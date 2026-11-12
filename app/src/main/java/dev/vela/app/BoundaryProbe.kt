package dev.vela.app

import android.os.Build
import java.io.File

/**
 * Phase A: prove the boundary between this app and the game's native code.
 *
 * Two things are being tested, and nothing else:
 *
 *   1. that a library can be extracted from an imported APK into this app's own
 *      storage, and
 *   2. that the system linker will load it from there by absolute path.
 *
 * If the second fails, the failure is the answer, and the exact
 * `UnsatisfiedLinkError` is recorded rather than summarised. This is the step that
 * decides whether anything after it is possible at all.
 */
object BoundaryProbe {

    /** The ABI this device will run. */
    fun deviceAbi(): String = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"

    data class Outcome(
        val ok: Boolean,
        val log: String,
    )

    /**
     * Runs the check. Must be called off the main thread: loading a library can
     * take a moment and is not allowed to stall the UI.
     */
    fun runPhaseA(filesDir: File, apk: File, versionId: String, libsDir: File): Outcome {
        BoundaryLog.clear(filesDir)
        val abi = deviceAbi()

        BoundaryLog.line(filesDir, "== Phase A: extract and load ==")
        BoundaryLog.line(filesDir, "device ABIs: ${Build.SUPPORTED_ABIS.joinToString()}")
        BoundaryLog.line(filesDir, "trying ABI: $abi")
        BoundaryLog.line(filesDir, "APK: ${apk.name} (${apk.length() / 1024} KB)")
        BoundaryLog.line(filesDir, "target: ${libsDir.absolutePath}")

        if (!apk.isFile) {
            BoundaryLog.line(filesDir, "RESULT: FAIL - the APK for this version is missing")
            return Outcome(false, BoundaryLog.read(filesDir))
        }

        val extraction = LibraryExtractor.extract(apk, abi, libsDir)
        BoundaryLog.line(filesDir, "ABIs in this APK: ${extraction.availableAbis.joinToString()}")
        BoundaryLog.line(filesDir, "extraction: ${extraction.summary()}")
        for (name in extraction.names) {
            val f = File(libsDir, name)
            BoundaryLog.line(filesDir, "  $name (${f.length() / 1024} KB)")
        }

        if (extraction.written == 0) {
            BoundaryLog.line(filesDir, "RESULT: FAIL - nothing to load for $abi")
            return Outcome(false, BoundaryLog.read(filesDir))
        }

        val libs = (libsDir.listFiles { f -> f.isFile && f.name.endsWith(".so") } ?: emptyArray()).toList()
        val candidate = LibraryExtractor.probeCandidate(libs)
        if (candidate == null) {
            BoundaryLog.line(filesDir, "RESULT: FAIL - no candidate library found")
            return Outcome(false, BoundaryLog.read(filesDir))
        }

        BoundaryLog.line(filesDir, "loading: ${candidate.name}")
        BoundaryLog.line(filesDir, "path: ${candidate.absolutePath}")

        return try {
            System.load(candidate.absolutePath)
            BoundaryLog.line(filesDir, "RESULT: OK - ${candidate.name} loaded from app storage")
            BoundaryLog.line(filesDir, "version: $versionId, ABI: $abi")
            Outcome(true, BoundaryLog.read(filesDir))
        } catch (e: UnsatisfiedLinkError) {
            // The exact error, not a paraphrase. The message names the library the
            // linker could not find, which is the whole diagnosis.
            BoundaryLog.line(filesDir, "RESULT: FAIL - UnsatisfiedLinkError")
            BoundaryLog.line(filesDir, "ABI tried: $abi")
            BoundaryLog.line(filesDir, "message: ${e.message}")
            var cause = e.cause
            var depth = 0
            while (cause != null && depth < 5) {
                BoundaryLog.line(filesDir, "caused by: ${cause.javaClass.simpleName}: ${cause.message}")
                cause = cause.cause
                depth++
            }
            BoundaryLog.line(filesDir, "stack:")
            e.stackTrace.take(12).forEach { BoundaryLog.line(filesDir, "  at $it") }
            Outcome(false, BoundaryLog.read(filesDir))
        } catch (t: Throwable) {
            BoundaryLog.line(filesDir, "RESULT: FAIL - ${t.javaClass.simpleName}: ${t.message}")
            t.stackTrace.take(12).forEach { BoundaryLog.line(filesDir, "  at $it") }
            Outcome(false, BoundaryLog.read(filesDir))
        }
    }

