package dev.vela.app

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Tests for library extraction.
 *
 * The load itself cannot be tested here — it needs the Android linker. What can be
 * tested is that the right bytes come out of the APK for the right ABI, which is
 * the half that runs before any loading happens.
 */
class LibraryExtractorTest {

    private lateinit var root: File
    private lateinit var apk: File

    @Before
    fun setUp() {
        root = File(System.getProperty("java.io.tmpdir"), "vela-libs-${System.nanoTime()}")
        root.mkdirs()
        apk = File(root, "game.apk")
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun makeApk(entries: Map<String, Int>) {
        ZipOutputStream(apk.outputStream()).use { zip ->
            for ((path, size) in entries) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(ByteArray(size))
                zip.closeEntry()
            }
        }
    }

    @Test
    fun `every library for the ABI is extracted`() {
        makeApk(
            mapOf(
                "lib/arm64-v8a/libc++_shared.so" to 100,
                "lib/arm64-v8a/libminecraftpe.so" to 200,
                "lib/arm64-v8a/libfmod.so" to 50,
                "lib/x86_64/libc++_shared.so" to 100,
                "classes.dex" to 10,
            )
        )
        val target = File(root, "libs")

        val result = LibraryExtractor.extract(apk, "arm64-v8a", target)

        assertEquals(3, result.written)
        assertEquals(
            listOf("libc++_shared.so", "libfmod.so", "libminecraftpe.so"),
            result.names,
        )
        assertEquals(100L, File(target, "libc++_shared.so").length())
        assertEquals(200L, File(target, "libminecraftpe.so").length())
    }

    @Test
    fun `the ABIs present in the APK are reported`() {
        makeApk(
            mapOf(
                "lib/arm64-v8a/libminecraftpe.so" to 10,
                "lib/armeabi-v7a/libminecraftpe.so" to 10,
            )
        )
        assertEquals(
            listOf("armeabi-v7a", "arm64-v8a"),
            LibraryExtractor.availableAbis(apk),
        )
    }

    @Test
    fun `a wrong ABI reports which ABIs the APK actually carries`() {
        makeApk(mapOf("lib/armeabi-v7a/libminecraftpe.so" to 10))
        val result = LibraryExtractor.extract(apk, "arm64-v8a", File(root, "libs"))

        assertEquals(0, result.written)
        assertTrue(
            "the message should name the ABIs found: ${result.summary()}",
            result.summary().contains("armeabi-v7a"),
        )
    }

    @Test
    fun `an APK with no native libraries says so`() {
        makeApk(mapOf("classes.dex" to 10))
        val result = LibraryExtractor.extract(apk, "arm64-v8a", File(root, "libs"))

        assertEquals(0, result.written)
        assertTrue(result.summary().contains("no native libraries"))
    }

    @Test
    fun `nested paths under the ABI folder are ignored`() {
        makeApk(
            mapOf(
                "lib/arm64-v8a/libminecraftpe.so" to 10,
                "lib/arm64-v8a/sub/libnested.so" to 10,
            )
        )
        val result = LibraryExtractor.extract(apk, "arm64-v8a", File(root, "libs"))

        assertEquals(listOf("libminecraftpe.so"), result.names)
    }

    @Test
    fun `a missing APK is reported rather than thrown`() {
        val result = LibraryExtractor.extract(File(root, "absent.apk"), "arm64-v8a", File(root, "libs"))
        assertNotNull(result.error)
        assertEquals(0, result.written)
    }

    @Test
    fun `libc++_shared is preferred as the probe candidate`() {
        val dir = File(root, "libs").also { it.mkdirs() }
        val small = File(dir, "liba.so").apply { writeBytes(ByteArray(10)) }
        val cxx = File(dir, "libc++_shared.so").apply { writeBytes(ByteArray(500)) }

        assertEquals(cxx.name, LibraryExtractor.probeCandidate(listOf(small, cxx))?.name)
    }

    @Test
    fun `without libc++_shared the smallest library is chosen`() {
        val dir = File(root, "libs").also { it.mkdirs() }
        val big = File(dir, "libbig.so").apply { writeBytes(ByteArray(900)) }
        val small = File(dir, "libsmall.so").apply { writeBytes(ByteArray(10)) }

        assertEquals(small.name, LibraryExtractor.probeCandidate(listOf(big, small))?.name)
    }

    @Test
    fun `no libraries means no candidate`() {
        assertEquals(null, LibraryExtractor.probeCandidate(emptyList()))
    }
}
