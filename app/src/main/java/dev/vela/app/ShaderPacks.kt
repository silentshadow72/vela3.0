package dev.vela.app

import android.content.Context
import java.io.File
import java.util.Properties

/**
 * The shader packs folder the mod scans.
 *
 * The path is read from the mod's own configuration rather than hardcoded. The
 * library's strings name `/files/IriOptic` and `/settings.properties`, so that is
 * where the configuration is looked for; the folder it names is used when it says
 * so, and a documented default is used when it does not.
 *
 * Until the mod has run once, no configuration exists. That is recorded rather
 * than hidden, because a missing config and a config that says something
 * unexpected are different problems.
 */
object ShaderPacks {

    // This is the folder the mod itself creates on disk, so it keeps its exact
    // name: it is a functional identifier, not user-facing text. Renaming it
    // would stop us finding the configuration the mod writes.
    private const val CONFIG_DIR = "IriOptic"
    private const val CONFIG_FILE = "settings.properties"
    private const val FOLDER_KEY = "shaderpacks"
    private const val DEFAULT_FOLDER = "shaderpacks"

    data class Location(val folder: File, val source: String)

    /** Where packs go, and why that path was chosen. */
    fun locate(context: Context): Location {
        val configDir = File(context.filesDir, CONFIG_DIR)
        val config = File(configDir, CONFIG_FILE)

        if (config.isFile) {
            val properties = Properties()
            try {
                config.inputStream().use { properties.load(it) }
            } catch (t: Throwable) {
                Reason.note(context.filesDir, "could not read ${config.path}: ${t.message}")
            }
            val configured = properties.getProperty(FOLDER_KEY)?.trim()
            if (!configured.isNullOrEmpty()) {
                val folder = if (configured.startsWith("/")) File(configured)
                else File(configDir, configured)
                Reason.note(
                    context.filesDir,
                    "shaderpacks folder from the mod's settings.properties: ${folder.path}",
                )
                return Location(folder, "settings.properties")
            }
        }

        val fallback = File(configDir, DEFAULT_FOLDER)
        Reason.note(
            context.filesDir,
            "no shaderpacks entry in ${config.path}; using ${fallback.path}",
        )
        return Location(fallback, "default")
    }

    data class ShaderPack(val name: String, val file: File, val isZip: Boolean)

    /** Zips and folders, sorted, ignoring anything hidden. */
    fun list(folder: File): List<ShaderPack> {
        val children = folder.listFiles() ?: return emptyList()
        return children
            .filter { !it.name.startsWith(".") }
            .filter { it.isFile && it.name.endsWith(".zip", ignoreCase = true) || it.isDirectory }
            .map { ShaderPack(it.name, it, it.isFile) }
            .sortedBy { it.name.lowercase() }
    }

    sealed class Result {
        data class Imported(val pack: ShaderPack, val folder: File) : Result()
        data class Rejected(val reason: String) : Result()
    }

    /**
     * Copies a picked `.zip` into the folder the mod scans.
     *
     * The name is taken from the picked file where the content resolver gives one,
     * because the mod identifies packs by folder and file name.
     */
    fun import(context: Context, uri: android.net.Uri, folder: File): Result {
        if (!folder.exists() && !folder.mkdirs()) {
            return Result.Rejected("Could not create ${folder.path}")
        }

        val name = displayName(context, uri) ?: "pack-${System.currentTimeMillis()}.zip"
        if (!name.endsWith(".zip", ignoreCase = true)) {
            return Result.Rejected("Shader packs must be .zip files; this is \"$name\"")
        }

        val target = File(folder, name)
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
            } ?: return Result.Rejected("The file could not be opened")
            if (target.length() == 0L) {
                target.delete()
                return Result.Rejected("The file was empty")
            }
            Reason.note(context.filesDir, "imported shader pack $name into ${folder.path}")
            Result.Imported(ShaderPack(name, target, true), folder)
        } catch (t: Throwable) {
            target.delete()
            Result.Rejected("Could not copy the pack: ${t.message}")
        }
    }

    private fun displayName(context: Context, uri: android.net.Uri): String? {
        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
            } ?: uri.lastPathSegment?.substringAfterLast('/')
        } catch (t: Throwable) {
            null
        }
    }
}
