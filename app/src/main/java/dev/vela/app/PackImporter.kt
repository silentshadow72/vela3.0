package dev.vela.app

import org.json.JSONObject
import java.io.File
import java.util.zip.ZipInputStream

/**
 * What a pack's manifest says about itself.
 *
 * Only fields that were actually read are here. Nothing is inferred and nothing
 * is filled in with a guess: a pack whose manifest does not say something simply
 * does not have it.
 */
data class PackModule(
    val type: String,
    val uuid: String?,
    val description: String?,
)

data class PackManifest(
    val name: String,
    val uuid: String,
    /** The pack's own version, e.g. [1, 2, 0]. Empty when it declares none. */
    val version: List<Int>,
    /** format_version as declared, or -1 when absent. */
    val formatVersion: Int,
    val modules: List<PackModule>,
) {
    /** True when the pack declares a format this build does not understand. */
    val newerThanSupported: Boolean
        get() = formatVersion > PackManifestParser.SUPPORTED_FORMAT_VERSION

    /** Distinct module types, in the order the pack declared them. */
    val moduleTypes: List<String>
        get() = modules.map { it.type }.distinct()

    fun versionLabel(): String = if (version.isEmpty()) "unversioned" else version.joinToString(".")
}

/**
 * Reads and imports Minecraft packs.
 *
 * The parser is separate from the zip handling on purpose: the parser is where
 * the rules live and it is tested directly against manifest text, without
 * building archives.
 */
object PackManifestParser {

    /**
     * The newest `format_version` this build understands.
     *
     * A pack declaring something newer is not rejected — it may well work — but
     * it is flagged, because the honest answer to "will this work" is "it
     * declares a format newer than this build knows about".
     */
    const val SUPPORTED_FORMAT_VERSION = 2

    sealed class Result {
        data class Ok(val manifest: PackManifest) : Result()
        data class Error(val message: String) : Result()
    }

    fun parse(text: String): Result {
        val json = try {
            JSONObject(text)
        } catch (t: Throwable) {
            return Result.Error("manifest.json is not valid JSON: ${t.message}")
        }

        val header = json.optJSONObject("header")
            ?: return Result.Error("manifest.json has no header, so the pack has no identity.")

        val packName = header.optString("name").ifBlank {
            return Result.Error("manifest.json header has no name.")
        }
        val uuid = header.optString("uuid").ifBlank {
            return Result.Error("manifest.json header has no uuid.")
        }

        val version = readVersion(header.opt("version"))
        val formatVersion = readFormatVersion(json.opt("format_version"))

        val modulesArray = json.optJSONArray("modules")
            ?: return Result.Error("This pack declares no modules, so it does nothing.")
        if (modulesArray.length() == 0) {
            return Result.Error("This pack declares no modules, so it does nothing.")
        }

        val modules = ArrayList<PackModule>(modulesArray.length())
        for (i in 0 until modulesArray.length()) {
            val module = modulesArray.optJSONObject(i) ?: continue
            val type = module.optString("type").ifBlank { "unknown" }
            modules.add(
                PackModule(
                    type = type,
                    uuid = module.optString("uuid").ifBlank { null },
                    description = module.optString("description").ifBlank { null },
                )
            )
        }
        if (modules.isEmpty()) {
            return Result.Error("This pack's modules could not be read.")
        }

        return Result.Ok(
            PackManifest(
                name = packName,
                uuid = uuid,
                version = version,
                formatVersion = formatVersion,
                modules = modules,
            )
        )
    }

    /** `version` is either [1,2,0] or "1.2.0" depending on who wrote the pack. */
    private fun readVersion(value: Any?): List<Int> {
        return when (value) {
            null -> emptyList()
            is org.json.JSONArray -> (0 until value.length()).mapNotNull {
                value.opt(it).toString().toIntOrNull()
            }
            is String -> value.split(".").mapNotNull { it.trim().toIntOrNull() }
            is Number -> listOf(value.toInt())
            else -> emptyList()
        }
    }

