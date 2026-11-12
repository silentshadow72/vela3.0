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
}
