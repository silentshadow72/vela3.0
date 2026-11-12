package dev.vela.app

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Tests for the manifest parser and the importer.
 *
 * The parser tests use manifest text directly. The importer tests build real zip
 * files, so the unzip, the manifest lookup and the duplicate check are exercised
 * as they will run.
 */
class PackImporterTest {

    private lateinit var root: File
    private lateinit var packsDir: File
    private lateinit var tempRoot: File

    @Before
    fun setUp() {
        root = File(System.getProperty("java.io.tmpdir"), "vela-packs-${System.nanoTime()}")
        packsDir = File(root, "packs")
        tempRoot = File(root, "temp")
        packsDir.mkdirs()
        tempRoot.mkdirs()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    // ---- three real manifests ---------------------------------------------

    /** A normal pack: a resource module and a data module. */
    private val validManifest = """
        {
          "format_version": 2,
          "header": {
            "name": "Ore Borders",
            "description": "Outlines every ore block.",
            "uuid": "8f2a1c40-3b7e-4c1a-9d55-1a2b3c4d5e6f",
            "version": [1, 2, 0],
            "min_engine_version": [1, 20, 0]
          },
          "modules": [
            { "type": "resources", "uuid": "11111111-1111-4111-8111-111111111111", "version": [1, 2, 0] },
            { "type": "data", "uuid": "22222222-2222-4222-8222-222222222222", "version": [1, 2, 0] }
          ]
        }
    """.trimIndent()

    /** Declares a header but forgets the modules array entirely. */
    private val missingModulesManifest = """
        {
          "format_version": 2,
          "header": {
            "name": "Half Finished",
            "uuid": "3c9d5e70-6a1b-4c2d-8e3f-4a5b6c7d8e9f",
            "version": [1, 0, 0]
          }
        }
    """.trimIndent()

    /** Declares a format newer than this build understands. */
    private val newerFormatManifest = """
        {
          "format_version": 9,
          "header": {
            "name": "From The Future",
            "uuid": "5e1f7a90-2b3c-4d5e-9f60-7a8b9c0d1e2f",
            "version": "2.0.0"
          },
          "modules": [
            { "type": "resources", "uuid": "33333333-3333-4333-8333-333333333333", "version": [2, 0, 0] }
          ]
        }
    """.trimIndent()

    // ---- parser -----------------------------------------------------------

    @Test
    fun `valid manifest is read completely`() {
        val result = PackManifestParser.parse(validManifest)
        assertTrue(result is PackManifestParser.Result.Ok)
        val manifest = (result as PackManifestParser.Result.Ok).manifest

        assertEquals("Ore Borders", manifest.name)
        assertEquals("8f2a1c40-3b7e-4c1a-9d55-1a2b3c4d5e6f", manifest.uuid)
        assertEquals(listOf(1, 2, 0), manifest.version)
        assertEquals("1.2.0", manifest.versionLabel())
        assertEquals(2, manifest.formatVersion)
        assertEquals(listOf("resources", "data"), manifest.moduleTypes)
        assertFalse(manifest.newerThanSupported)
    }

    @Test
    fun `a manifest with no modules is rejected with a reason`() {
        val result = PackManifestParser.parse(missingModulesManifest)
        assertTrue(result is PackManifestParser.Result.Error)
        val message = (result as PackManifestParser.Result.Error).message
        assertTrue(
            "expected the reason to mention modules, was: $message",
            message.contains("no modules", ignoreCase = true),
        )
    }

    @Test
    fun `a newer format version parses and is flagged, not rejected`() {
        val result = PackManifestParser.parse(newerFormatManifest)
        assertTrue(result is PackManifestParser.Result.Ok)
        val manifest = (result as PackManifestParser.Result.Ok).manifest

        assertEquals("From The Future", manifest.name)
        assertEquals(9, manifest.formatVersion)
        assertTrue("a format 9 pack must be flagged", manifest.newerThanSupported)
        // The string form of version is accepted too.
        assertEquals(listOf(2, 0, 0), manifest.version)
    }

    @Test
    fun `invalid json is rejected with a reason`() {
        val result = PackManifestParser.parse("{\"header\": {")
        assertTrue(result is PackManifestParser.Result.Error)
        assertTrue(
            (result as PackManifestParser.Result.Error).message.contains("not valid JSON"),
        )
    }

    @Test
    fun `a manifest without a header is rejected`() {
        val result = PackManifestParser.parse("""{"format_version":2,"modules":[]}""")
        assertTrue(result is PackManifestParser.Result.Error)
        assertTrue(
            (result as PackManifestParser.Result.Error).message.contains("no header"),
        )
    }

    // ---- importer ---------------------------------------------------------

    private fun makePack(file: File, manifest: String, extraFiles: Map<String, String> = emptyMap()): File {
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(manifest.toByteArray())
            zip.closeEntry()
            for ((path, content) in extraFiles) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        return file
    }

    @Test
    fun `a valid pack is unzipped into packs uuid`() {
        val pack = makePack(
            File(root, "ore.mcpack"),
            validManifest,
            mapOf("textures/blocks/ore_border.png" to "not really a png"),
        )

        val result = PackImporter.import(pack, packsDir, tempRoot)
        assertTrue(result is PackImportResult.Imported)
        val imported = result as PackImportResult.Imported

        assertEquals("Ore Borders", imported.manifest.name)
        val dir = File(packsDir, "8f2a1c40-3b7e-4c1a-9d55-1a2b3c4d5e6f")
        assertTrue("pack directory missing", dir.isDirectory)
        assertTrue("manifest not copied", File(dir, "manifest.json").isFile)
        assertTrue("payload not copied", File(dir, "textures/blocks/ore_border.png").isFile)
    }

    @Test
    fun `a second import of the same uuid is rejected`() {
        val pack = makePack(File(root, "ore.mcpack"), validManifest)
        assertTrue(PackImporter.import(pack, packsDir, tempRoot) is PackImportResult.Imported)

        val again = PackImporter.import(pack, packsDir, tempRoot)
        assertTrue(again is PackImportResult.Rejected)
        assertTrue(
            (again as PackImportResult.Rejected).reason.contains("already installed"),
        )
    }

    @Test
    fun `a zip with no manifest is rejected`() {
        val file = File(root, "notapack.zip")
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("readme.txt"))
            zip.write("hello".toByteArray())
            zip.closeEntry()
        }
        val result = PackImporter.import(file, packsDir, tempRoot)
        assertTrue(result is PackImportResult.Rejected)
        assertTrue(
            (result as PackImportResult.Rejected).reason.contains("no manifest.json"),
        )
    }

    @Test
    fun `a pack nested in a folder is found`() {
        val file = File(root, "nested.mcpack")
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("OreBorders/manifest.json"))
            zip.write(validManifest.toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("OreBorders/pack_icon.png"))
            zip.write(byteArrayOf(1, 2, 3))
            zip.closeEntry()
        }
        val result = PackImporter.import(file, packsDir, tempRoot)
        assertTrue(result is PackImportResult.Imported)
        val dir = File(packsDir, "8f2a1c40-3b7e-4c1a-9d55-1a2b3c4d5e6f")
        assertTrue(File(dir, "pack_icon.png").isFile)
        assertFalse("the wrapper folder should not be kept", File(dir, "OreBorders").exists())
    }

    @Test
    fun `a file that is not a zip is rejected`() {
        val file = File(root, "junk.mcpack")
        file.writeText("this is not a zip at all")
        val result = PackImporter.import(file, packsDir, tempRoot)
        assertTrue(result is PackImportResult.Rejected)
    }

    @Test
    fun `the temp directory is cleaned up after a successful import`() {
        val pack = makePack(File(root, "ore.mcpack"), validManifest)
        PackImporter.import(pack, packsDir, tempRoot)
        val leftovers = tempRoot.listFiles()?.filter { it.isDirectory } ?: emptyList()
        assertTrue("temp import directories were left behind: $leftovers", leftovers.isEmpty())
    }

    @Test
    fun `the temp directory is cleaned up after a rejected import`() {
        val file = File(root, "notapack.zip")
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("readme.txt"))
            zip.write("hello".toByteArray())
            zip.closeEntry()
        }
        PackImporter.import(file, packsDir, tempRoot)
        val leftovers = tempRoot.listFiles()?.filter { it.isDirectory } ?: emptyList()
        assertTrue("temp import directories were left behind: $leftovers", leftovers.isEmpty())
    }
}