    /** `format_version` is an int, or a "1.16.0"-style string in older packs. */
    private fun readFormatVersion(value: Any?): Int {
        return when (value) {
            null -> -1
            is Number -> value.toInt()
            is String -> value.substringBefore('.').trim().toIntOrNull() ?: -1
            else -> -1
        }
    }
}

/** Where an import ended up, or why it did not. */
sealed class PackImportResult {
    data class Imported(val manifest: PackManifest, val directory: File) : PackImportResult()
    data class Rejected(val reason: String) : PackImportResult()
}

/**
 * Imports a `.mcpack` or `.zip` into a profile's `packs/<uuid>/`.
 *
 * Every rejection carries a sentence a person can act on, because "import failed"
 * tells the user nothing about what to do next.
 */
object PackImporter {

    fun import(packFile: File, packsDir: File, tempRoot: File): PackImportResult {
        if (!packFile.isFile || packFile.length() == 0L) {
            return PackImportResult.Rejected("That file is empty or could not be read.")
        }
        val work = File(tempRoot, "import-${System.nanoTime()}")
        if (!work.mkdirs()) {
            return PackImportResult.Rejected("Could not create a working directory for the import.")
        }
        return try {
            val unpacked = unzip(packFile, work)
                ?: return PackImportResult.Rejected("That file is not a readable zip or .mcpack.")

            val manifestFile = findManifest(unpacked)
                ?: return PackImportResult.Rejected(
                    "This file has no manifest.json, so it is not a Minecraft pack."
                )

            when (val parsed = PackManifestParser.parse(manifestFile.readText())) {
                is PackManifestParser.Result.Error ->
                    return PackImportResult.Rejected(parsed.message)

                is PackManifestParser.Result.Ok -> {
                    val manifest = parsed.manifest

                    if (alreadyInstalled(manifest.uuid, packsDir)) {
                        return PackImportResult.Rejected(
                            "A pack with this UUID is already installed: ${manifest.name}."
                        )
                    }

                    packsDir.mkdirs()
                    val target = File(packsDir, manifest.uuid)
                    if (target.exists()) target.deleteRecursively()
                    // Copy the unpacked pack root, not the temp wrapper.
                    if (!manifestFile.parentFile.copyRecursively(target, overwrite = true)) {
                        return PackImportResult.Rejected("Could not copy the pack into the profile.")
                    }
                    PackImportResult.Imported(manifest, target)
                }
            }
        } catch (t: Throwable) {
            PackImportResult.Rejected("The import failed: ${t.message}")
        } finally {
            work.deleteRecursively()
        }
    }

    private fun alreadyInstalled(uuid: String, packsDir: File): Boolean {
        return File(packsDir, uuid).isDirectory
    }

    /** Unzips into [target] and returns the directory the contents landed in. */
    private fun unzip(packFile: File, target: File): File? {
        var entries = 0
        try {
            packFile.inputStream().use { raw ->
                ZipInputStream(raw).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        val out = File(target, entry.name)
                        // Refuse anything that would land outside the target.
                        if (!out.canonicalPath.startsWith(target.canonicalPath)) {
                            return null
                        }
                        if (entry.isDirectory) {
                            out.mkdirs()
                        } else {
                            out.parentFile?.mkdirs()
                            out.outputStream().use { os -> zip.copyTo(os) }
                            entries++
                        }
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                }
            }
        } catch (t: Throwable) {
            return null
        }
        if (entries == 0) return null
        return target
    }

    /**
     * Finds manifest.json at the root, or one level down.
     *
     * Packs are packaged both ways: the manifest at the top, or everything inside
     * a single folder named after the pack.
     */
    private fun findManifest(root: File): File? {
        val direct = File(root, "manifest.json")
        if (direct.isFile) return direct
        val children = root.listFiles() ?: return null
        for (child in children) {
            if (!child.isDirectory) continue
            val nested = File(child, "manifest.json")
            if (nested.isFile) return nested
        }
        return null
    }
}
