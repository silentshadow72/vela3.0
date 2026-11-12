package dev.vela.app

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Tests for the integrity report.
 *
 * Every assertion is about something counted on disk or declared in a manifest.
 * Nothing here scores a pack or claims compatibility.
 */
class PackIntegrityTest {

    private lateinit var root: File

    @Before
    fun setUp() {
        root = File(System.getProperty("java.io.tmpdir"), "vela-integrity-${System.nanoTime()}")
        root.mkdirs()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun manifest(name: String = "Ore Borders", format: Int = 2, moduleType: String = "resources"): PackManifest {
        return (PackManifestParser.parse(
            """
            {
              "format_version": $format,
              "header": { "name": "$name", "uuid": "uuid-1", "version": [1, 4, 2] },
              "modules": [ { "type": "$moduleType", "uuid": "uuid-1-m" } ]
            }
            """.trimIndent()
        ) as PackManifestParser.Result.Ok).manifest
    }

    private fun write(path: String, bytes: Int = 10) {
        val file = File(root, path)
        file.parentFile?.mkdirs()
        file.writeBytes(ByteArray(bytes))
    }

    @Test
    fun `textures are counted when the folder exists`() {
        write("textures/blocks/stone.png")
        write("textures/blocks/dirt.png")
        write("textures/items/stick.png")
        write("manifest.json")

        val report = PackIntegrity.report(root, manifest())

        assertEquals(3, report.textureCount)
        assertEquals(4, report.fileCount)
        assertEquals(listOf("resources"), report.moduleTypes)
        assertEquals("1.4.2", report.version)
    }

    @Test
    fun `a pack with no textures folder reports null rather than zero`() {
        write("manifest.json")
        write("models/thing.json")

        val report = PackIntegrity.report(root, manifest())

        assertNull("no textures folder should be null, not 0", report.textureCount)
        assertTrue(report.notes.any { it.contains("No textures/") })
    }

    @Test
    fun `an empty textures folder is distinguished from a missing one`() {
        File(root, "textures").mkdirs()
        write("manifest.json")

        val report = PackIntegrity.report(root, manifest())

        assertEquals(0, report.textureCount)
        assertTrue(report.notes.any { it.contains("empty") })
    }

    @Test
    fun `a newer format is flagged in the report and the notes`() {
        write("textures/a.png")
        val report = PackIntegrity.report(root, manifest(format = 9))

        assertTrue(report.newerFormat)
        assertTrue(report.notes.any { it.contains("newer than this build") })
    }

    @Test
    fun `a script module is called out because scripts are not run`() {
        write("textures/a.png")
        val report = PackIntegrity.report(root, manifest(moduleType = "script"))

        assertTrue(report.notes.any { it.contains("script", ignoreCase = true) })
    }

    @Test
    fun `an unreadable manifest still produces a report`() {
        write("textures/a.png")

        val report = PackIntegrity.report(root, null, parseProblem = "manifest.json is not valid JSON")

        assertEquals(root.name, report.name)
        assertEquals("unknown", report.version)
        assertTrue(report.moduleTypes.isEmpty())
        assertEquals(1, report.textureCount)
        assertTrue(report.notes.any { it.contains("could not be read") })
    }

    @Test
    fun `size is summed across every file`() {
        write("a.bin", 100)
        write("nested/b.bin", 200)
        write("nested/deeper/c.bin", 300)

        val report = PackIntegrity.report(root, manifest())

        assertEquals(600L, report.sizeBytes)
        assertEquals(3, report.fileCount)
    }

    @Test
    fun `an empty pack reports zero files and says so`() {
        val report = PackIntegrity.report(root, manifest())

        assertEquals(0, report.fileCount)
        assertEquals(0L, report.sizeBytes)
        assertTrue(report.notes.any { it.contains("No textures/") })
    }
}
