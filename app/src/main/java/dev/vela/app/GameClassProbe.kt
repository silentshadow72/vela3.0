package dev.vela.app

import android.content.Context
import android.os.Build
import java.io.File

/**
 * Milestone 3, Phase A: prove the context.
 *
 * The previous attempt established two things: the game's Activity class can be
 * obtained on API 29, and forcing its static initialisation kills the process.
 * The last line written was `call: loader is …`, with no exception — so the abort
 * happened in native code during initialisation.
 *
 * Hypothesis: the static initialiser loads the game's native libraries, and the
 * classloader we built has no library search path, so they cannot be found. A
 * DexClassLoader takes that path as its third argument.
 *
 * This probe therefore builds `DexClassLoader(sourceDir, null, nativeLibraryDir,
 * parent)`, loads the class through it without initialising, records the loader
 * and its library path, then forces initialisation with the log flushed
 * immediately before it.
 *
 * It does not instantiate anything. If the process dies, the last flushed stage
 * line names the call it died in.
 */
object GameClassProbe {

    const val GAME_PACKAGE = "com.mojang.minecraftpe"
    const val ACTIVITY_CLASS = "com.mojang.minecraftpe.MainActivity"

    fun run(filesDir: File, context: Context): BoundaryProbe.Outcome {
        BoundaryLog.clear(filesDir)
        BoundaryLog.line(filesDir, "== M3 Phase A: class with a library search path ==")
        BoundaryLog.line(filesDir, "build: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        BoundaryLog.line(filesDir, "package: $GAME_PACKAGE")
        BoundaryLog.line(filesDir, "device: ${Build.MANUFACTURER} ${Build.MODEL}, API ${Build.VERSION.SDK_INT}")

        val info = try {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(GAME_PACKAGE, 0)
        } catch (t: Throwable) {
            BoundaryLog.line(filesDir, "RESULT: FAIL - could not resolve $GAME_PACKAGE")
            BoundaryLog.line(filesDir, "exception: ${t.javaClass.name}: ${t.message}")
            return BoundaryProbe.Outcome(false, BoundaryLog.read(filesDir))
        }

        val appInfo = info.applicationInfo
        val sourceDir = appInfo?.sourceDir
        val nativeLibDir = appInfo?.nativeLibraryDir
        BoundaryLog.line(filesDir, "versionName: ${info.versionName}")
        BoundaryLog.line(filesDir, "sourceDir: $sourceDir")
        BoundaryLog.line(filesDir, "nativeLibraryDir: $nativeLibDir")

        if (sourceDir.isNullOrEmpty()) {
            BoundaryLog.line(filesDir, "RESULT: FAIL - the package has no sourceDir")
            return BoundaryProbe.Outcome(false, BoundaryLog.read(filesDir))
        }
        if (nativeLibDir.isNullOrEmpty()) {
            BoundaryLog.line(
                filesDir,
                "note: nativeLibraryDir is empty, so there is no library search path to give the loader",
            )
        }
        val apk = File(sourceDir)
        BoundaryLog.line(filesDir, "APK readable: ${apk.canRead()}, ${apk.length() / 1048576} MB")
        if (!apk.canRead()) {
            BoundaryLog.line(filesDir, "RESULT: FAIL - the game's APK is not readable by this app")
            return BoundaryProbe.Outcome(false, BoundaryLog.read(filesDir))
        }

        // ---- the loader ---------------------------------------------------
        val parent = context.classLoader.parent
        Reason.stage(
            filesDir,
            question = "can the game's class be loaded with its library search path?",
            attempt = "new DexClassLoader(sourceDir, null, $nativeLibDir, ${parent?.javaClass?.name})",
        )
        BoundaryLog.line(filesDir, "call: new DexClassLoader(sourceDir, null, nativeLibraryDir, parent)")
        BoundaryLog.line(filesDir, "call: parent is ${parent?.javaClass?.name}")

        val loader = try {
            val cls = Class.forName("dalvik.system.DexClassLoader")
            val ctor = cls.getConstructor(
                String::class.java,      // dexPath
                String::class.java,      // optimizedDirectory
                String::class.java,      // librarySearchPath
                ClassLoader::class.java, // parent
            )
            val instance = ctor.newInstance(sourceDir, null, nativeLibDir, parent) as ClassLoader
            BoundaryLog.line(filesDir, "loader built: ${instance.javaClass.name}")
            instance
        } catch (t: Throwable) {
            BoundaryLog.line(filesDir, "could not build the loader: ${t.javaClass.name}: ${t.message}")
            t.stackTrace.take(10).forEach { BoundaryLog.line(filesDir, "  at $it") }
            Reason.result(
                filesDir,
                question = "can the game's class be loaded with its library search path?",
                attempt = "new DexClassLoader(...)",
                observation = "${t.javaClass.name}: ${t.message}",
                conclusion = "no - the loader could not even be constructed",
            )
            BoundaryLog.line(filesDir, "RESULT: FAIL - the loader could not be built")
            return BoundaryProbe.Outcome(false, BoundaryLog.read(filesDir))
        }

        // DexClassLoader has no getter for the library path, so record what was
        // passed in rather than implying the object reported it.
        BoundaryLog.line(filesDir, "library search path given to the loader: $nativeLibDir")
        BoundaryLog.line(filesDir, "loader class: ${loader.javaClass.name}")

        // ---- load without initialising ------------------------------------
        Reason.stage(
            filesDir,
            question = "does the class load without running its static initialiser?",
            attempt = "Class.forName($ACTIVITY_CLASS, initialise = false, dexLoader)",
        )
        BoundaryLog.line(filesDir, "call: Class.forName(initialise = false) via DexClassLoader")

        val clazz = try {
            Class.forName(ACTIVITY_CLASS, false, loader)
        } catch (t: Throwable) {
            BoundaryLog.line(filesDir, "loading threw: ${t.javaClass.name}: ${t.message}")
            var cause = t.cause
            var depth = 0
            while (cause != null && depth < 10) {
                BoundaryLog.line(filesDir, "caused by [$depth]: ${cause.javaClass.name}: ${cause.message}")
                cause = cause.cause
                depth++
            }
            t.stackTrace.take(16).forEach { BoundaryLog.line(filesDir, "  at $it") }
            Reason.result(
                filesDir,
                question = "does the class load without running its static initialiser?",
                attempt = "Class.forName(initialise = false)",
                observation = "${t.javaClass.name}: ${t.message}",
                conclusion = "no - the class could not be loaded",
            )
            BoundaryLog.line(filesDir, "RESULT: FAIL - the class did not load")
            return BoundaryProbe.Outcome(false, BoundaryLog.read(filesDir))
        }

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
        BoundaryLog.line(filesDir, "static initialisers have NOT run yet")
        Reason.result(
            filesDir,
            question = "does the class load without running its static initialiser?",
            attempt = "Class.forName(initialise = false)",
            observation = "loaded ${clazz.name} from ${clazz.classLoader?.javaClass?.name}",
            conclusion = "yes - loading is not what kills the process",
        )

        // ---- force initialisation -----------------------------------------
        Reason.stage(
            filesDir,
            question = "does initialisation survive when the loader has the game's library path?",
            attempt = "Class.forName($ACTIVITY_CLASS, initialise = true, dexLoader)",
        )
        BoundaryLog.line(filesDir, "call: Class.forName(initialise = true) via DexClassLoader")
        BoundaryLog.line(filesDir, "call: library search path is $nativeLibDir")
        // Flushed. If the process dies inside the call, this is the last line.

        return try {
            val initialised = Class.forName(ACTIVITY_CLASS, true, loader)
            BoundaryLog.line(filesDir, "initialisation returned: ${initialised.name}")
            BoundaryLog.line(filesDir, "RESULT: OK - the class initialises with a library search path")
            Reason.result(
                filesDir,
                question = "does initialisation survive when the loader has the game's library path?",
                attempt = "Class.forName(initialise = true)",
                observation = "returned normally",
                conclusion = "yes - the hypothesis was right, the missing search path was the cause",
            )
            BoundaryProbe.Outcome(true, BoundaryLog.read(filesDir))
        } catch (t: Throwable) {
            BoundaryLog.line(filesDir, "initialisation threw: ${t.javaClass.name}")
            BoundaryLog.line(filesDir, "message: ${t.message}")
            var cause = t.cause
            var depth = 0
            while (cause != null && depth < 10) {
                BoundaryLog.line(filesDir, "caused by [$depth]: ${cause.javaClass.name}: ${cause.message}")
                cause = cause.cause
                depth++
            }
            BoundaryLog.line(filesDir, "stack:")
            t.stackTrace.take(20).forEach { BoundaryLog.line(filesDir, "  at $it") }
            Reason.result(
                filesDir,
                question = "does initialisation survive when the loader has the game's library path?",
                attempt = "Class.forName(initialise = true)",
                observation = "${t.javaClass.name}: ${t.message}",
                conclusion = "no - it fails at the Java level; the chain above names where",
            )
            BoundaryLog.line(filesDir, "RESULT: FAIL - initialisation threw at the Java level")
            BoundaryProbe.Outcome(false, BoundaryLog.read(filesDir))
        }
    }
}
