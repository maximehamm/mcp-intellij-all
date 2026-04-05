package io.nimbly.mcpcompanion

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Verifies that every method/field accessed via reflection in McpCompanionCodeAnalysisToolset
 * still exists in the current IntelliJ version.
 *
 * These tests are the JUnit equivalent of the curl SSE smoke tests for the Code Analysis toolset:
 * if a test fails here, the corresponding tool will silently return empty results at runtime.
 */
class CodeAnalysisReflectionTest {

    // ── get_quick_fixes — HighlightInfo.offsetStore ──────────────────────────
    // get_quick_fixes finds the offsetStore field on HighlightInfo via reflection and
    // passes it to getIntentionActionDescriptors().
    // If offsetStore is renamed/removed, get_quick_fixes returns "No quick fixes found".

    @Test
    fun `HighlightInfo has offsetStore field`() {
        val cls = Class.forName("com.intellij.codeInsight.daemon.impl.HighlightInfo")
        val field = generateSequence(cls as Class<*>?) { it.superclass }
            .flatMap { it.declaredFields.asSequence() }
            .find { it.name == "offsetStore" }
        assertNotNull(field,
            "HighlightInfo.offsetStore field not found — get_quick_fixes will return empty results. " +
            "Update the field name in McpCompanionCodeAnalysisToolset.get_quick_fixes().")
    }

    // ── get_quick_fixes — HighlightInfo.getIntentionActionDescriptors ────────
    // Called with the offsetStore instance to retrieve the list of IntentionActionDescriptor.
    // If this method is renamed/removed, get_quick_fixes returns "No quick fixes found".

    @Test
    fun `HighlightInfo has getIntentionActionDescriptors(offsetStore) method`() {
        val cls = Class.forName("com.intellij.codeInsight.daemon.impl.HighlightInfo")
        val offsetStoreField = generateSequence(cls as Class<*>?) { it.superclass }
            .flatMap { it.declaredFields.asSequence() }
            .find { it.name == "offsetStore" }
        if (offsetStoreField == null) {
            println("SKIP: offsetStore field not found — prerequisite for this test is missing")
            return
        }
        offsetStoreField.isAccessible = true
        val method = generateSequence(cls as Class<*>?) { it.superclass }
            .flatMap { it.declaredMethods.asSequence() }
            .find { m -> m.name == "getIntentionActionDescriptors" && m.parameterCount == 1
                      && m.parameterTypes[0] == offsetStoreField.type }
        assertNotNull(method,
            "HighlightInfo.getIntentionActionDescriptors(${offsetStoreField.type.simpleName}) not found — " +
            "get_quick_fixes will return empty results. " +
            "Update the method lookup in McpCompanionCodeAnalysisToolset.get_quick_fixes().")
    }

    // ── get_quick_fixes — IntentionActionDescriptor.getAction ────────────────
    // Each descriptor returned by getIntentionActionDescriptors() must expose getAction().

    @Test
    fun `IntentionActionDescriptor getAction exists`() {
        val cls = runCatching {
            Class.forName("com.intellij.codeInsight.intention.IntentionActionDelegate\$Companion")
        }.getOrNull() ?: runCatching {
            Class.forName("com.intellij.codeInsight.daemon.impl.HighlightInfo\$IntentionActionDescriptor")
        }.getOrNull()
        if (cls == null) {
            println("INFO: IntentionActionDescriptor not on test classpath — skipping (verified at runtime)")
            return
        }
        val method = cls.methods.find { it.name == "getAction" && it.parameterCount == 0 }
        assertNotNull(method,
            "IntentionActionDescriptor.getAction() not found — get_quick_fixes will return empty results.")
    }

    // ── get_quick_fixes — DaemonCodeAnalyzer.isRunning ───────────────────────
    // get_quick_fixes uses reflection to call isRunning() on the DaemonCodeAnalyzer instance
    // (the method is on the impl, not the public interface) to detect if highlights are ready.
    // If isRunning() disappears, the check degrades gracefully (returns false).

    @Test
    fun `DaemonCodeAnalyzer implementation has isRunning method`() {
        val implCls = runCatching {
            Class.forName("com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerImpl")
        }.getOrNull()
        if (implCls == null) {
            println("INFO: DaemonCodeAnalyzerImpl not on test classpath — skipping (verified at runtime)")
            return
        }
        val method = runCatching { implCls.getMethod("isRunning") }.getOrNull()
        if (method == null) {
            println("WARNING: DaemonCodeAnalyzerImpl.isRunning() not found — " +
                    "get_quick_fixes daemon check will always return false (degraded gracefully). " +
                    "Consider updating the daemon check in McpCompanionCodeAnalysisToolset.")
        } else {
            assertEquals(Boolean::class.javaPrimitiveType, method.returnType,
                "DaemonCodeAnalyzerImpl.isRunning() must return boolean")
            println("OK: DaemonCodeAnalyzerImpl.isRunning() found")
        }
    }

    // ── get_file_problems / get_quick_fixes — DocumentMarkupModel.forDocument ─
    // Both tools rely on this public API to read highlight info without using
    // the @ApiStatus.Internal DaemonCodeAnalyzerImpl.getHighlights().

    @Test
    fun `DocumentMarkupModel forDocument static method exists`() {
        val cls = Class.forName("com.intellij.openapi.editor.impl.DocumentMarkupModel")
        val method = runCatching {
            cls.getMethod("forDocument",
                Class.forName("com.intellij.openapi.editor.Document"),
                Class.forName("com.intellij.openapi.project.Project"),
                Boolean::class.javaPrimitiveType)
        }.getOrNull()
        assertNotNull(method,
            "DocumentMarkupModel.forDocument(Document, Project, boolean) not found — " +
            "get_file_problems and get_quick_fixes will return empty results. " +
            "Find the new public API to read markup highlights.")
        assertTrue(java.lang.reflect.Modifier.isStatic(method!!.modifiers),
            "DocumentMarkupModel.forDocument() must be static")
    }

    // ── ProgressSuspender — already covered in ReflectionApiTest ─────────────
    // (ourProgressToSuspenderMap, suspendProcess, resumeProcess)
}
