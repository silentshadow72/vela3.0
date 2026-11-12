package dev.vela.app

import java.io.File

/**
 * What a pack actually contains, as opposed to what it claims.
 *
 * This is the honest answer to "does my pack work": it reports only what was
 * found on disk and what the manifest declared. It does not score the pack, guess
 * at compatibility, or say "compatible" about something it has not verified.
 */
data class PackReport(
    val uuid: String,
    val name: String,
    val version: String,
    val moduleTypes: List<String>,
    /** Number of files under textures/, or null when there is no textures/ folder. */
    val textureCount: Int?,
    val fileCount: Int,
    val sizeBytes: Long,
    val newerFormat: Boolean,
    /** Anything worth telling the user, in plain language. */
    val notes: List<String>,
) {
    fun sizeLabel(): String = when {
        sizeBytes >= 1024 * 1024 -> "%.1f MB".format(sizeBytes / 1048576.0)
        sizeBytes >= 1024 -> "${sizeBytes / 1024} KB"
        else -> "$sizeBytes B"
    }
}

object PackIntegrity {

    /**
     * Builds a report for an installed pack directory.
     *
     * A pack with no readable manifest still produces a report — with empty
     * modules and a note saying so — because "we could not read it" is itself a
     * useful answer.
     */
    fun report(packDir: File, manifest: PackManifest?, parseProblem: String? = null): PackReport {
        val files = ArrayList<File>()
        collect(packDir, files)

        val textureFiles = files.filter { file ->
            val relative = file.relativeTo(packDir).path.replace('\\', '/')
            relative.startsWith("textures/")
        }
        val hasTexturesDir = File(packDir, "textures").isDirectory

        val notes = ArrayList<String>()
        if (parseProblem != null) {
            notes.add("This pack's manifest could not be read: $parseProblem")
        }
        if (manifest != null) {
            if (manifest.newerThanSupported) {
                notes.add(
                    "Declares format ${manifest.formatVersion}, newer than this build " +
                        "understands (${PackManifestParser.SUPPORTED_FORMAT_VERSION}). " +
                        "It may still work; this build cannot confirm it."
                )
            }
            if (manifest.modules.isEmpty()) {
                notes.add("The manifest declares no modules.")
            }
            val types = manifest.moduleTypes
            if (types.isNotEmpty() && types.none { it == "resources" || it == "data" }) {
                notes.add("None of its modules are resources or data: ${types.joinToString()}.")
            }
            if (types.any { it == "script" }) {
                notes.add("Contains a script module. Scripts are not executed by this build.")
            }
        }
        if (!hasTexturesDir) {
            notes.add("No textures/ folder.")
        } else if (textureFiles.isEmpty()) {
            notes.add("Has a textures/ folder, but it is empty.")
        }

        return PackReport(
            uuid = packDir.name,
            name = manifest?.name ?: packDir.name,
            version = manifest?.versionLabel() ?: "unknown",
            moduleTypes = manifest?.moduleTypes ?: emptyList(),
            textureCount = if (hasTexturesDir) textureFiles.size else null,
            fileCount = files.size,
            sizeBytes = files.sumOf { it.length() },
            newerFormat = manifest?.newerThanSupported == true,
            notes = notes,
        )
    }

    private fun collect(dir: File, out: MutableList<File>) {
        val children = dir.listFiles() ?: return
        for (child in children) {
            if (child.isDirectory) collect(child, out) else out.add(child)
        }
    }
}
