package dev.vela.app

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import java.io.File

/**
 * Phase B: host the game's Activity.
 *
 * Android asks this factory to create an Activity when a component is started.
 * For the component named `com.mojang.minecraftpe.MainActivity` we return the
 * game's own class, loaded from the installed APK through a DexClassLoader that
 * carries the game's nativeLibraryDir as its library search path.
 *
 * The class is loaded with `initialise = false` and **never** forced to
 * initialise. Phase A established that forcing it aborts the process even with a
 * correct search path, so this path does not do it: initialisation happens when
 * Android instantiates and uses the class, which is the only way it can be done
 * in context.
 *
 * Every step is a Reason stage before the call, so a native abort leaves a line
 * with conclusion pending naming the call it died in.
 */
class VelaComponentFactory : androidx.core.app.CoreComponentFactory() {

    override fun instantiateActivity(
        cl: ClassLoader,
        className: String,
        intent: Intent?,
    ): Activity {
        if (className != GAME_ACTIVITY) {
            return super.instantiateActivity(cl, className, intent)
        }

        val filesDir = ReasonFiles.dir
        if (filesDir != null) {
            Reason.stage(
                filesDir,
                question = "will Android accept the game's Activity from our manifest?",
                attempt = "instantiateActivity($className)",
            )
        }

        return try {
            val activity = buildGameActivity(cl, filesDir)
            if (filesDir != null) {
                Reason.result(
                    filesDir,
                    question = "will Android accept the game's Activity from our manifest?",
                    attempt = "instantiateActivity($className)",
                    observation = "instance created: ${activity.javaClass.name}",
                    conclusion = "handing it to Android; onCreate is next, and it is not ours",
                )
                Reason.stage(
                    filesDir,
                    question = "is onCreate entered?",
                    attempt = "return the instance to ActivityThread and let it attach and call onCreate",
                )
            }
            activity
        } catch (t: Throwable) {
            if (filesDir != null) {
                Reason.result(
                    filesDir,
                    question = "will Android accept the game's Activity from our manifest?",
                    attempt = "instantiateActivity($className)",
                    observation = "${t.javaClass.name}: ${t.message}",
                    conclusion = "no - the instance could not be created",
                )
            }
            Log.e(TAG, "could not instantiate the game's Activity", t)
            throw t
        }
    }

    private fun buildGameActivity(parent: ClassLoader, filesDir: File?): Activity {
        val built = GameLoader.build(ReasonFiles.app ?: error("no application context"), filesDir)

        val clazz = built.gameClass ?: throw IllegalStateException(
            built.error ?: "the game's Activity class could not be loaded"
        )

        filesDir?.let {
            Reason.result(
                it,
                question = "is the Activity class available to hand to Android?",
                attempt = "Class.forName via the DexClassLoader",
                observation = "loaded ${clazz.name} (superclass ${clazz.superclass?.name})",
                conclusion = "yes - initialisation is left to Android, never forced",
            )
        }

        val constructor = clazz.getDeclaredConstructor()
        constructor.isAccessible = true

        filesDir?.let {
            Reason.stage(
                it,
                question = "does instantiating the Activity abort the process?",
                attempt = "newInstance() on ${clazz.name}",
            )
        }

        val instance = constructor.newInstance() as Activity

        filesDir?.let {
            Reason.result(
                it,
                question = "does instantiating the Activity abort the process?",
                attempt = "newInstance() on ${clazz.name}",
                observation = "instance created",
                conclusion = "no - instantiation survives; onCreate is the next thing that can fail",
            )
            // Written immediately before we hand control back to Android, which
            // will attach the activity and call onCreate. If nothing follows this
            // line, onCreate either entered and died, or was never reached.
            Reason.stage(
                it,
                question = "is GameActivity.onCreate reached?",
                attempt = "return the instance to ActivityThread; it attaches and calls onCreate",
            )
        }
        return instance
    }

    companion object {
        private const val TAG = "VelaComponentFactory"
        const val GAME_PACKAGE = "com.mojang.minecraftpe"
        const val GAME_ACTIVITY = "com.mojang.minecraftpe.MainActivity"
    }
}
