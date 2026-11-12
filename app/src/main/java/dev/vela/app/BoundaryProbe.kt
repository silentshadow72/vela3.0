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

}
