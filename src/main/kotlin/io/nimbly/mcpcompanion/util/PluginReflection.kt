package io.nimbly.mcpcompanion.util

import com.intellij.openapi.extensions.PluginId

/**
 * Reflective accessors for `com.intellij.ide.plugins.PluginManagerCore` — JetBrains marked its
 * static `getPlugin(PluginId)` method `@ApiStatus.Internal` in IDEA 2026.2 EAP. The Marketplace
 * verifier statically rejects plugins that reference internal methods, so we call them via
 * reflection here. All accessors degrade silently to `null` if either the class or the method
 * disappears, so the plugin keeps loading on future IDE versions.
 *
 * Every method here is covered by [io.nimbly.mcpcompanion.ReflectionApiTest] — if the
 * underlying API moves, the test fails at build time rather than at the user's runtime.
 */
internal object PluginReflection {

    private val pluginManagerCoreCls: Class<*>? by lazy {
        runCatching { Class.forName("com.intellij.ide.plugins.PluginManagerCore") }.getOrNull()
    }

    private val getPluginMethod: java.lang.reflect.Method? by lazy {
        runCatching { pluginManagerCoreCls?.getMethod("getPlugin", PluginId::class.java) }.getOrNull()
    }

    private val getPluginsMethod: java.lang.reflect.Method? by lazy {
        runCatching {
            pluginManagerCoreCls?.methods?.firstOrNull { m ->
                m.name == "getPlugins" && m.parameterCount == 0 && m.returnType.isArray
            }
        }.getOrNull()
    }

    /** Returns the `IdeaPluginDescriptor` for the given plugin id, or `null` if absent/disabled. */
    fun getPlugin(id: PluginId): Any? = runCatching {
        getPluginMethod?.invoke(null, id)
    }.getOrNull()

    /** Returns the `IdeaPluginDescriptor` for the given plugin id string, or `null`. */
    fun getPlugin(idString: String): Any? = getPlugin(PluginId.getId(idString))

    /** Returns the plugin's ClassLoader (null if disabled or absent). */
    fun pluginClassLoader(idString: String): ClassLoader? = runCatching {
        val descriptor = getPlugin(idString) ?: return@runCatching null
        descriptor.javaClass.methods.firstOrNull {
            it.name == "getPluginClassLoader" && it.parameterCount == 0
        }?.invoke(descriptor) as? ClassLoader
    }.getOrNull()

    /** Returns the plugin's reported version, or `null` if not found. */
    fun pluginVersion(idString: String): String? = runCatching {
        val descriptor = getPlugin(idString) ?: return@runCatching null
        descriptor.javaClass.methods.firstOrNull {
            it.name == "getVersion" && it.parameterCount == 0
        }?.invoke(descriptor) as? String
    }.getOrNull()

    /** Reads a String-returning getter (e.g. `getName`) on a descriptor obtained from [getPlugin]. */
    fun pluginStringProperty(descriptor: Any, getterName: String): String? = runCatching {
        descriptor.javaClass.methods.firstOrNull {
            it.name == getterName && it.parameterCount == 0
        }?.invoke(descriptor) as? String
    }.getOrNull()

    /** Returns all installed `IdeaPluginDescriptor` instances (as untyped objects). */
    fun getPlugins(): Array<*> = runCatching {
        getPluginsMethod?.invoke(null) as? Array<*>
    }.getOrNull() ?: emptyArray<Any>()
}

/** Convenience shortcut used by toolsets that just need the plugin ClassLoader. */
internal fun pluginClassLoader(pluginId: String): ClassLoader? = PluginReflection.pluginClassLoader(pluginId)
