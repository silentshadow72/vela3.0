package dev.vela.app

import android.content.Context
import android.net.Uri
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * Imported Minecraft versions.
 *
 * A version is one APK the user picked, copied into app storage, with the package
 * name and version read out of it. Nothing here starts the game; this milestone
 * only records what was imported.
 *
 * Layout: filesDir/versions/<id>/base.apk, plus filesDir/versions/versions.json.
 */
data class ImportedVersion(
    val id: String,
    val label: String,
    val packageName: String,
    val versionName: String,
    val sizeBytes: Long,
    val importedAt: Long,
) {
    fun apk(context: Context): File = File(File(File(context.filesDir, "versions"), id), "base.apk")

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("label", label)
        put("packageName", packageName)
        put("versionName", versionName)
        put("sizeBytes", sizeBytes)
        put("importedAt", importedAt)
    }

    companion object {
        fun fromJson(o: JSONObject) = ImportedVersion(
            id = o.optString("id"),
            label = o.optString("label"),
            packageName = o.optString("packageName"),
            versionName = o.optString("versionName"),
            sizeBytes = o.optLong("sizeBytes"),
            importedAt = o.optLong("importedAt"),
        )
    }
}

object VersionStore {

    private const val TAG = "VelaVersions"
    private const val INDEX = "versions.json"

    private fun dir(context: Context) = File(context.filesDir, "versions")
    private fun index(context: Context) = File(dir(context), INDEX)

    fun all(context: Context): List<ImportedVersion> = try {
        val f = index(context)
        if (!f.isFile) emptyList() else {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let(ImportedVersion::fromJson) }
        }
    } catch (t: Throwable) {
        Log.w(TAG, "could not read the index", t)
        emptyList()
    }

    private fun save(context: Context, list: List<ImportedVersion>) = try {
        dir(context).mkdirs()
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        index(context).writeText(arr.toString(2))
    } catch (t: Throwable) {
        Log.w(TAG, "could not write the index", t)
    }

    fun delete(context: Context, id: String) {
        File(dir(context), id).deleteRecursively()
        save(context, all(context).filterNot { it.id == id })
    }

    /**
     * Copies the picked file in and records what it is.
     *
     * Returns null when the file cannot be copied or is not a readable APK, so
     * the screen can say so instead of listing something broken.
     */
    fun import(context: Context, uri: Uri): ImportedVersion? {
        val id = UUID.randomUUID().toString().take(8)
        val root = File(dir(context), id)
        if (!root.mkdirs()) return null
        val apk = File(root, "base.apk")

        try {
            val stream = context.contentResolver.openInputStream(uri) ?: run {
                root.deleteRecursively()
                return null
            }
            stream.use { input ->
                FileOutputStream(apk).use { output -> input.copyTo(output, 1 shl 20) }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "copy failed", t)
            root.deleteRecursively()
            return null
        }

        if (apk.length() == 0L) {
            root.deleteRecursively()
            return null
        }

        val info = try {
            context.packageManager.getPackageArchiveInfo(apk.absolutePath, 0)
        } catch (t: Throwable) {
            null
        }
        if (info?.packageName == null) {
            root.deleteRecursively()
            return null
        }

        val version = ImportedVersion(
            id = id,
            label = info.packageName,
            packageName = info.packageName,
            versionName = info.versionName ?: "unknown",
            sizeBytes = apk.length(),
            importedAt = System.currentTimeMillis(),
        )
        save(context, all(context) + version)
        return version
    }
}
