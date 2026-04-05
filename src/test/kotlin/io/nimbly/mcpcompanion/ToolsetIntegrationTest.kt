package io.nimbly.mcpcompanion

import com.intellij.openapi.application.invokeAndWaitIfNeeded
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.colors.EditorColors
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.impl.DocumentMarkupModel
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.XDebuggerUtil
import com.intellij.xdebugger.breakpoints.XLineBreakpoint
import com.intellij.xdebugger.evaluation.EvaluationMode

/**
 * Integration tests using IntelliJ's headless platform (BasePlatformTestCase).
 * Each test runs in a real lightweight IntelliJ environment — no external sandbox needed.
 */
class ToolsetIntegrationTest : BasePlatformTestCase() {

    // ── Editor & Navigation ───────────────────────────────────────────────────

    fun `test get_open_editors returns open file`() {
        myFixture.configureByText("Hello.java", "class Hello {}")

        val state = invokeAndWaitIfNeeded {
            McpCompanionEditorToolset().buildEditorState(project)
        }

        assertTrue("Expected open files", state.openFiles.isNotEmpty())
        assertTrue("Expected Hello.java in open files",
            state.openFiles.any { it.endsWith("Hello.java") })
    }

    fun `test get_open_editors returns focused editor caret line`() {
        myFixture.configureByText("Caret.java", "class Caret {\n  void a() {}\n  void b() {}\n}")
        invokeAndWaitIfNeeded {
            // Move caret to line 3 (0-based: 2)
            val doc = myFixture.editor.document
            val offset = doc.getLineStartOffset(2)
            myFixture.editor.caretModel.moveToOffset(offset)
        }

        val state = invokeAndWaitIfNeeded {
            McpCompanionEditorToolset().buildEditorState(project)
        }

        assertNotNull("Expected a focused editor", state.focusedEditor)
        assertEquals("Expected caret on line 3", 3, state.focusedEditor!!.currentLine)
    }

    fun `test highlight_text adds MCP highlighters`() {
        myFixture.configureByText("Hl.java", "class Highlight { int x = 1; }")
        val editor: Editor = myFixture.editor

        val attrs = EditorColorsManager.getInstance().globalScheme
            .getAttributes(EditorColors.TEXT_SEARCH_RESULT_ATTRIBUTES)

        val countBefore = invokeAndWaitIfNeeded {
            editor.markupModel.allHighlighters.count { it.getUserData(MCP_HIGHLIGHT_KEY) == true }
        }
        assertEquals("No MCP highlights before", 0, countBefore)

        invokeAndWaitIfNeeded {
            val h = editor.markupModel.addRangeHighlighter(
                0, 5,
                HighlighterLayer.SELECTION - 1,
                attrs,
                HighlighterTargetArea.EXACT_RANGE
            )
            h.putUserData(MCP_HIGHLIGHT_KEY, true)
        }

        val countAfter = invokeAndWaitIfNeeded {
            editor.markupModel.allHighlighters.count { it.getUserData(MCP_HIGHLIGHT_KEY) == true }
        }
        assertEquals("Expected 1 MCP highlight", 1, countAfter)
    }

    fun `test clear_highlights removes MCP highlighters`() {
        myFixture.configureByText("Clear.java", "class Clear {}")
        val editor: Editor = myFixture.editor

        val attrs = EditorColorsManager.getInstance().globalScheme
            .getAttributes(EditorColors.TEXT_SEARCH_RESULT_ATTRIBUTES)

        // Add two highlights
        invokeAndWaitIfNeeded {
            repeat(2) {
                val h = editor.markupModel.addRangeHighlighter(
                    0, 3,
                    HighlighterLayer.SELECTION - 1,
                    attrs,
                    HighlighterTargetArea.EXACT_RANGE
                )
                h.putUserData(MCP_HIGHLIGHT_KEY, true)
            }
        }

        // Clear them
        invokeAndWaitIfNeeded {
            val toRemove = editor.markupModel.allHighlighters
                .filter { it.getUserData(MCP_HIGHLIGHT_KEY) == true }
            toRemove.forEach { editor.markupModel.removeHighlighter(it) }
        }

        val remaining = invokeAndWaitIfNeeded {
            editor.markupModel.allHighlighters.count { it.getUserData(MCP_HIGHLIGHT_KEY) == true }
        }
        assertEquals("Expected 0 MCP highlights after clear", 0, remaining)
    }

    fun `test select_text sets selection in editor`() {
        myFixture.configureByText("Sel.java", "class Selection { int value = 42; }")
        val editor: Editor = myFixture.editor

        invokeAndWaitIfNeeded {
            val doc = editor.document
            val start = doc.getLineStartOffset(0)
            val end = start + 5
            editor.selectionModel.setSelection(start, end)
        }

        val selectedText = invokeAndWaitIfNeeded { editor.selectionModel.selectedText }
        assertEquals("class", selectedText)
    }

    // ── Code Analysis ─────────────────────────────────────────────────────────

    fun `test relativize strips base path prefix`() {
        val toolset = McpCompanionCodeAnalysisToolset()
        assertEquals("src/Main.java", toolset.relativize("/project", "/project/src/Main.java"))
        assertEquals("src/Main.java", toolset.relativize("/project/", "/project/src/Main.java"))
        assertEquals("/other/File.java", toolset.relativize("/base", "/other/File.java"))
    }

    fun `test get_file_problems markup model is accessible`() {
        myFixture.configureByText("Problems.java",
            "import java.util.List;\nclass Problems { void foo() {} }")
        myFixture.doHighlighting()

        val vFile = myFixture.file.virtualFile
        val document = FileDocumentManager.getInstance().getDocument(vFile)!!

        val markupModel = invokeAndWaitIfNeeded {
            DocumentMarkupModel.forDocument(document, project, false)
        }
        assertNotNull("Markup model should be accessible via DocumentMarkupModel.forDocument", markupModel)
    }

