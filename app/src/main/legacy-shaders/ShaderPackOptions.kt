package dev.vela.core

import android.content.Context
import java.io.File
import java.util.zip.ZipFile

/**
 * Reads a Java shaderpack's own options.
 *
 * OptiFine packs declare their settings in `shaders/shaders.properties`:
 *
 *   screen = SHADOW_QUALITY
 *   screen.SHADOW_QUALITY = SHADOW_SAMPLES
 *   sliders = SHADOW_SAMPLES
 *   screen.SHADOW_QUALITY.columns = 2
 *
 * and each option maps to a `#define` in the pack's source. This reads the names
 * so the in-game panel can show the pack's real settings instead of a fixed pair
 * of sliders. Toggling one stores a value that the renderer injects as a define
 * when it compiles the pack.
 */
object ShaderPackOptions {

    data class Option(
        val name: String,
        val kind: Kind,
        val screen: String?,
        val values: List<String>,
    ) {
        enum class Kind { TOGGLE, SLIDER, LIST }
    }

    /** Parses the option list from a pack zip. Returns empty when unreadable. */
    fun read(pack: File): List<Option> {
        val text = try {
            ZipFile(pack).use { zip ->
                val entry = zip.getEntry("shaders/shaders.properties")
                    ?: zip.getEntry("shaders.properties")
                    ?: return emptyList()
                zip.getInputStream(entry).bufferedReader().readText()
            }
        } catch (t: Throwable) {
            return emptyList()
        }
        return parse(text)
    }

    fun parse(properties: String): List<Option> {
        val sliders = HashSet<String>()
        val profiles = HashSet<String>()
        val screens = LinkedHashMap<String, MutableList<String>>()
        val values = HashMap<String, MutableList<String>>()

        for (raw in properties.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("!")) continue
            val eq = line.indexOf('=')
            if (eq <= 0) continue
            val key = line.substring(0, eq).trim()
            val value = line.substring(eq + 1).trim()

            when {
                key == "sliders" || key == "profile" -> {
                    value.split(Regex("\\s+")).filter { it.isNotEmpty() }.forEach {
                        if (key == "sliders") sliders.add(it) else profiles.add(it)
                    }
                }
                key == "screen" -> {
                    value.split(Regex("\\s+")).filter { it.isNotEmpty() }.forEach {
                        screens.getOrPut(it) { mutableListOf() }
                    }
                }
                key.startsWith("screen.") && key.endsWith(".columns") -> {}
                key.startsWith("screen.") -> {
                    val screen = key.removePrefix("screen.")
                    val list = screens.getOrPut(screen) { mutableListOf() }
                    value.split(Regex("\\s+")).filter { it.isNotEmpty() }.forEach {
                        if (!list.contains(it)) list.add(it)
                    }
                }
                key.endsWith(".comment") || key.endsWith(".visibility") -> {}
                else -> {
                    // A plain option with explicit values: VALUE1 VALUE2 ...
                    val parts = value.split(Regex("\\s+")).filter { it.isNotEmpty() }
                    if (parts.size > 1) values[key] = parts.toMutableList()
                }
            }
        }

        val out = ArrayList<Option>()
        val seen = HashSet<String>()
        for ((screen, items) in screens) {
            for (item in items) {
                if (!seen.add(item)) continue
                val kind = when {
                    values.containsKey(item) -> Option.Kind.LIST
                    sliders.contains(item) || profiles.contains(item) -> Option.Kind.SLIDER
                    else -> Option.Kind.TOGGLE
                }
                out.add(Option(item, kind, screen, values[item] ?: emptyList()))
            }
        }
        return out
    }

    /** Reads the pack the user last imported, or null. */
    fun active(context: Context): File? {
        val path = context.getSharedPreferences("vela_settings", Context.MODE_PRIVATE)
            .getString("active_pack", null) ?: return null
        val file = File(path)
        return if (file.isFile) file else null
    }

    fun currentValue(context: Context, option: String, fallback: String): String {
        return context.getSharedPreferences("vela_settings", Context.MODE_PRIVATE)
            .getString("pack_opt_$option", fallback) ?: fallback
    }

    fun setValue(context: Context, option: String, value: String) {
        context.getSharedPreferences("vela_settings", Context.MODE_PRIVATE)
            .edit().putString("pack_opt_$option", value).apply()
    }
}