    /**
     * Phase A against the installed game, with no imported APK involved.
     *
     * Resolves the installed package, takes a library out of its
     * nativeLibraryDir, and loads it by absolute path. This tests the same thing
     * as [runPhaseA] — whether this app may load a foreign library at all — using
     * a directory the platform already manages, so it needs nothing imported.
     *
     * Must be called off the main thread.
     */
    fun runInstalledGameCheck(
        filesDir: File,
        packageManager: android.content.pm.PackageManager,
        packageName: String = "com.mojang.minecraftpe",
    ): Outcome {
        BoundaryLog.clear(filesDir)
        BoundaryLog.line(filesDir, "== Phase A: load a library from the installed game ==")
        BoundaryLog.line(filesDir, "package: $packageName")
        BoundaryLog.line(filesDir, "device ABIs: ${Build.SUPPORTED_ABIS.joinToString()}")

        val info = try {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, 0)
        } catch (t: Throwable) {
            BoundaryLog.line(filesDir, "RESULT: FAIL - could not resolve $packageName")
            BoundaryLog.line(filesDir, "exception: ${t.javaClass.name}: ${t.message}")
            return Outcome(false, BoundaryLog.read(filesDir))
        }

        val appInfo = info.applicationInfo
        if (appInfo == null) {
            BoundaryLog.line(filesDir, "RESULT: FAIL - the package has no applicationInfo")
            return Outcome(false, BoundaryLog.read(filesDir))
        }

        BoundaryLog.line(filesDir, "versionName: ${info.versionName}")
        BoundaryLog.line(filesDir, "sourceDir: ${appInfo.sourceDir}")
        BoundaryLog.line(filesDir, "nativeLibraryDir: ${appInfo.nativeLibraryDir}")

        val libDirPath = appInfo.nativeLibraryDir
        if (libDirPath.isNullOrEmpty()) {
            BoundaryLog.line(
                filesDir,
                "RESULT: FAIL - nativeLibraryDir is empty, so this app keeps its " +
                    "libraries inside the APK rather than extracted",
            )
            return Outcome(false, BoundaryLog.read(filesDir))
        }

        val libDir = File(libDirPath)
        val readable = try {
            libDir.canRead()
        } catch (t: Throwable) {
            BoundaryLog.line(filesDir, "exception checking readability: ${t.javaClass.name}: ${t.message}")
            false
        }
        BoundaryLog.line(filesDir, "directory readable: $readable")

        if (!readable) {
            BoundaryLog.line(filesDir, "RESULT: FAIL - the library directory is not readable")
            try {
                val names = libDir.list()
                BoundaryLog.line(filesDir, "list() returned ${names?.size ?: -1} entries")
            } catch (t: Throwable) {
                BoundaryLog.line(filesDir, "exception: ${t.javaClass.name}: ${t.message}")
            }
            return Outcome(false, BoundaryLog.read(filesDir))
        }

        val libs = (libDir.listFiles { f -> f.isFile && f.name.endsWith(".so") } ?: emptyArray()).toList()
        BoundaryLog.line(filesDir, "libraries in that directory: ${libs.size}")
        for (f in libs.sortedBy { it.name }.take(30)) {
            BoundaryLog.line(filesDir, "  ${f.name} (${f.length() / 1024} KB)")
        }
        if (libs.isEmpty()) {
            BoundaryLog.line(filesDir, "RESULT: FAIL - no .so files in the native library directory")
            return Outcome(false, BoundaryLog.read(filesDir))
        }

        val candidate = LibraryExtractor.probeCandidate(libs)
        if (candidate == null) {
            BoundaryLog.line(filesDir, "RESULT: FAIL - no candidate library chosen")
            return Outcome(false, BoundaryLog.read(filesDir))
        }

        BoundaryLog.line(filesDir, "loading: ${candidate.name}")
        BoundaryLog.line(filesDir, "path: ${candidate.absolutePath}")
        BoundaryLog.line(filesDir, "writable by this app: ${candidate.canWrite()}")

