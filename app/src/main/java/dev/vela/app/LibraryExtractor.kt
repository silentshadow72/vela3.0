package dev.vela.app

import java.io.File
import java.util.zip.ZipFile

/**
 * Extracts `lib/<abi>/*.so` from an imported APK.
 *
 * Every library the APK ships for that ABI is taken. A curated list is how a
 * dependency goes missing later and produces a `dlopen` error naming a different
 * file, which is far harder to diagnose than an extraction that simply took
 * everything.
 *
 * The extraction result says what it found, including the ABIs the APK actually
 * carries, so a wrong-ABI APK is reported as such rather than as an empty folder.
 */
data class ExtractionResult(
    val abi: String,
    val written: Int,
    val names: List<String>,
    val availableAbis: List<String>,
    val error: String? = null,
) {
    fun summary(): String = when {
        error != null -> "extraction failed: $error"
        written == 0 && availableAbis.isEmpty() -> "no native libraries in this APK"
        written == 0 -> "no libraries for $abi; the APK carries ${availableAbis.joinToString()}"
        else -> "$written libraries for $abi"
    }
}

object LibraryExtractor {

    /** The ABIs present in an APK, from its `lib/` entries. */
    fun availableAbis(apk: File): List<String> = try {
        ZipFile(apk).use { zip ->
            zip.entries().asSequence()
                .map { it.name }
                .filter { it.startsWith("lib/") && it.endsWith(".so") }
                .map { it.removePrefix("lib/").substringBefore('/') }
                .distinct()
                .sorted()
                .toList()
        }
    } catch (t: Throwable) {
        emptyList()
    }

    /**
     * Extracts every `.so` under `lib/<abi>/` into [targetDir].
     *
     * Files already present with a non-zero length are left alone, so running the
     * check twice does not rewrite the same bytes.
     */
    fun extract(apk: File, abi: String, targetDir: File): ExtractionResult {
        val available = availableAbis(apk)
        if (!apk.isFile || apk.length() == 0L) {
            return ExtractionResult(abi, 0, emptyList(), available, "the APK is missing or empty")
        }
        if (!targetDir.exists() && !targetDir.mkdirs()) {
            return ExtractionResult(abi, 0, emptyList(), available, "could not create $targetDir")
        }

        val written = ArrayList<String>()
        try {
            ZipFile(apk).use { zip ->
                val prefix = "lib/$abi/"
                for (entry in zip.entries()) {
                    val name = entry.name
                    if (!name.startsWith(prefix) || !name.endsWith(".so")) continue
                    val libName = name.substring(prefix.length)
                    if (libName.contains('/')) continue

                    val out = File(targetDir, libName)
                    if (out.isFile && out.length() > 0) {
                        written.add(libName)
                        continue
                    }
                    zip.getInputStream(entry).use { input ->
                        out.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
                    }
                    written.add(libName)
                }
            }
        } catch (t: Throwable) {
            return ExtractionResult(abi, written.size, written.sorted(), available, t.message)
        }
        return ExtractionResult(abi, written.size, written.sorted(), available)
    }

    /**
     * Picks the library to try loading first.
     *
     * `libc++_shared.so` when present: it is the C++ runtime, it has no behaviour
     * of its own beyond initialising itself, and loading it proves the extraction
     * and the linker path without dragging in the game. Otherwise the smallest
     * library, on the same reasoning — smallest surface, least to go wrong.
     */
    fun probeCandidate(libs: List<File>): File? {
        libs.firstOrNull { it.name == "libc++_shared.so" }?.let { return it }
        return libs.filter { it.isFile }.minByOrNull { it.length() }
    }
}
