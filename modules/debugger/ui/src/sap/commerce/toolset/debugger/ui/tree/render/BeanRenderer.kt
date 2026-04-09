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

import com.intellij.debugger.JavaDebuggerBundle
import com.intellij.debugger.engine.DebuggerUtils
import com.intellij.debugger.engine.FullValueEvaluatorProvider
import com.intellij.debugger.engine.JavaValue.JavaFullValueEvaluator
import com.intellij.debugger.engine.evaluation.EvaluationContextImpl
import com.intellij.debugger.ui.impl.watch.ValueDescriptorImpl
import com.intellij.debugger.ui.tree.render.ChildrenRenderer
import com.intellij.debugger.ui.tree.render.CompoundRendererProvider
import com.intellij.debugger.ui.tree.render.ValueLabelRenderer
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.util.PsiNavigateUtil
import com.sun.jdi.ClassType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sap.commerce.toolset.beanSystem.meta.BSMetaModelAccess

/**
 * Custom compound renderer for SAP Commerce DTO/Bean classes generated from *-beans.xml.
 *
 * Because SAP Commerce beans have no common base class (unlike Models which all extend
 * AbstractItemModel), this renderer is registered against java.lang.Object and relies on
 * [BeanNodeRenderer] and [BeanChildrenRenderer] to self-select only when the runtime type
 * is found in [BSMetaModelAccess]. This makes it a last-resort renderer: more specific
 * IntelliJ built-in renderers (String, Collection, etc.) always take priority.
 */
class BeanRenderer : CompoundRendererProvider() {

    override fun getName() = "[y] Bean Renderer"

    /**
     * Targets java.lang.Object so that all SAP Commerce bean instances (which have no shared
     * base class) can be matched. Both the label renderer and the children renderer perform
     * an early lookup in BSMetaModelAccess and fall back immediately for non-bean types,
     * so the performance impact on unrelated objects is minimal (one ConcurrentHashMap read).
     */
    override fun getClassName() = "java.lang.Object"

    override fun isEnabled() = true

    override fun getChildrenRenderer(): ChildrenRenderer = BeanChildrenRenderer()

    override fun getValueLabelRenderer(): ValueLabelRenderer = BeanNodeRenderer()

    /**
     * Provides a "Navigate to source" link in the debugger popup for known bean types,
     * navigating to the generated Java class corresponding to the bean declaration.
     * Returns null (no link) for non-bean objects so the popup stays clean.
     */
    override fun getFullValueEvaluatorProvider(): FullValueEvaluatorProvider =
        FullValueEvaluatorProvider { evaluationContext: EvaluationContextImpl, valueDescriptor: ValueDescriptorImpl ->
            val value = valueDescriptor.getValue() ?: return@FullValueEvaluatorProvider null
            val type = value.type() as? ClassType ?: return@FullValueEvaluatorProvider null

            // Gate: only attach the evaluator when the type is a known bean
            val project = valueDescriptor.project
            BSMetaModelAccess.getInstance(project).findMetaBeanByName(type.name())
                ?: return@FullValueEvaluatorProvider null

            object : JavaFullValueEvaluator(JavaDebuggerBundle.message("message.node.navigate"), evaluationContext) {
                override fun evaluate(callback: XFullValueEvaluationCallback) {
                    callback.evaluated("")

                    CoroutineScope(Dispatchers.Default).launch {
                        val psiClass = readAction {
                            DebuggerUtils.findClass(type.name(), project, evaluationContext.debugProcess.searchScope)
                        } ?: return@launch

                        val navigationElement = readAction { psiClass.navigationElement }
                        val navigatable = readAction { PsiNavigateUtil.getNavigatable(navigationElement) }
                            ?: return@launch

                        withContext(Dispatchers.EDT) {
                            navigatable.navigate(true)
                        }
                    }
                }

                override fun isShowValuePopup() = false
            }
        }
}
