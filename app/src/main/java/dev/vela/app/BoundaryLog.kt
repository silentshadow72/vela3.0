package dev.vela.app

import android.util.Log
import java.io.File

/**
 * A log the user can read back.
 *
 * Milestone 3 asks for proof, and proof has to survive the app being closed. Every
 * line is written to a file as well as to logcat, so a check can be run and the
 * result read afterwards without a computer attached.
 */
object BoundaryLog {

    private const val TAG = "VelaBoundary"
    private const val FILE_NAME = "boundary.log"

    fun file(filesDir: File): File = File(filesDir, FILE_NAME)

    fun clear(filesDir: File) {
        try {
            file(filesDir).writeText("")
        } catch (t: Throwable) {
            Log.w(TAG, "could not clear the log", t)
        }
    }

    fun line(filesDir: File, message: String) {
        Log.i(TAG, message)
        try {
            file(filesDir).appendText(message + "\n")
        } catch (t: Throwable) {
            Log.w(TAG, "could not write the log", t)
        }
    }

    fun read(filesDir: File): String = try {
        val f = file(filesDir)
        if (f.isFile) f.readText() else ""
    } catch (t: Throwable) {
        "could not read the log: ${t.message}"
    }
}