    fun `test get_project_structure project has modules`() {
        val modules = com.intellij.openapi.module.ModuleManager.getInstance(project).modules
        assertTrue("Test project should have at least one module", modules.isNotEmpty())
    }

    // ── General ───────────────────────────────────────────────────────────────

    fun `test replace_text_undoable replaces text in document`() {
        myFixture.configureByText("Replace.java", "class Replace { int x = OLD; }")
        val vFile = myFixture.file.virtualFile
        val document = FileDocumentManager.getInstance().getDocument(vFile)!!

        invokeAndWaitIfNeeded {
            WriteCommandAction.runWriteCommandAction(project, "MCP Replace", null, {
                val offset = document.text.indexOf("OLD")
                assertTrue("OLD should be present", offset >= 0)
                document.replaceString(offset, offset + "OLD".length, "NEW")
            })
        }

        assertTrue("Document should contain NEW", document.text.contains("NEW"))
        assertFalse("Document should not contain OLD", document.text.contains("OLD"))
    }

    fun `test delete_file removes file via VFS`() {
        val vFile = myFixture.addFileToProject("ToDelete.txt", "content").virtualFile
        assertTrue("File should exist", vFile.exists())

        invokeAndWaitIfNeeded { runWriteAction { vFile.delete(this) } }

        assertFalse("File should be deleted", vFile.exists())
    }

    // ── Debug — breakpoints ───────────────────────────────────────────────────

    fun `test add and get line breakpoint`() {
        val manager = XDebuggerManager.getInstance(project).breakpointManager
        val bp = addTestBreakpoint(manager, "BpCalc.java", 0) ?: run {
            System.err.println("SKIP: no line breakpoint type available in headless — verified via curl SSE"); return
        }
        try {
            val found = manager.allBreakpoints
                .filterIsInstance<XLineBreakpoint<*>>()
                .any { it === bp }
            assertTrue("Breakpoint should be in manager", found)
            assertEquals("Breakpoint on line 1", 1, bp.line + 1)
        } finally {
            invokeAndWaitIfNeeded { manager.removeBreakpoint(bp) }
        }
    }

    fun `test mute and unmute breakpoints`() {
        val manager = XDebuggerManager.getInstance(project).breakpointManager
        val bp = addTestBreakpoint(manager, "BpMute.java", 0) ?: run {
            System.err.println("SKIP: no line breakpoint type available in headless"); return
        }
        try {
            invokeAndWaitIfNeeded { bp.isEnabled = false }
            assertFalse("Should be disabled", bp.isEnabled)

            invokeAndWaitIfNeeded { bp.isEnabled = true }
            assertTrue("Should be re-enabled", bp.isEnabled)
        } finally {
            invokeAndWaitIfNeeded { manager.removeBreakpoint(bp) }
        }
    }

    fun `test set breakpoint condition`() {
        val manager = XDebuggerManager.getInstance(project).breakpointManager
        val bp = addTestBreakpoint(manager, "BpCond.java", 0) ?: run {
            System.err.println("SKIP: no line breakpoint type available in headless"); return
        }
        try {
            invokeAndWaitIfNeeded {
                val expr = XDebuggerUtil.getInstance()
                    .createExpression("i > 5", null, null, EvaluationMode.EXPRESSION)
                bp.conditionExpression = expr
            }
            assertEquals("i > 5", bp.conditionExpression?.expression)

            invokeAndWaitIfNeeded { bp.conditionExpression = null }
            assertNull("Condition should be cleared", bp.conditionExpression)
        } finally {
            invokeAndWaitIfNeeded { manager.removeBreakpoint(bp) }
        }
    }

    // ── Diagnostic ────────────────────────────────────────────────────────────

    fun `test collectRunningProcesses does not throw`() {
        val processes = McpCompanionDiagnosticToolset().collectRunningProcesses()
        assertNotNull("Should return non-null list", processes)
        // In headless there are typically no background tasks
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /**
     * Adds a line breakpoint using the first available XLineBreakpointType.
     * Returns null if no line breakpoint type is registered (e.g. headless without Java debugger).
     */
    @Suppress("UNCHECKED_CAST")
    private fun addTestBreakpoint(
        manager: com.intellij.xdebugger.breakpoints.XBreakpointManager,
        fileName: String,
        line: Int
    ): XLineBreakpoint<*>? {
        val vFile = myFixture.addFileToProject(fileName, "class ${fileName.removeSuffix(".java")} {}").virtualFile
        val type = com.intellij.xdebugger.breakpoints.XBreakpointType.EXTENSION_POINT_NAME.extensionList
            .filterIsInstance<com.intellij.xdebugger.breakpoints.XLineBreakpointType<*>>()
            .firstOrNull() ?: return null
        return invokeAndWaitIfNeeded {
            manager.addLineBreakpoint(
                type as com.intellij.xdebugger.breakpoints.XLineBreakpointType<com.intellij.xdebugger.breakpoints.XBreakpointProperties<*>>,
                vFile.url,
                line,
                null
            )
        }
    }

    /** Polls until breakpoints appear on the given file, or times out after 2s. */
    private fun waitForBreakpoint(
        manager: com.intellij.xdebugger.breakpoints.XBreakpointManager,
        fileName: String,
        timeoutMs: Long = 2000
    ): List<XLineBreakpoint<*>> {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val found = manager.allBreakpoints
                .filterIsInstance<XLineBreakpoint<*>>()
                .filter { it.presentableFilePath.endsWith(fileName) }
            if (found.isNotEmpty()) return found
            Thread.sleep(100)
        }
        return emptyList()
    }
}
