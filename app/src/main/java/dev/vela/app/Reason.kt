package dev.vela.app

import android.content.Context
import android.os.Build
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The reason log: a crash and stage recorder that survives a native abort.
 *
 * Written as a lab notebook rather than a console. Every entry answers, where it
 * applies: what was being found out, what was done, what happened, and what it
 * means. A stage line is written *before* the risky call and carries
 * `conclusion: pending` — so if the process dies inside that call, the last line
 * on disk is the question that was being asked. That absence is the result.
 *
 * One JSON object per line, appended and fsync'd immediately. No batching: a
 * batched buffer is exactly what is lost when the process aborts.
 */
object Reason {

    private const val DIR = "reason"
    private const val CURRENT = "current.log"
    private const val MAX_BYTES = 2L * 1024L * 1024L
    private const val ROTATED = 2

    private val stamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US)

    private var buildId: String = "unknown"
    private var versionCode: Int = 0

    /** Set once at startup, so every entry carries the build that produced it. */
    fun configure(build: String, code: Int) {
        buildId = build
        versionCode = code
    }

    fun dir(filesDir: File): File = File(filesDir, DIR)
    fun currentFile(filesDir: File): File = File(dir(filesDir), CURRENT)

    /**
     * Records a stage: the question and the attempt, with the conclusion pending.
     *
     * Must be called before the call it describes. The line is flushed by the time
     * this returns.
     */
    fun stage(filesDir: File, question: String, attempt: String) {
        write(
            filesDir,
            kind = "stage",
            question = question,
            attempt = attempt,
            observation = null,
            conclusion = "pending",
        )
    }

    /** Records what happened and what it means, closing the current question. */
    fun result(
        filesDir: File,
        question: String,
        attempt: String,
        observation: String,
        conclusion: String,
    ) {
        write(filesDir, "note", question, attempt, observation, conclusion)
    }

    /** A free-standing remark with no experiment attached. */
    fun note(filesDir: File, message: String) {
        write(filesDir, "note", null, message, null, null)
    }

    /** Records a Java failure with its full cause chain. */
    fun javaCrash(filesDir: File, throwable: Throwable, question: String? = null) {
        val chain = StringBuilder()
        var t: Throwable? = throwable
        var depth = 0
        while (t != null && depth < 12) {
            if (depth > 0) chain.append(" <- ")
            chain.append(t.javaClass.name).append(": ").append(t.message)
            t = t.cause
            depth++
        }
        write(
            filesDir,
            kind = "java",
            question = question,
            attempt = "app was running",
            observation = chain.toString(),
            conclusion = "the app died in Java; the chain above names where",
            extra = JSONObject().apply {
                put("stack", throwable.stackTrace.take(24).joinToString("\n") { "at $it" })
            },
        )
    }

    /**
     * Appends one entry and flushes it.
     *
     * Rotation happens before the write, so a single line can never be split
     * across files.
     */
    private fun write(
        filesDir: File,
        kind: String,
        question: String?,
        attempt: String?,
        observation: String?,
        conclusion: String?,
        extra: JSONObject? = null,
    ) {
        try {
            val directory = dir(filesDir)
            if (!directory.exists()) directory.mkdirs()
            rotateIfNeeded(directory)

            val entry = JSONObject().apply {
                put("ts", stamp.format(Date()))
                put("buildId", buildId)
                put("versionCode", versionCode)
                put("thread", Thread.currentThread().name)
                put("kind", kind)
                if (question != null) put("question", question) else put("question", JSONObject.NULL)
                if (attempt != null) put("attempt", attempt) else put("attempt", JSONObject.NULL)
                if (observation != null) put("observation", observation) else put("observation", JSONObject.NULL)
                if (conclusion != null) put("conclusion", conclusion) else put("conclusion", JSONObject.NULL)
                extra?.let { put("extra", it) }
            }

            FileOutputStream(currentFile(filesDir), true).use { out ->
                out.write((entry.toString() + "\n").toByteArray())
                out.flush()
                out.fd.sync()
            }
        } catch (t: Throwable) {
            // Never throw from the logger: a failure to log must not become the
            // failure being investigated.
            android.util.Log.w("VelaReason", "could not write the reason log", t)
        }
    }

    /**
     * current.log -> current.log.1 -> current.log.2, dropping the oldest.
     *
     * Called before a write, so the cap is enforced between entries and no entry
     * is ever cut in half.
     */
    fun rotateIfNeeded(directory: File) {
        val current = File(directory, CURRENT)
        if (!current.isFile || current.length() < MAX_BYTES) return
        for (i in ROTATED downTo 2) {
            val from = File(directory, "$CURRENT.${i - 1}")
            val to = File(directory, "$CURRENT.$i")
            if (to.exists()) to.delete()
            if (from.exists()) from.renameTo(to)
        }
        val first = File(directory, "$CURRENT.1")
        if (first.exists()) first.delete()
        current.renameTo(first)
    }

    /**
     * Marks the point where one build's entries end and another's begin.
     *
     * The log persists across installs, so without this an entry from an older
     * build sits beside a newer one and the file looks like it is lying about
     * which build produced it. This makes the boundary explicit instead of
     * silently mixing them.
     */
    fun noteBuildTransition(filesDir: File) {
        val previous = lastBuildId(filesDir)
        if (previous == null || previous == buildId) return
        write(
            filesDir,
            kind = "note",
            question = "which build wrote these entries?",
            attempt = "compare the last entry's buildId with this build",
            observation = "last entry was $previous, this process is $buildId",
            conclusion = "entries above $previous are from that build; everything below is $buildId",
        )
    }

    /** The buildId of the most recent entry, or null when there is none. */
    fun lastBuildId(filesDir: File): String? {
        return try {
            val f = currentFile(filesDir)
            if (!f.isFile) return null
            val lines = f.readLines()
            val last = lines.lastOrNull { it.isNotBlank() } ?: return null
            JSONObject(last).optString("buildId").ifEmpty { null }
        } catch (t: Throwable) {
            null
        }
    }

    /** The build stamp this process is running, for display and for tests. */
    fun currentBuildId(): String = buildId

    /** The last [lines] entries from current.log, oldest first. */
    fun tail(filesDir: File, lines: Int = 200): List<String> {
        return try {
            val f = currentFile(filesDir)
            if (!f.isFile) emptyList() else f.readLines().takeLast(lines)
        } catch (t: Throwable) {
            emptyList()
        }
    }

    /** Every file, newest first, for sharing. */
    fun allText(filesDir: File): String {
        val directory = dir(filesDir)
        val files = (0..ROTATED).map { i ->
            File(directory, if (i == 0) CURRENT else "$CURRENT.$i")
        }
        val builder = StringBuilder()
        builder.append("build ").append(buildId).append(" (").append(versionCode).append(")\n")
        builder.append("device ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
        builder.append(", API ").append(Build.VERSION.SDK_INT).append("\n\n")
        for (file in files) {
            if (!file.isFile) continue
            builder.append("--- ").append(file.name).append(" ---\n")
            builder.append(file.readText()).append("\n")
        }
        return builder.toString()
    }

    fun clear(filesDir: File) {
        val directory = dir(filesDir)
        for (i in 0..ROTATED) {
            File(directory, if (i == 0) CURRENT else "$CURRENT.$i").delete()
        }
    }
}
