package dev.vela.app

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.net.URLClassLoader

/**
 * Milestone 3, Phase A under the new approach: prove the context.
 *
 * The earlier shape loaded the game's libraries by hand. That is the wrong
 * mechanism — a launcher hosts the game's own Activity instead, and lets Android
 * create it. Before any of that can be attempted, one thing has to be true: the
 * game's Activity class must be obtainable from the installed APK and
 * instantiable in this process.
 *
 * This does exactly that and stops. No native code, no hooks, no interception.
 * If this cannot be done, everything after it is pointless, so it is worth
 * knowing first.
 *
 * Two routes are tried, because they fail differently:
 *
 *   1. createPackageContext with CONTEXT_INCLUDE_CODE, which is the sanctioned
 *      way to get another package's code
 *   2. a PathClassLoader built directly on the installed APK's sourceDir
 *
 * Whichever succeeds is recorded; whichever throws records the exact exception.
 */
object GameClassProbe {

    const val GAME_PACKAGE = "com.mojang.minecraftpe"
    const val ACTIVITY_CLASS = "com.mojang.minecraftpe.MainActivity"

    fun run(filesDir: File, context: Context): BoundaryProbe.Outcome {
        BoundaryLog.clear(filesDir)
        BoundaryLog.line(filesDir, "== M3 Phase A: prove the context ==")
        BoundaryLog.line(filesDir, "package: $GAME_PACKAGE")
        BoundaryLog.line(filesDir, "class wanted: $ACTIVITY_CLASS")
        BoundaryLog.line(filesDir, "device: ${Build.MANUFACTURER} ${Build.MODEL}, API ${Build.VERSION.SDK_INT}")

        val info = try {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(GAME_PACKAGE, 0)
        } catch (t: Throwable) {
            BoundaryLog.line(filesDir, "RESULT: FAIL - could not resolve $GAME_PACKAGE")
            BoundaryLog.line(filesDir, "exception: ${t.javaClass.name}: ${t.message}")
            return BoundaryProbe.Outcome(false, BoundaryLog.read(filesDir))
        }

        val sourceDir = info.applicationInfo?.sourceDir
        val nativeLibDir = info.applicationInfo?.nativeLibraryDir
        BoundaryLog.line(filesDir, "versionName: ${info.versionName}")
        BoundaryLog.line(filesDir, "sourceDir: $sourceDir")
        BoundaryLog.line(filesDir, "nativeLibraryDir: $nativeLibDir")

        if (sourceDir.isNullOrEmpty()) {
            BoundaryLog.line(filesDir, "RESULT: FAIL - the package has no sourceDir")
            return BoundaryProbe.Outcome(false, BoundaryLog.read(filesDir))
        }
        val apk = File(sourceDir)
        BoundaryLog.line(filesDir, "APK readable: ${apk.canRead()}, size: ${apk.length() / 1048576} MB")
        if (!apk.canRead()) {
            BoundaryLog.line(filesDir, "RESULT: FAIL - the game's APK is not readable by this app")
            return BoundaryProbe.Outcome(false, BoundaryLog.read(filesDir))
        }

        // ---- route 1: the sanctioned way -----------------------------------
        BoundaryLog.line(filesDir, "")
        BoundaryLog.line(filesDir, "route 1: createPackageContext(INCLUDE_CODE | IGNORE_SECURITY)")
        val packageContext = try {
            context.createPackageContext(
                GAME_PACKAGE,
                Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY,
            )
        } catch (t: Throwable) {
            BoundaryLog.line(filesDir, "route 1: threw ${t.javaClass.name}: ${t.message}")
            null
        }

        if (packageContext != null) {
            BoundaryLog.line(filesDir, "route 1: context obtained")
            val loader = packageContext.classLoader
            BoundaryLog.line(filesDir, "route 1: its classloader is ${loader.javaClass.name}")
            try {
                val clazz = Class.forName(ACTIVITY_CLASS, false, loader)
                BoundaryLog.line(filesDir, "route 1: class found: ${clazz.name}")
                BoundaryLog.line(filesDir, "route 1: loaded by ${clazz.classLoader?.javaClass?.name}")
                return finish(filesDir, clazz)
            } catch (t: Throwable) {
                BoundaryLog.line(filesDir, "route 1: could not load the class: ${t.javaClass.name}: ${t.message}")
            }
        }

        // ---- route 2: build the loader ourselves ---------------------------
        BoundaryLog.line(filesDir, "")
        BoundaryLog.line(filesDir, "route 2: PathClassLoader on the game's sourceDir")
        val parent = context.classLoader.parent
        BoundaryLog.line(filesDir, "route 2: parent classloader ${parent?.javaClass?.name}")

        val loader = try {
            // PathClassLoader by reflection so this compiles the same way whether
            // or not the constructor with an explicit parent is visible.
            val cls = Class.forName("dalvik.system.PathClassLoader")
            val ctor = cls.getConstructor(String::class.java, ClassLoader::class.java)
            val instance = ctor.newInstance(sourceDir, parent) as ClassLoader
            BoundaryLog.line(filesDir, "route 2: loader built: ${instance.javaClass.name}")
            instance
        } catch (t: Throwable) {
            BoundaryLog.line(filesDir, "route 2: could not build the loader: ${t.javaClass.name}: ${t.message}")
            t.stackTrace.take(8).forEach { BoundaryLog.line(filesDir, "  at $it") }
            null
        }

        if (loader == null) {
            BoundaryLog.line(filesDir, "RESULT: FAIL - no way to obtain the game's code")
            return BoundaryProbe.Outcome(false, BoundaryLog.read(filesDir))
        }

        val clazz = try {
            Class.forName(ACTIVITY_CLASS, false, loader)
        } catch (t: Throwable) {
            BoundaryLog.line(filesDir, "route 2: could not load the class: ${t.javaClass.name}: ${t.message}")
            t.stackTrace.take(10).forEach { BoundaryLog.line(filesDir, "  at $it") }
            BoundaryLog.line(filesDir, "RESULT: FAIL - the class could not be loaded by either route")
            return BoundaryProbe.Outcome(false, BoundaryLog.read(filesDir))
        }

        BoundaryLog.line(filesDir, "route 2: class found: ${clazz.name}")
        return finish(filesDir, clazz)
    }

