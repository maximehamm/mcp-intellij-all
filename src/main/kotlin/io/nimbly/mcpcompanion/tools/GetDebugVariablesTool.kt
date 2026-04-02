package io.nimbly.mcpcompanion.tools

import com.intellij.openapi.application.invokeAndWaitIfNeeded
import com.intellij.openapi.project.Project
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.frame.XCompositeNode
import com.intellij.xdebugger.frame.XDebuggerTreeNodeHyperlink
import com.intellij.xdebugger.frame.XFullValueEvaluator
import com.intellij.xdebugger.frame.XValue
import com.intellij.xdebugger.frame.XValueChildrenList
import com.intellij.xdebugger.frame.XValueNode
import com.intellij.xdebugger.frame.XValuePlace
import com.intellij.xdebugger.frame.presentation.XValuePresentation
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.ide.mcp.NoArgs
import org.jetbrains.ide.mcp.Response
import org.jetbrains.mcpserverplugin.AbstractMcpTool
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.swing.Icon

class GetDebugVariablesTool : AbstractMcpTool<NoArgs>(NoArgs.serializer()) {

    override val name: String = "get_debug_variables"

    override val description: String = """
        Returns the local variables and their values from the current debugger stack frame.
        Only available when a debug session is paused at a breakpoint.
        Useful to inspect variable state during debugging.
    """.trimIndent()

    override fun handle(project: Project, args: NoArgs): Response {
        val sessions = XDebuggerManager.getInstance(project).debugSessions
        if (sessions.isEmpty()) return Response("No active debug session")

        val session = sessions.first()
        val frame = invokeAndWaitIfNeeded { session.currentStackFrame }
            ?: return Response("No stack frame — program may still be running")

        // 1. Collecter les enfants sur l'EDT
        val children = mutableListOf<Pair<String, XValue>>()
        val childrenLatch = CountDownLatch(1)

        invokeAndWaitIfNeeded {
            frame.computeChildren(object : XCompositeNode {
                override fun addChildren(list: XValueChildrenList, last: Boolean) {
                    for (i in 0 until list.size()) children.add(list.getName(i) to list.getValue(i))
                    if (last) childrenLatch.countDown()
                }
                override fun tooManyChildren(remaining: Int) { childrenLatch.countDown() }
                override fun tooManyChildren(remaining: Int, addNextChildren: Runnable) { childrenLatch.countDown() }
                override fun setAlreadySorted(alreadySorted: Boolean) {}
                override fun setErrorMessage(errorMessage: String) { childrenLatch.countDown() }
                override fun setErrorMessage(errorMessage: String, link: XDebuggerTreeNodeHyperlink?) { childrenLatch.countDown() }
                override fun setMessage(message: String, icon: Icon?, attributes: com.intellij.ui.SimpleTextAttributes, link: XDebuggerTreeNodeHyperlink?) {}
                override fun isObsolete() = false
            })
        }
        childrenLatch.await(5, TimeUnit.SECONDS)

        // 2. Résoudre les présentations HORS EDT (évite le deadlock)
        val variables = children.map { (name, xValue) -> resolveValue(name, xValue) }

        return Response(Json.encodeToString(DebugVariablesOutput(session = session.sessionName, variables = variables)))
    }

    private fun resolveValue(name: String, xValue: XValue): DebugVariable {
        var type: String? = null
        var value: String? = null
        val latch = CountDownLatch(1)

        xValue.computePresentation(object : XValueNode {
            override fun setPresentation(icon: Icon?, type_: String?, value_: String, hasChildren: Boolean) {
                type = type_; value = value_; latch.countDown()
            }
            override fun setPresentation(icon: Icon?, presentation: XValuePresentation, hasChildren: Boolean) {
                type = presentation.type
                val sb = StringBuilder()
                presentation.renderValue(object : XValuePresentation.XValueTextRenderer {
                    override fun renderValue(v: String) { sb.append(v) }
                    override fun renderValue(v: String, key: com.intellij.openapi.editor.colors.TextAttributesKey) { sb.append(v) }
                    override fun renderStringValue(v: String) { sb.append("\"$v\"") }
                    override fun renderStringValue(v: String, extra: String?, maxLen: Int) { sb.append("\"$v\"") }
                    override fun renderNumericValue(v: String) { sb.append(v) }
                    override fun renderKeywordValue(v: String) { sb.append(v) }
                    override fun renderComment(v: String) {}
                    override fun renderSpecialSymbol(v: String) { sb.append(v) }
                    override fun renderError(v: String) { sb.append(v) }
                })
                value = sb.toString(); latch.countDown()
            }
            override fun setFullValueEvaluator(e: XFullValueEvaluator) {}
            override fun isObsolete() = false
        }, XValuePlace.TREE)

        latch.await(2, TimeUnit.SECONDS)
        return DebugVariable(name = name, type = type, value = value)
    }
}

@Serializable
data class DebugVariablesOutput(val session: String, val variables: List<DebugVariable>)

@Serializable
data class DebugVariable(val name: String, val type: String? = null, val value: String? = null)
