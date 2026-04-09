/*
 * This file is part of "SAP Commerce Developers Toolset" plugin for IntelliJ IDEA.
 * Copyright (C) 2019-2026 EPAM Systems <hybrisideaplugin@epam.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package sap.commerce.toolset.debugger.ui.tree.render

import com.intellij.debugger.DebuggerContext
import com.intellij.debugger.engine.DebugProcessImpl
import com.intellij.debugger.engine.DebuggerManagerThreadImpl
import com.intellij.debugger.engine.evaluation.EvaluationContext
import com.intellij.debugger.impl.DebuggerUtilsAsync
import com.intellij.debugger.ui.impl.watch.ValueDescriptorImpl
import com.intellij.debugger.ui.tree.DebuggerTreeNode
import com.intellij.debugger.ui.tree.NodeDescriptor
import com.intellij.debugger.ui.tree.ValueDescriptor
import com.intellij.debugger.ui.tree.render.ChildrenBuilder
import com.intellij.debugger.ui.tree.render.ChildrenRenderer
import com.intellij.debugger.ui.tree.render.ReferenceRenderer
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.util.asSafely
import com.sun.jdi.Method
import com.sun.jdi.ObjectReference
import com.sun.jdi.Value
import sap.commerce.toolset.beanSystem.meta.BSMetaHelper
import sap.commerce.toolset.beanSystem.meta.BSMetaModelAccess
import sap.commerce.toolset.beanSystem.meta.model.BSGlobalMetaBean
import sap.commerce.toolset.beanSystem.meta.model.BSMetaProperty
import sap.commerce.toolset.debugger.ui.tree.LazyMethodValueDescriptor
import sap.commerce.toolset.debugger.ui.tree.MethodValueDescriptor
import java.util.concurrent.CompletableFuture

internal class BeanChildrenRenderer : ReferenceRenderer("java.lang.Object"), ChildrenRenderer {

    override fun getUniqueId() = "[y] Bean Children Renderer"

    override fun isExpandableAsync(value: Value, evaluationContext: EvaluationContext, parentDescriptor: NodeDescriptor): CompletableFuture<Boolean> = DebugProcessImpl
        .getDefaultRenderer(value)
        .isExpandableAsync(value, evaluationContext, parentDescriptor)

    override fun buildChildren(
        value: Value,
        builder: ChildrenBuilder,
        evaluationContext: EvaluationContext
    ) {
        DebuggerManagerThreadImpl.assertIsManagerThread()

        val objectReference = value.asSafely<ObjectReference>() ?: return
        val parentDescriptor = builder.parentDescriptor as? ValueDescriptorImpl ?: return
        val nodeManager = builder.nodeManager
        val project = parentDescriptor.project
        val type = objectReference.referenceType()

        if (DumbService.isDumb(project)) {
            val message = "Direct fields access is not available during the re-index..."
            builder.addChildren(listOf(nodeManager.createMessageNode(message)), false)
            DebugProcessImpl.getDefaultRenderer(value).buildChildren(value, builder, evaluationContext)
            return
        }

        // Look up the bean using the full class FQN — this matches how beans are keyed in BSMetaModelAccess
        val meta = BSMetaModelAccess.getInstance(project).findMetaBeanByName(type.name())
        if (meta == null) {
            // Not a known SAP Commerce bean; let IntelliJ's default renderer handle this object
            DebugProcessImpl.getDefaultRenderer(value).buildChildren(value, builder, evaluationContext)
            return
        }

        DebuggerUtilsAsync.allMethods(type).thenApply { allMethods ->
            // Only consider no-arg get/is methods, excluding java.lang.Object methods and constructors
            val groupedMethods = allMethods
                .filter { method -> method.name().startsWith("get") || method.name().startsWith("is") }
                .filter { method -> !method.isAbstract }
                .filter { method -> method.argumentTypes().isEmpty() }
                .filter { method -> method.declaringType().name() != "java.lang.Object" }
                .distinctBy { method -> method.name() }
                .groupBy { method -> method.declaringType().name() }

            groupedMethods.forEach { (declaringTypeName, methods) ->
                // Use the simple class name as the group header (e.g. "CartData | 5 properties")
                val shortTypeName = declaringTypeName.substringAfterLast('.')
                val descriptors = methods
                    .mapNotNull { method ->
                        propertyNodeDescriptor(project, meta, objectReference, parentDescriptor, method, method.name())
                            ?: MethodValueDescriptor(objectReference, parentDescriptor, method, method.name(), project)
                    }
                    .distinctBy { it.name }

                val groupNode = nodeManager.createMessageNode("$shortTypeName | ${descriptors.size} properties")
                val nodes = descriptors.map { descriptor -> nodeManager.createNode(descriptor, evaluationContext) }

                builder.addChildren(listOf(groupNode), false)
                builder.addChildren(nodes, false)
            }

            // Append the raw fields section at the bottom so advanced users can inspect stored values
            builder.addChildren(listOf(nodeManager.createMessageNode("Fields")), false)
            DebugProcessImpl.getDefaultRenderer(value).buildChildren(value, builder, evaluationContext)
        }
    }

    /**
     * Tries to match a getter method to a bean property declared in *-beans.xml.
     * Returns a [LazyMethodValueDescriptor] for collection/map properties (loaded on demand)
     * or a regular [MethodValueDescriptor] for scalar properties.
     * Returns null when the method does not correspond to any declared property.
     */
    private fun propertyNodeDescriptor(
        project: Project,
        meta: BSGlobalMetaBean,
        value: ObjectReference,
        parentDescriptor: ValueDescriptorImpl,
        method: Method,
        methodName: String
    ): NodeDescriptor? {
        val possiblePropertyName = getPossiblePropertyName(methodName) ?: return null
        val property = meta.allProperties[possiblePropertyName] ?: return null
        val propertyName = property.name ?: return null

        return if (isCollectionOrMapType(property)) {
            // Load collections and maps lazily to avoid freezing the debugger on large structures
            LazyMethodValueDescriptor(
                value,
                parentDescriptor,
                method,
                buildString {
                    append(propertyName)
                    append(" (")
                    append(BSMetaHelper.flattenType(property) ?: property.type ?: "collection/map")
                    append(")")
                },
                project
            )
        } else {
            MethodValueDescriptor(value, parentDescriptor, method, propertyName, project)
        }
    }

    /**
     * Strips the "get" or "is" prefix from a getter name and lowercases the first character
     * to produce the property name used as a key in [BSGlobalMetaBean.allProperties].
     *
     * Examples: "getName" → "name", "isActive" → "active", "getFirstName" → "firstName"
     */
    private fun getPossiblePropertyName(methodName: String): String? = when {
        methodName.startsWith("get") && methodName.length > 3 ->
            methodName.removePrefix("get").replaceFirstChar(Char::lowercase)

        methodName.startsWith("is") && methodName.length > 2 ->
            methodName.removePrefix("is").replaceFirstChar(Char::lowercase)

        else -> null
    }

    /**
     * Returns true when the property type is a collection or map (List, Set, Collection, Map).
     * Uses [BSMetaHelper.flattenType] to normalise the type string before checking,
     * so both "java.util.List<XData>" and "List<XData>" are handled.
     */
    private fun isCollectionOrMapType(property: BSMetaProperty): Boolean {
        val flat = BSMetaHelper.flattenType(property) ?: return false
        return flat.startsWith("List") ||
            flat.startsWith("Collection") ||
            flat.startsWith("Set") ||
            flat.startsWith("Map") ||
            flat.startsWith("Queue") ||
            flat.startsWith("Deque")
    }

    override fun getChildValueExpression(node: DebuggerTreeNode, context: DebuggerContext) = node.descriptor
        .asSafely<ValueDescriptor>()
        ?.getDescriptorEvaluation(context)
}
