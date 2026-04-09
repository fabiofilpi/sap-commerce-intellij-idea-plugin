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

package sap.commerce.toolset.debugger.engine.managerThread

import com.intellij.debugger.JavaDebuggerBundle
import com.intellij.debugger.engine.DebuggerUtils
import com.intellij.debugger.engine.evaluation.EvaluateException
import com.intellij.debugger.engine.evaluation.EvaluationContext
import com.intellij.debugger.engine.evaluation.EvaluationContextImpl
import com.intellij.debugger.impl.descriptors.data.UserExpressionData
import com.intellij.debugger.ui.impl.watch.UserExpressionDescriptorImpl
import com.intellij.debugger.ui.impl.watch.ValueDescriptorImpl
import com.intellij.debugger.ui.tree.ValueDescriptor
import com.intellij.debugger.ui.tree.render.DescriptorLabelListener
import com.intellij.debugger.ui.tree.render.ToStringCommand
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.sun.jdi.ObjectReference
import com.sun.jdi.Type
import com.sun.jdi.Value
import sap.commerce.toolset.beanSystem.meta.BSMetaHelper
import sap.commerce.toolset.beanSystem.meta.BSMetaModelAccess

internal class BeanToStringCommand(
    private val valueDescriptor: ValueDescriptor,
    private val labelListener: DescriptorLabelListener,
    evaluationContext: EvaluationContext,
    value: ObjectReference
) : ToStringCommand(evaluationContext, value) {

    override fun evaluationResult(message: String?) {
        valueDescriptor.setValueLabel(StringUtil.notNullize(message))
        labelListener.labelChanged()
    }

    override fun evaluationError(message: String?) {
        val typeName = value.type().name()
        val msg = "$message " + JavaDebuggerBundle.message("evaluation.error.cannot.evaluate.tostring", typeName)
        valueDescriptor.setValueLabelFailed(EvaluateException(msg, null))
        labelListener.labelChanged()
    }

    override fun action() {
        val project = evaluationContext.project
        val expression = toStringExpression(value.type(), project).trimIndent()
        val text = DebuggerUtils.getInstance().createExpressionWithImports(expression)

        val descriptor = UserExpressionData(
            valueDescriptor as ValueDescriptorImpl,
            value.type().name(),
            "toString_renderer_" + value.type().name(),
            text
        )
            .createDescriptor(project) as UserExpressionDescriptorImpl

        try {
            val calcValue = descriptor.calcValue(evaluationContext as EvaluationContextImpl)
            val valueAsString = DebuggerUtils.getValueAsString(evaluationContext, calcValue)
            evaluationResult(valueAsString)
        } catch (_: EvaluateException) {
            fallback(evaluationContext, value)
        }
    }

    private fun fallback(evaluationContext: EvaluationContext, value: Value) {
        try {
            val valueAsString = DebuggerUtils.getValueAsString(evaluationContext, value)
            evaluationResult(valueAsString)
        } catch (e: EvaluateException) {
            evaluationError(e.message)
        }
    }

    private fun toStringExpression(type: Type, project: Project): String {
        // Use the full class name as the key — beans.xml declares classes by FQN (without generics)
        val meta = BSMetaModelAccess.getInstance(project).findMetaBeanByName(type.name())
            ?: return fallbackExpression()

        // Build expression from the first 3 non-null property getters
        val getters = meta.allProperties.values
            .take(3)
            .mapNotNull { prop ->
                prop.name?.let { "get${it.replaceFirstChar(Char::uppercase)}()" }
            }

        return if (getters.isEmpty()) fallbackExpression()
        else buildToStringExpression(getters)
    }

    /**
     * Builds a Java expression that concatenates up to 3 bean property values separated by " | ".
     * Uses Object intermediate variables so any return type is safely handled (no cast errors).
     * Null values are displayed as "?".
     */
    private fun buildToStringExpression(getters: List<String>): String {
        val fieldDeclarations = getters.mapIndexed { index, getter ->
            "Object _f$index = $getter; String fieldValue$index = _f$index == null ? \"?\" : _f$index.toString();"
        }.joinToString("\n")

        val concat = getters.indices.joinToString(" + \" | \" + ") { "fieldValue$it" }

        return """
            $fieldDeclarations
            $concat
        """.trimIndent()
    }

    /**
     * Fallback when the bean is not found in the meta model or has no properties.
     * Delegates to the object's own toString() so the debugger still shows something useful.
     */
    private fun fallbackExpression() = "toString()"
}
