package io.nimbly.mcpcompanion.util

import com.intellij.execution.impl.ConsoleViewImpl
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.util.ui.UIUtil

/**
 * Reads text from any JComponent that embeds an EditorComponentImpl (e.g. database Output tab).
 * Uses the same EditorComponentImpl → myEditor reflection strategy as ConsoleViewImpl.readText().
 */
fun readEditorText(component: javax.swing.JComponent): String? = try {
    val compCls = Class.forName("com.intellij.openapi.editor.impl.EditorComponentImpl")
    UIUtil.findComponentsOfType(component, compCls as Class<javax.swing.JComponent>)
        .firstNotNullOfOrNull { comp ->
            val f = generateSequence(comp.javaClass as Class<*>?) { it.superclass }
                .flatMap { it.declaredFields.asSequence() }
                .find { it.name == "myEditor" || it.name == "editor" }
            f?.let { it.isAccessible = true; it.get(comp) as? Editor }
        }
        ?.document?.text?.trim()?.ifEmpty { null }
} catch (_: Exception) { null }

/**
 * Materializes the console editor and drains its deferred output buffer into the document.
 *
 * [ConsoleViewImpl] accumulates process output in a private `myDeferredBuffer` and only moves it
 * into the editor document through `flushDeferredText()`, which its internal flush alarm schedules
 * off the component's visibility lifecycle (`addNotify`). A run configuration hosted in the
 * "Services" / Run-Dashboard tool window whose tab was never displayed therefore has a live process,
 * a real [com.intellij.execution.ui.RunContentDescriptor] — and an **empty document**. Reading the
 * document alone reports `console: null` even though the output exists.
 *
 * Both calls below are plain public API on `ConsoleViewImpl` (neither is `@ApiStatus.Internal`), but:
 *  - they assert the EDT — hence the [ApplicationManager.isDispatchThread] guard, so a background
 *    caller degrades to the document/reflection fallbacks instead of throwing;
 *  - `flushDeferredText()` dereferences `getEditor()` without a null check — hence touching
 *    [ConsoleViewImpl.getComponent] first, which runs `initConsoleEditor()`.
 *
 * Idempotent: once flushed the buffer is empty, so later calls only read the document.
 */
fun ConsoleViewImpl.flushDeferredOutput() {
    if (!ApplicationManager.getApplication().isDispatchThread) return
    runCatching {
        component  // getComponent() → initConsoleEditor(); MUST precede flushDeferredText()
        if (hasDeferredOutput()) flushDeferredText()
    }
}

/**
 * Drains the deferred output of every [ConsoleViewImpl] at or under [component].
 *
 * The root itself is checked explicitly: callers pass an `ExecutionConsole.getComponent()` which
 * often *is* the `ConsoleViewImpl` (it extends `JPanel`), and relying on the search to include its
 * own root would make the fix depend on an implementation detail of [UIUtil.findComponentsOfType].
 */
fun flushDeferredConsoleOutput(component: javax.swing.JComponent) {
    val consoles = LinkedHashSet<ConsoleViewImpl>()
    (component as? ConsoleViewImpl)?.let { consoles.add(it) }
    runCatching { consoles.addAll(UIUtil.findComponentsOfType(component, ConsoleViewImpl::class.java)) }
    consoles.forEach { it.flushDeferredOutput() }
}

/**
 * Reads text from a ConsoleViewImpl even when its editor has not been initialized
 * (i.e. the console tab was never focused). Falls back to reflection on myEditor.
 */
fun ConsoleViewImpl.readText(): String? {
    // 0. Vider le buffer différé — sans ça, une console jamais affichée a un document vide.
    flushDeferredOutput()

    // 1. Essayer l'accesseur public
    this.editor?.document?.text?.trim()?.ifEmpty { null }?.let { return it }

    // 2. Chercher myEditor dans toute la hiérarchie de classes
    val editorByField: Editor? = try {
        generateSequence(this.javaClass as Class<*>?) { it.superclass }
            .flatMap { it.declaredFields.asSequence() }
            .filter { it.name == "myEditor" }
            .firstNotNullOfOrNull { field ->
                field.isAccessible = true
                field.get(this) as? Editor
            }
    } catch (_: Exception) { null }
    editorByField?.document?.text?.trim()?.ifEmpty { null }?.let { return it }

    // 3. Chercher un EditorComponentImpl dans le composant (toujours présent visuellement)
    return try {
        val compCls = Class.forName("com.intellij.openapi.editor.impl.EditorComponentImpl")
        UIUtil.findComponentsOfType(this.component, compCls as Class<javax.swing.JComponent>)
            .firstNotNullOfOrNull { comp ->
                val editorField = generateSequence(comp.javaClass as Class<*>?) { it.superclass }
                    .flatMap { it.declaredFields.asSequence() }
                    .find { it.name == "myEditor" || it.name == "editor" }
                editorField?.let { f -> f.isAccessible = true; f.get(comp) as? Editor }
            }
            ?.document?.text?.trim()?.ifEmpty { null }
    } catch (_: Exception) { null }
}
