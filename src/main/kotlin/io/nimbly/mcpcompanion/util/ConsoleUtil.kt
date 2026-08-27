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
 * Reads a terminal-emulation console (JediTerm) from an arbitrary Swing [component] tree.
 *
 * Node/npm and other run configurations render into a `TerminalExecutionConsole`, which contains
 * **no editor at all** — no `ConsoleViewImpl`, no `EditorComponentImpl` — so every editor-based
 * strategy returns null for them regardless of buffer flushing.
 *
 * Duck-typed on the *text buffer* rather than on a widget class: `ShellTerminalWidget` only exists
 * in the Terminal tool window, while run-configuration consoles embed a different widget. Any
 * component exposing `getTerminalTextBuffer()` (directly or through `getTerminalPanel()`) is read.
 *
 * Returns scrollback + screen: the visible screen is ~24 rows, so a startup error that scrolled off
 * would otherwise be lost. History lines are negative indices in JediTerm's buffer.
 */
fun readTerminalWidgetText(component: javax.swing.JComponent): String? = runCatching {
    val buffer = findTerminalTextBuffer(component) ?: return null

    fun call(target: Any, name: String, vararg args: Any?): Any? =
        generateSequence(target.javaClass as Class<*>?) { it.superclass }
            .flatMap { it.methods.asSequence() }
            .find { it.name == name && it.parameterCount == args.size }
            ?.also { it.isAccessible = true }
            ?.invoke(target, *args)

    val height = call(buffer, "getHeight") as? Int ?: 24
    val historyLines = (call(buffer, "getHistoryLinesCount") as? Int ?: 0).coerceIn(0, 5_000)

    val lines = (-historyLines until height).mapNotNull { row ->
        val line = runCatching { call(buffer, "getLine", row) }.getOrNull() ?: return@mapNotNull null
        (runCatching { call(line, "getText") }.getOrNull() as? String)?.trimEnd()
    }
    lines.joinToString("\n").trim().ifEmpty { null }
}.getOrNull()

private fun findTerminalTextBuffer(component: javax.swing.JComponent): Any? {
    fun bufferOf(target: Any): Any? {
        val direct = generateSequence(target.javaClass as Class<*>?) { it.superclass }
            .flatMap { it.methods.asSequence() }
            .find { it.name == "getTerminalTextBuffer" && it.parameterCount == 0 }
        runCatching { direct?.invoke(target) }.getOrNull()?.let { return it }

        val panel = generateSequence(target.javaClass as Class<*>?) { it.superclass }
            .flatMap { it.methods.asSequence() }
            .find { it.name == "getTerminalPanel" && it.parameterCount == 0 }
            ?.let { m -> runCatching { m.invoke(target) }.getOrNull() }
            ?: return null
        return generateSequence(panel.javaClass as Class<*>?) { it.superclass }
            .flatMap { it.methods.asSequence() }
            .find { it.name == "getTerminalTextBuffer" && it.parameterCount == 0 }
            ?.let { m -> runCatching { m.invoke(panel) }.getOrNull() }
    }

    bufferOf(component)?.let { return it }
    return UIUtil.findComponentsOfType(component, javax.swing.JComponent::class.java)
        .firstNotNullOfOrNull { bufferOf(it) }
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
