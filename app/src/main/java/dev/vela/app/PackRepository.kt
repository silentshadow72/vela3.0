package dev.vela.app

import java.io.File

/**
 * A pack as it exists in a profile right now.
 *
 * [manifest] is whatever the pack declared. A pack whose manifest cannot be read
 * still appears here, with a null manifest and the reason, because a pack sitting
 * in the folder that silently vanishes from the list is worse than one shown as
 * broken.
 */
data class InstalledPack(
    val uuid: String,
    val directory: File,
    val enabled: Boolean,
    val manifest: PackManifest?,
    val problem: String? = null,
) {
    val displayName: String get() = manifest?.name ?: uuid
    val versionLabel: String get() = manifest?.versionLabel() ?: "unknown"
    val types: List<String> get() = manifest?.moduleTypes ?: emptyList()
    val newerFormat: Boolean get() = manifest?.newerThanSupported == true
}

/**
 * Enabling, disabling and deleting packs inside a profile.
 *
 * Disabling **moves** the pack from `packs/<uuid>/` to `disabled/<uuid>/`. It is
 * never deleted by disabling: a user switching a pack off has not asked to lose
 * it, and re-enabling must restore the same files.
 *
 * Deleting removes both locations, and the UI is expected to confirm first.
 */
class PackRepository(private val profiles: ProfileRepository) {

    fun packsDir(profileId: String): File = profiles.packsDir(profileId)
    fun disabledDir(profileId: String): File = profiles.disabledDir(profileId)

    /** Everything in the profile: enabled first, then disabled, each by name. */
    fun list(profileId: String): List<InstalledPack> {
        val enabled = scan(packsDir(profileId), enabled = true)
        val disabled = scan(disabledDir(profileId), enabled = false)
        return (enabled + disabled).sortedWith(
            compareByDescending<InstalledPack> { it.enabled }.thenBy { it.displayName.lowercase() }
        )
    }

    private fun scan(dir: File, enabled: Boolean): List<InstalledPack> {
        val children = dir.listFiles { f -> f.isDirectory } ?: return emptyList()
        return children.mapNotNull { packDir ->
            val manifestFile = File(packDir, "manifest.json")
            if (!manifestFile.isFile) {
                // Not a pack folder we recognise; do not invent an entry for it.
                return@mapNotNull null
            }
            when (val parsed = PackManifestParser.parse(manifestFile.readText())) {
                is PackManifestParser.Result.Ok ->
                    InstalledPack(packDir.name, packDir, enabled, parsed.manifest)
                is PackManifestParser.Result.Error ->
                    InstalledPack(packDir.name, packDir, enabled, null, parsed.message)
            }
        }
    }

    /**
     * Switches a pack on or off by moving it between the two folders.
     *
     * Returns false when the pack is not where it was expected, so the caller can
     * refresh rather than assume success.
     */
    fun setEnabled(profileId: String, uuid: String, enabled: Boolean): Boolean {
        val from = if (enabled) File(disabledDir(profileId), uuid) else File(packsDir(profileId), uuid)
        val to = if (enabled) File(packsDir(profileId), uuid) else File(disabledDir(profileId), uuid)
        if (!from.isDirectory) return false
        if (to.exists()) to.deleteRecursively()
        to.parentFile?.mkdirs()
        if (from.renameTo(to)) return true
        // Fall back to a copy when rename is refused, then drop the original.
        if (!from.copyRecursively(to, overwrite = true)) return false
        from.deleteRecursively()
        return true
    }

    /** Removes a pack from both folders. */
    fun delete(profileId: String, uuid: String): Boolean {
        var removed = false
        val active = File(packsDir(profileId), uuid)
        if (active.exists()) {
            active.deleteRecursively()
            removed = true
        }
        val disabled = File(disabledDir(profileId), uuid)
        if (disabled.exists()) {
            disabled.deleteRecursively()
            removed = true
        }
        return removed
    }

    /** Where a pack lives now, or null when it is not installed. */
    fun location(profileId: String, uuid: String): File? {
        val active = File(packsDir(profileId), uuid)
        if (active.isDirectory) return active
        val disabled = File(disabledDir(profileId), uuid)
        if (disabled.isDirectory) return disabled
        return null
    }
}
