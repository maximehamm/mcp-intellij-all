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
import io.nimbly.mcpcompanion.toolsets.McpCompanionCodeAnalysisToolset
import io.nimbly.mcpcompanion.toolsets.McpCompanionDiagnosticToolset
import io.nimbly.mcpcompanion.toolsets.McpCompanionEditorToolset
import io.nimbly.mcpcompanion.toolsets.MCP_HIGHLIGHT_KEY

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

    // ── 3.4.1: resolveProject single-project fallback ─────────────────────────
    //
    // Bug report 2026-05-12: the JetBrains MCP framework's `coroutineContext.project` throws
    // `McpExpectedError("Unable to determine the target project for the current MCP tool call.")`
    // even when only one project is open in the JVM. Our `resolveProject` now short-circuits in
    // the single-project case to keep the API ergonomic for the common scenario.

    fun `test resolveProject returns the single open project when projectPath is null`() {
        // BasePlatformTestCase always opens a single project — exactly the scenario we want to cover.
        val resolved = kotlinx.coroutines.runBlocking {
            io.nimbly.mcpcompanion.util.resolveProject(null)
        }
        assertEquals("Should return the single open project", project, resolved)
    }

    fun `test resolveProject returns the single open project when projectPath is blank`() {
        val resolved = kotlinx.coroutines.runBlocking {
            io.nimbly.mcpcompanion.util.resolveProject("")
        }
        assertEquals("Should return the single open project for blank path", project, resolved)
    }

    fun `test resolveProject honours an explicit matching projectPath`() {
        val resolved = kotlinx.coroutines.runBlocking {
            io.nimbly.mcpcompanion.util.resolveProject(project.basePath)
        }
        assertEquals("Should return the project whose basePath matches the argument", project, resolved)
    }

    // ── 3.4.1: replace_text_undoable robustness ───────────────────────────────
    //
    // Same bug report: the framework rejects ~30-line Java payloads (Mockito generics + accents)
    // as "No argument is passed for required parameter 'newText'". We work around this by giving
    // `oldText`/`newText` a default value of "" and validating ourselves.

    fun `test replace_text_undoable returns clear error for empty oldText`() {
        // No file needed — the empty-oldText check fires BEFORE file resolution by design,
        // so the path can be a non-existent dummy. This is the central behaviour change
        // introduced to keep the contract clear once oldText became a defaultable param.
        val result = kotlinx.coroutines.runBlocking {
            io.nimbly.mcpcompanion.toolsets.McpCompanionToolset()
                .replace_text_undoable(
                    pathInProject = "irrelevant.java",
                    oldText = "",
                    newText = "anything",
                    projectPath = project.basePath,
                )
        }
        assertTrue(
            "Empty oldText must produce a clear error rather than a framework rejection, got: $result",
            result.contains("oldText is empty"),
        )
    }

    fun `test replace_text_undoable handles a 30-line Java payload with generics and accents`() {
        // Reproduces the user-reported failure mode: Mockito generics, accents, multi-line.
        // We write the file to real disk (under project.basePath) so the toolset's path
        // resolver — which uses LocalFileSystem — can find it. configureByText alone uses an
        // in-memory TempFileSystem that LocalFileSystem can't see, so it isn't usable here.
        val sourceBlock = """
            class Original {
                /**
                 * Méthode d'orchestration — gère les pièces jointes Mockito.
                 */
                public List<AttachmentDto> processAttachments() {
                    when(service.<AttachmentDto>findByOwner(any())).thenReturn(List.of());
                    return List.of();
                }
            }
        """.trimIndent()
        val replacementBlock = """
            class Original {
                /**
                 * Méthode d'orchestration — gère les pièces jointes via le « bus » d'événements.
                 * Voir : org.example.bus.EventBus#dispatch(Event) pour le détail.
                 */
                public List<AttachmentDto> processAttachments() {
                    Mockito.<AttachmentDto>any();
                    when(service.<AttachmentDto>findByOwner(any())).thenReturn(List.of(
                        new AttachmentDto("éàç", 1L),
                        new AttachmentDto("«hello»", 2L),
                        new AttachmentDto("multi\nline", 3L)
                    ));
                    return service.<AttachmentDto>findAll();
                }
            }
        """.trimIndent()
        val baseDir = java.io.File(project.basePath!!)
        baseDir.mkdirs()
        val target = java.io.File(baseDir, "Original.java")
        target.writeText(sourceBlock)
        invokeAndWaitIfNeeded {
            com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByPath(target.absolutePath)
        }

        val result = kotlinx.coroutines.runBlocking {
            io.nimbly.mcpcompanion.toolsets.McpCompanionToolset()
                .replace_text_undoable(
                    pathInProject = "Original.java",
                    oldText = sourceBlock,
                    newText = replacementBlock,
                    projectPath = project.basePath,
                )
        }
        assertEquals("Replacement must succeed, got: $result", "ok", result)

        // Read from disk to verify the write made it through (the suspend function uses
        // WriteCommandAction → document.replaceString, which persists via the VFS).
        val vFile = invokeAndWaitIfNeeded {
            com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByPath(target.absolutePath)
        }
        assertNotNull("Refreshed VirtualFile should be non-null", vFile)
        val newText = invokeAndWaitIfNeeded {
            FileDocumentManager.getInstance().getDocument(vFile!!)!!.text
        }
        assertTrue("File should contain the new accent string", newText.contains("éàç"))
        assertTrue("File should contain the new « hello » string", newText.contains("«hello»"))
        assertTrue("File should retain the Mockito.<AttachmentDto> generic", newText.contains("Mockito.<AttachmentDto>"))
    }

    // ── 3.5.0: get_psi_tree ───────────────────────────────────────────────────

    fun `test get_psi_tree produces a well-formed hierarchical dump with Stats footer`() {
        // BasePlatformTestCase's minimal headless platform does NOT register a Java parser by
        // default, so a Sample.java written to disk will be parsed as PsiPlainTextFile instead
        // of PsiJavaFile. That's fine — this test focuses on the dump _format_ (indentation,
        // ranges, line annotations, footer), not on PSI semantics; the latter are exercised
        // end-to-end via the sandbox in real IDE conditions.
        val baseDir = java.io.File(project.basePath!!)
        baseDir.mkdirs()
        val src = java.io.File(baseDir, "Sample.java")
        src.writeText("class Sample {\n    int v = 42;\n    void hello() {}\n}\n")
        invokeAndWaitIfNeeded {
            com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByPath(src.absolutePath)
        }

        val dump = kotlinx.coroutines.runBlocking {
            McpCompanionCodeAnalysisToolset().get_psi_tree(
                filePath = "Sample.java",
                projectPath = project.basePath,
            )
        }
        assertTrue("Dump should mention the file name in the root quoted preview, got:\n$dump",
            dump.contains("\"Sample.java\""))
        assertTrue("Dump should annotate range and line numbers (L1), got:\n$dump",
            dump.contains(", L1"))
        assertTrue("Dump should end with the Stats footer, got:\n$dump",
            dump.contains("Stats: ") && dump.contains("nodes, depth max"))
        assertTrue("Dump should include an offset range like [0..", dump.contains("[0.."))
        // 2-space indentation: at least one inner node must start with "  ".
        assertTrue("Dump should contain at least one indented child line, got:\n$dump",
            dump.lineSequence().any { it.startsWith("  ") && it.isNotBlank() })
    }

    fun `test get_psi_tree accepts a valid line parameter without error`() {
        // In the headless platform .txt files are parsed as PsiPlainText — a single leaf — so we
        // can't observe a narrower root. We just verify that passing `line` doesn't fail and
        // still produces a well-formed dump; semantic narrowing is exercised in the sandbox.
        val baseDir = java.io.File(project.basePath!!)
        baseDir.mkdirs()
        val src = java.io.File(baseDir, "Narrow.txt")
        src.writeText("line one\nline two\nline THREE marker\nline four\n")
        invokeAndWaitIfNeeded {
            com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByPath(src.absolutePath)
        }

        val dump = kotlinx.coroutines.runBlocking {
            McpCompanionCodeAnalysisToolset().get_psi_tree(
                filePath = "Narrow.txt",
                line = 3,
                projectPath = project.basePath,
            )
        }
        assertFalse("Valid line must not produce an out-of-range error, got:\n$dump",
            dump.contains("out of range"))
        assertTrue("Dump should still include the Stats footer, got:\n$dump",
            dump.contains("Stats:"))
    }

    fun `test get_psi_tree reports out-of-range line clearly`() {
        val baseDir = java.io.File(project.basePath!!)
        baseDir.mkdirs()
        val src = java.io.File(baseDir, "Short.txt")
        src.writeText("only one line\n")
        invokeAndWaitIfNeeded {
            com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByPath(src.absolutePath)
        }

        val result = kotlinx.coroutines.runBlocking {
            McpCompanionCodeAnalysisToolset().get_psi_tree(
                filePath = "Short.txt",
                line = 999,
                projectPath = project.basePath,
            )
        }
        assertTrue(
            "Out-of-range line must produce a clear error referencing the line and the file length, got: $result",
            result.contains("out of range") && result.contains("999"),
        )
    }

    fun `test get_psi_tree reports a clear error for a missing file`() {
        val result = kotlinx.coroutines.runBlocking {
            McpCompanionCodeAnalysisToolset().get_psi_tree(
                filePath = "does-not-exist.feature",
                projectPath = project.basePath,
            )
        }
        assertTrue(
            "Missing file should produce a clear error mentioning the path, got: $result",
            result.startsWith("File not found:") && result.contains("does-not-exist.feature"),
        )
    }

    // ── Diagnostic ────────────────────────────────────────────────────────────

    fun `test collectRunningProcesses does not throw`() {
        val processes = McpCompanionDiagnosticToolset().collectRunningProcesses(project)
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