    /** Records what the class is and whether it can be instantiated. */
    private fun finish(filesDir: File, clazz: Class<*>): BoundaryProbe.Outcome {
        BoundaryLog.line(filesDir, "")
        BoundaryLog.line(filesDir, "class: ${clazz.name}")
        BoundaryLog.line(filesDir, "superclass: ${clazz.superclass?.name}")
        BoundaryLog.line(filesDir, "loaded by: ${clazz.classLoader?.javaClass?.name}")
        BoundaryLog.line(filesDir, "is an Activity: ${android.app.Activity::class.java.isAssignableFrom(clazz)}")

        BoundaryLog.line(filesDir, "constructors: " + clazz.declaredConstructors.joinToString { c ->
            c.parameterTypes.joinToString(",", "(", ")") { it.simpleName }
        })

        // Static initialisers have not run yet, because the class was loaded with
        // initialise = false. Instantiating runs them, which is the point: it is
        // the first thing that can fail for a reason that is not about loading.
        BoundaryLog.line(filesDir, "about to instantiate (static initialisers run here)")
        return try {
            val ctor = clazz.getDeclaredConstructor()
            ctor.isAccessible = true
            val instance = ctor.newInstance()
            BoundaryLog.line(filesDir, "instantiated: ${instance.javaClass.name}")
            BoundaryLog.line(filesDir, "RESULT: OK - the game's Activity class is obtainable and instantiable")
            BoundaryProbe.Outcome(true, BoundaryLog.read(filesDir))
        } catch (t: Throwable) {
            BoundaryLog.line(filesDir, "instantiation threw ${t.javaClass.name}: ${t.message}")
            var cause = t.cause
            var depth = 0
            while (cause != null && depth < 6) {
                BoundaryLog.line(filesDir, "caused by: ${cause.javaClass.simpleName}: ${cause.message}")
                cause = cause.cause
                depth++
            }
            t.stackTrace.take(14).forEach { BoundaryLog.line(filesDir, "  at $it") }
            BoundaryLog.line(
                filesDir,
                "RESULT: PARTIAL - the class loads but does not instantiate; see the throw above",
            )
            BoundaryProbe.Outcome(false, BoundaryLog.read(filesDir))
        }
    }
}
