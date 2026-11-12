package dev.vela.app

import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * A profile: one self-contained set of pack, world and setting folders.
 *
 * Exactly one profile is active at a time. The active id lives in the index, not
 * in each profile, so there is one place that can be wrong rather than several.
 */
data class Profile(
    val id: String,
    val name: String,
    val createdAt: Long,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("createdAt", createdAt)
    }

    companion object {
        fun fromJson(o: JSONObject) = Profile(
            id = o.optString("id"),
            name = o.optString("name", o.optString("id")),
            createdAt = o.optLong("createdAt"),
        )
    }
}

/**
 * Profiles on disk.
 *
 * Layout under [root]:
 *
 *   profiles.json          the index: the profile list and which one is active
 *   <id>/packs/            installed packs
 *   <id>/disabled/         packs switched off, kept rather than deleted
 *   <id>/worlds/           worlds
 *   <id>/settings.json     per-profile settings
 *
 * The index is written by writing a temporary file and renaming it over the old
 * one, so a process killed mid-write cannot leave a half-written index. If the
 * index is nevertheless unreadable — an older build, a truncated file, a bad
 * edit — [list] rebuilds it from the directories that are actually on disk
 * instead of throwing.
 *
 * Deliberately free of Android imports: this is the part worth testing, and it
 * is tested on the JVM.
 */
class ProfileRepository(private val root: File) {

    private val indexFile: File get() = File(root, "profiles.json")
    private val tempFile: File get() = File(root, "profiles.json.tmp")
    private val badFile: File get() = File(root, "profiles.json.bad")

    /** A profile and the folders it owns. */
    fun profileDir(id: String): File = File(root, id)
    fun packsDir(id: String): File = File(profileDir(id), "packs")
    fun disabledDir(id: String): File = File(profileDir(id), "disabled")
    fun worldsDir(id: String): File = File(profileDir(id), "worlds")
    fun settingsFile(id: String): File = File(profileDir(id), "settings.json")

    /** Every profile, oldest first. Rebuilds the index when it cannot be read. */
    fun list(): List<Profile> {
        val index = readIndex()
        if (index != null) return index.profiles.sortedBy { it.createdAt }
        return recover()
    }

    /** The active profile, or null when there are none. */
    fun active(): Profile? {
        val index = readIndex() ?: recover()
        val id = index.activeId
        val all = list()
        return all.firstOrNull { it.id == id } ?: all.firstOrNull()
    }

    /**
     * Creates a profile with its folders and settings file, and makes it active
     * when it is the first one.
     */
    fun create(name: String): Profile {
        root.mkdirs()
        val trimmed = name.trim().ifEmpty { "Profile" }
        val profile = Profile(
            id = UUID.randomUUID().toString().take(8),
            name = trimmed,
            createdAt = System.currentTimeMillis(),
        )
        profileDir(profile.id).mkdirs()
        packsDir(profile.id).mkdirs()
        disabledDir(profile.id).mkdirs()
        worldsDir(profile.id).mkdirs()
        settingsFile(profile.id).writeText("{\n  \"renderScale\": \"auto\"\n}\n")

        val current = readIndex() ?: Index(list(), null)
        val updated = Index(current.profiles + profile, current.activeId ?: profile.id)
        writeIndex(updated)
        return profile
    }

    /** Makes [id] the active profile. Returns false when it does not exist. */
    fun switchTo(id: String): Boolean {
        val index = readIndex() ?: recover()
        if (index.profiles.none { it.id == id }) return false
        writeIndex(Index(index.profiles, id))
        return true
    }

    /**
     * Deletes a profile and everything in it.
     *
     * When the active profile is removed, the next oldest becomes active; when
     * none remain, nothing is active.
     */
    fun delete(id: String): Boolean {
        val index = readIndex() ?: recover()
        if (index.profiles.none { it.id == id }) return false
        profileDir(id).deleteRecursively()
        val remaining = index.profiles.filterNot { it.id == id }
        val nextActive = when {
            index.activeId != id -> index.activeId
            else -> remaining.minByOrNull { it.createdAt }?.id
        }
        writeIndex(Index(remaining, nextActive))
        return true
    }

    // ---- index ------------------------------------------------------------

    private data class Index(val profiles: List<Profile>, val activeId: String?)

    private fun readIndex(): Index? {
        if (!indexFile.isFile) return null
        return try {
            val json = JSONObject(indexFile.readText())
            val array = json.optJSONArray("profiles") ?: return null
            val profiles = (0 until array.length()).mapNotNull { i ->
                array.optJSONObject(i)?.let { Profile.fromJson(it) }
            }
            Index(profiles, json.optString("active").ifEmpty { null })
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * Rebuilds the index from the directories on disk.
     *
     * The unreadable index is kept as `profiles.json.bad` rather than deleted:
     * it is the only record of what the user had, and it costs one file to keep.
     */
    private fun recover(): Index {
        if (indexFile.isFile) {
            try {
                indexFile.copyTo(badFile, overwrite = true)
            } catch (_: Throwable) {
            }
        }
        val dirs = root.listFiles { f -> f.isDirectory && File(f, "packs").isDirectory }
            ?.sortedBy { it.lastModified() }
            ?: emptyList()
        val profiles = dirs.map { dir ->
            Profile(
                id = dir.name,
                name = dir.name,
                createdAt = dir.lastModified(),
            )
        }
        val rebuilt = Index(profiles, profiles.firstOrNull()?.id)
        writeIndex(rebuilt)
        return rebuilt
    }

    private fun writeIndex(index: Index) {
        root.mkdirs()
        val json = JSONObject().apply {
            put("active", index.activeId ?: JSONObject.NULL)
            val array = org.json.JSONArray()
            index.profiles.forEach { array.put(it.toJson()) }
            put("profiles", array)
        }
        tempFile.writeText(json.toString(2))
        // Rename is atomic within a filesystem, so a reader sees the old file or
        // the new one, never a partial write.
        if (!tempFile.renameTo(indexFile)) {
            indexFile.delete()
            tempFile.renameTo(indexFile)
        }
    }
}