        return try {
            System.load(candidate.absolutePath)
            BoundaryLog.line(filesDir, "RESULT: OK - ${candidate.name} loaded from ${packageName}")
            Outcome(true, BoundaryLog.read(filesDir))
        } catch (e: UnsatisfiedLinkError) {
            BoundaryLog.line(filesDir, "RESULT: FAIL - UnsatisfiedLinkError")
            BoundaryLog.line(filesDir, "ABI tried: ${deviceAbi()}")
            BoundaryLog.line(filesDir, "message: ${e.message}")
            var cause = e.cause
            var depth = 0
            while (cause != null && depth < 5) {
                BoundaryLog.line(filesDir, "caused by: ${cause.javaClass.simpleName}: ${cause.message}")
                cause = cause.cause
                depth++
            }
            BoundaryLog.line(filesDir, "stack:")
            e.stackTrace.take(12).forEach { BoundaryLog.line(filesDir, "  at $it") }
            Outcome(false, BoundaryLog.read(filesDir))
        } catch (t: Throwable) {
            BoundaryLog.line(filesDir, "RESULT: FAIL - ${t.javaClass.simpleName}: ${t.message}")
            t.stackTrace.take(12).forEach { BoundaryLog.line(filesDir, "  at $it") }
            Outcome(false, BoundaryLog.read(filesDir))
        }
    }


    /**
     * Loads the game's libraries in a fixed order, each by absolute path.
     *
     * This is a search-path problem, not a missing-file problem: a dependency the
     * game needs sits in the same directory, but loading the game by absolute path
     * does not put that directory on the linker's search path. Loading each
     * dependency first puts it in the process, where the linker then resolves it
     * by soname.
     *
     * Every step is written and flushed before its load, so if one of them kills
     * the process the log shows exactly which.
     */
    private val LOAD_ORDER = listOf(
        "libc++_shared.so",
        "libpairipcore.so",
        "libfmod.so",
        "libHttpClient.Android.so",
        "libMediaDecoders_Android.so",
        "libconscrypt_jni.so",
        "libminecraftpe.so",
    )

    fun runGameLibraryProbe(
        filesDir: File,
        packageManager: android.content.pm.PackageManager,
        packageName: String = "com.mojang.minecraftpe",
    ): Outcome {
        BoundaryLog.clear(filesDir)
        BoundaryLog.line(filesDir, "== Probe: load the game's libraries in order ==")
        BoundaryLog.line(filesDir, "package: $packageName")
        BoundaryLog.line(filesDir, "device ABIs: ${Build.SUPPORTED_ABIS.joinToString()}")

        val info = try {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, 0)
        } catch (t: Throwable) {
            BoundaryLog.line(filesDir, "RESULT: FAIL - could not resolve $packageName")
            BoundaryLog.line(filesDir, "exception: ${t.javaClass.name}: ${t.message}")
            return Outcome(false, BoundaryLog.read(filesDir))
        }

        val libDirPath = info.applicationInfo?.nativeLibraryDir
        if (libDirPath.isNullOrEmpty()) {
            BoundaryLog.line(filesDir, "RESULT: FAIL - nativeLibraryDir is empty")
            return Outcome(false, BoundaryLog.read(filesDir))
        }
        val libDir = File(libDirPath)
        BoundaryLog.line(filesDir, "nativeLibraryDir: $libDirPath")

        val present = (libDir.listFiles { f -> f.isFile && f.name.endsWith(".so") } ?: emptyArray())
            .associateBy { it.name }
        BoundaryLog.line(filesDir, "libraries present: ${present.size}")
        for (name in present.keys.sorted()) {
            BoundaryLog.line(filesDir, "  $name (${present.getValue(name).length() / 1024} KB)")
        }

        var loadedCount = 0
        val absent = ArrayList<String>()
        val failed = LinkedHashMap<String, String>()

        for ((index, name) in LOAD_ORDER.withIndex()) {
            val step = index + 1
            val file = present[name]
            if (file == null) {
                BoundaryLog.line(filesDir, "step $step: $name - ABSENT from the directory, skipping")
                absent.add(name)
                continue
            }
            // Flushed before the load, so a fatal load leaves this as the last line.
            BoundaryLog.line(filesDir, "step $step: loading $name (${file.length() / 1024} KB)")
            BoundaryLog.line(filesDir, "step $step: path ${file.absolutePath}")
            try {
                System.load(file.absolutePath)
                loadedCount++
                BoundaryLog.line(filesDir, "step $step: OK - $name")
            } catch (e: UnsatisfiedLinkError) {
                val message = e.message ?: ""
                BoundaryLog.line(filesDir, "step $step: FAIL - $name")
                BoundaryLog.line(filesDir, "message: $message")
                logCauses(filesDir, e)
                failed[name] = message
                BoundaryLog.line(filesDir, "step $step: stopping, the rest depend on this")
                break
            } catch (t: Throwable) {
                BoundaryLog.line(filesDir, "step $step: FAIL - ${t.javaClass.simpleName}: ${t.message}")
                failed[name] = t.message ?: t.javaClass.simpleName
                break
            }
        }

        val gameLoaded = loadedCount > 0 && LOAD_ORDER.lastOrNull()
            ?.let { it !in failed && it !in absent } == true

        BoundaryLog.line(filesDir, "----")
        BoundaryLog.line(filesDir, "loaded $loadedCount of ${LOAD_ORDER.size}")
        if (absent.isNotEmpty()) {
            BoundaryLog.line(filesDir, "absent: ${absent.joinToString()}")
        }
        if (failed.isNotEmpty()) {
            for ((lib, reason) in failed) {
                BoundaryLog.line(filesDir, "failed: $lib - $reason")
            }
        }

        return if (gameLoaded) {
            BoundaryLog.line(filesDir, "RESULT: OK - libminecraftpe.so loaded and JNI_OnLoad returned")
            Outcome(true, BoundaryLog.read(filesDir))
        } else {
            BoundaryLog.line(filesDir, "RESULT: FAIL - the game library did not load")
            val missing = failed.keys.firstOrNull() ?: absent.firstOrNull()
            if (missing != null) {
                BoundaryLog.line(filesDir, "next to add or fix: $missing")
            }
            Outcome(false, BoundaryLog.read(filesDir))
        }
    }

    private fun logCauses(filesDir: File, error: Throwable) {
        var cause = error.cause
        var depth = 0
        while (cause != null && depth < 6) {
            BoundaryLog.line(filesDir, "caused by: ${cause.javaClass.simpleName}: ${cause.message}")
            cause = cause.cause
            depth++
        }
    }

}
