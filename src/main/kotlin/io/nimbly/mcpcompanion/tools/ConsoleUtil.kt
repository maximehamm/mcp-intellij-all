package io.nimbly.mcpcompanion.tools

import com.intellij.execution.impl.ConsoleViewImpl
import com.intellij.openapi.editor.Editor
import com.intellij.util.ui.UIUtil

/**
 * Reads text from a ConsoleViewImpl even when its editor has not been initialized
 * (i.e. the console tab was never focused). Falls back to reflection on myEditor.
 */
fun ConsoleViewImpl.readText(): String? {
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
