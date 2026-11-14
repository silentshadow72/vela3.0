package dev.vela.app

import android.app.Application
import android.util.Log

/**
 * Installs the reason recorder before anything else can fail.
 *
 * Order matters: the build stamp is configured first so every entry carries it,
 * then the native signal handlers, then the Java crash handler. If the handlers
 * could not be installed, that is recorded as a note — a recorder that silently
 * failed to install is worse than none, because its absence looks like calm.
 */
class VelaApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        ReasonFiles.set(this)
        Reason.configure(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)

        val filesDir = filesDir
        Reason.noteBuildTransition(filesDir)
        Reason.note(filesDir, "process started, pid ${android.os.Process.myPid()}")

        val signalsInstalled = ReasonSignal.install(filesDir)
        if (!signalsInstalled) {
            Reason.result(
                filesDir,
                question = "will native crashes be recorded?",
                attempt = "install signal handlers for SIGSEGV/SIGABRT/SIGILL/SIGBUS/SIGFPE",
                observation = "the handler library could not be loaded or installed",
                conclusion = "no — a native abort will not be recorded in this build",
            )
        } else {
            Reason.result(
                filesDir,
                question = "will native crashes be recorded?",
                attempt = "install signal handlers for SIGSEGV/SIGABRT/SIGILL/SIGBUS/SIGFPE",
                observation = "handlers installed",
                conclusion = "yes — a native abort writes one line and then re-raises",
            )
        }

        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                Reason.javaCrash(
                    filesDir,
                    throwable,
                    question = "what killed the process on ${thread.name}",
                )
            } catch (t: Throwable) {
                Log.w("VelaReason", "could not record the Java crash", t)
            }
            // Delegate, so the system still reports it.
            previous?.uncaughtException(thread, throwable)
        }
        val menuReady = ModMenuBridge.load()
        Reason.result(
            filesDir,
            question = "is the mod menu ABI available?",
            attempt = "load vela_modmenu and call into pl::modmenu",
            observation = if (menuReady) "loaded" else "not loaded",
            conclusion = if (menuReady)
                "yes - the ball can open the menu the mod registers into"
            else "no - the ball will report 'shader menu not ready' rather than do nothing",
        )

        Reason.note(filesDir, "java crash handler installed")
    }
}
