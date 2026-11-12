package dev.vela.app

import android.util.Log
import java.io.File

/**
 * The native side of the reason log: signal handlers that survive a native abort.
 *
 * Installed once, pointing at an already-open file descriptor. The handler writes
 * one line and then lets the process die, so nothing is swallowed — the platform
 * still records the crash.
 */
object ReasonSignal {

    private const val TAG = "VelaReasonSignal"

    @Volatile
    private var installed = false

    private external fun nativeInstall(path: String): Boolean
    private external fun nativeCrash()

    /**
     * Loads the library and installs the handlers.
     *
     * Returns false with a reason when the library is absent or the install
     * failed, rather than pretending the handlers are in place.
     */
    fun install(filesDir: File): Boolean {
        if (installed) return true
        return try {
            System.loadLibrary("reason_signal")
            val directory = Reason.dir(filesDir)
            if (!directory.exists()) directory.mkdirs()
            val target = Reason.currentFile(filesDir)
            val ok = nativeInstall(target.absolutePath)
            installed = ok
            Log.i(TAG, "signal handlers installed: $ok, writing to ${target.absolutePath}")
            ok
        } catch (t: Throwable) {
            Log.w(TAG, "could not install the signal handlers", t)
            false
        }
    }

    fun isInstalled(): Boolean = installed

    /** Deliberately dereferences null. The process is expected to die. */
    fun crashForTest() {
        if (!installed) {
            Log.w(TAG, "crashForTest called but the handlers are not installed")
            return
        }
        nativeCrash()
    }
}
