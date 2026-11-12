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
        val context = ReasonFiles.app ?: error("application context not available")
        val info = try {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(GAME_PACKAGE, 0)
        } catch (t: Throwable) {
            throw IllegalStateException("$GAME_PACKAGE is not installed", t)
        }

        val sourceDir = info.applicationInfo?.sourceDir
            ?: throw IllegalStateException("$GAME_PACKAGE has no sourceDir")
        val nativeLibDir = info.applicationInfo?.nativeLibraryDir
        val versionName = info.versionName

        filesDir?.let {
            Reason.result(
                it,
                question = "where is the game?",
                attempt = "getPackageInfo($GAME_PACKAGE)",
                observation = "sourceDir=$sourceDir nativeLibraryDir=$nativeLibDir version=$versionName",
                conclusion = "resolved",
            )
        }

        // The library search path is the whole point of this loader: without it the
        // game's static initialiser cannot resolve its own libraries.
        filesDir?.let {
            Reason.stage(
                it,
                question = "can the class be loaded with the game's library path?",
                attempt = "new DexClassLoader(sourceDir, null, $nativeLibDir, ${parent.javaClass.name})",
            )
        }
        val loader = Class.forName("dalvik.system.DexClassLoader")
            .getConstructor(
                String::class.java,
                String::class.java,
                String::class.java,
                ClassLoader::class.java,
            )
            .newInstance(sourceDir, null, nativeLibDir, parent) as ClassLoader

        filesDir?.let {
            Reason.result(
                it,
                question = "can the class be loaded with the game's library path?",
                attempt = "new DexClassLoader(...)",
                observation = "loader built: ${loader.javaClass.name}",
                conclusion = "loader ready, library path = $nativeLibDir",
            )
        }

        // initialise = false. Phase A proved forcing it out of context aborts.
        val clazz = Class.forName(GAME_ACTIVITY, false, loader)

        filesDir?.let {
            Reason.result(
                it,
                question = "does the class load without initialising?",
                attempt = "Class.forName($GAME_ACTIVITY, initialise = false, dexLoader)",
                observation = "loaded ${clazz.name}, superclass ${clazz.superclass?.name}",
                conclusion = "yes - loading is safe; initialisation is left to Android",
            )
        }

        val constructor = clazz.getDeclaredConstructor()
        constructor.isAccessible = true
        val instance = constructor.newInstance()
        return instance as Activity
    }

    companion object {
        private const val TAG = "VelaComponentFactory"
        const val GAME_PACKAGE = "com.mojang.minecraftpe"
        const val GAME_ACTIVITY = "com.mojang.minecraftpe.MainActivity"
    }
}
