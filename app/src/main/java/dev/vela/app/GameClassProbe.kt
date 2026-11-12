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
        BoundaryLog.line(filesDir, "build: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
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
            // One line per call, flushed, so the last surviving line names the
            // exact call that died rather than the block it was in.
            BoundaryLog.line(filesDir, "call: packageContext.classLoader")
            val loader = packageContext.classLoader
            BoundaryLog.line(filesDir, "route 1: its classloader is ${loader.javaClass.name}")
            BoundaryLog.line(filesDir, "call: Class.forName(initialise = false) via route 1 loader")
            try {
                val clazz = Class.forName(ACTIVITY_CLASS, false, loader)
                BoundaryLog.line(filesDir, "route 1: class found: ${clazz.name}")
                BoundaryLog.line(filesDir, "route 1: loaded by ${clazz.classLoader?.javaClass?.name}")
                return finish(filesDir, clazz, loader)
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

        BoundaryLog.line(filesDir, "call: Class.forName(initialise = false) via route 2 loader")
        val clazz = try {
            Class.forName(ACTIVITY_CLASS, false, loader)
        } catch (t: Throwable) {
            BoundaryLog.line(filesDir, "route 2: could not load the class: ${t.javaClass.name}: ${t.message}")
            t.stackTrace.take(10).forEach { BoundaryLog.line(filesDir, "  at $it") }
            BoundaryLog.line(filesDir, "RESULT: FAIL - the class could not be loaded by either route")
            return BoundaryProbe.Outcome(false, BoundaryLog.read(filesDir))
        }

        BoundaryLog.line(filesDir, "route 2: class found: ${clazz.name}")
        return finish(filesDir, clazz, loader)
    }

    /**
     * Records what the class is, then forces initialisation and nothing more.
     *
     * Instantiating an Activity was the previous step and it killed the process,
     * so this separates the two possibilities: a Java-level failure, which can be
     * caught and printed with its cause chain, or a native abort, which cannot be
     * caught at all. If the process dies here with no exception logged, the last
     * flushed line says which stage it died in and the answer is native.
     */
    private fun finish(filesDir: File, clazz: Class<*>, loader: ClassLoader): BoundaryProbe.Outcome {
        BoundaryLog.line(filesDir, "")
        BoundaryLog.line(filesDir, "class: ${clazz.name}")
        BoundaryLog.line(filesDir, "superclass: ${clazz.superclass?.name}")
        BoundaryLog.line(filesDir, "loaded by: ${clazz.classLoader?.javaClass?.name}")
        BoundaryLog.line(filesDir, "is an Activity: ${android.app.Activity::class.java.isAssignableFrom(clazz)}")
        BoundaryLog.line(
            filesDir,
            "constructors: " + clazz.declaredConstructors.joinToString { c ->
                c.parameterTypes.joinToString(",", "(", ")") { it.simpleName }
            },
        )
        BoundaryLog.line(filesDir, "static initialisers have NOT run yet (loaded with initialise = false)")

        // Flushed before the call, so a native abort leaves this as the last line.
        BoundaryLog.line(filesDir, "call: Class.forName(initialise = true)")
        BoundaryLog.line(filesDir, "call: loader is ${loader.javaClass.name}")

        return try {
            val initialised = Class.forName(ACTIVITY_CLASS, true, loader)
            BoundaryLog.line(filesDir, "initialisation returned: ${initialised.name}")
            BoundaryLog.line(filesDir, "RESULT: OK - the class loads and initialises")
            BoundaryProbe.Outcome(true, BoundaryLog.read(filesDir))
        } catch (t: Throwable) {
            BoundaryLog.line(filesDir, "initialisation threw: ${t.javaClass.name}")
            BoundaryLog.line(filesDir, "message: ${t.message}")
            var cause = t.cause
            var depth = 0
            while (cause != null && depth < 10) {
                BoundaryLog.line(
                    filesDir,
                    "caused by [$depth]: ${cause.javaClass.name}: ${cause.message}",
                )
                cause = cause.cause
                depth++
            }
            BoundaryLog.line(filesDir, "stack:")
            t.stackTrace.take(20).forEach { BoundaryLog.line(filesDir, "  at $it") }
            BoundaryLog.line(
                filesDir,
                "RESULT: FAIL - initialisation threw at the Java level, see the chain above",
            )
            BoundaryProbe.Outcome(false, BoundaryLog.read(filesDir))
        }
    }
}
