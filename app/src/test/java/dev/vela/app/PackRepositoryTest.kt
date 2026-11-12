package dev.vela.app

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Tests for enable, disable and delete.
 *
 * These exercise the folder moves with real files, because the behaviour that
 * matters is where the bytes end up — not whether a flag was set.
 */
class PackRepositoryTest {

    private lateinit var root: File
    private lateinit var profiles: ProfileRepository
    private lateinit var repo: PackRepository
    private lateinit var profileId: String

    @Before
    fun setUp() {
        root = File(System.getProperty("java.io.tmpdir"), "vela-packrepo-${System.nanoTime()}")
        root.mkdirs()
        profiles = ProfileRepository(root)
        repo = PackRepository(profiles)
        profileId = profiles.create("Main").id
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun manifest(name: String, uuid: String, format: Int = 2): String = """
        {
          "format_version": $format,
          "header": { "name": "$name", "uuid": "$uuid", "version": [1, 0, 0] },
          "modules": [ { "type": "resources", "uuid": "$uuid-1" } ]
        }
    """.trimIndent()

    /** Installs a pack by writing it into packs/<uuid>/ directly. */
    private fun install(uuid: String, name: String, format: Int = 2, payload: String = "data") {
        val dir = File(repo.packsDir(profileId), uuid)
        dir.mkdirs()
        File(dir, "manifest.json").writeText(manifest(name, uuid, format))
        File(dir, "payload.txt").writeText(payload)
    }

    // ---- listing ----------------------------------------------------------

    @Test
    fun `an installed pack appears enabled`() {
        install("uuid-a", "Ore Borders")
        val packs = repo.list(profileId)

        assertEquals(1, packs.size)
        val pack = packs.single()
        assertEquals("Ore Borders", pack.displayName)
        assertEquals("1.0.0", pack.versionLabel)
        assertEquals(listOf("resources"), pack.types)
        assertTrue(pack.enabled)
        assertNull(pack.problem)
    }

    @Test
    fun `a pack with an unreadable manifest is listed with its problem`() {
        val dir = File(repo.packsDir(profileId), "uuid-bad")
        dir.mkdirs()
        File(dir, "manifest.json").writeText("{ not json")

        val pack = repo.list(profileId).single()
        assertNotNull("the pack should still be listed", pack.problem)
        assertNull(pack.manifest)
    }

    @Test
    fun `a newer format is flagged in the list`() {
        install("uuid-new", "From The Future", format = 9)
        assertTrue(repo.list(profileId).single().newerFormat)
    }

    @Test
    fun `enabled packs sort before disabled ones`() {
        install("uuid-a", "Zebra")
        install("uuid-b", "Apple")
        repo.setEnabled(profileId, "uuid-a", enabled = false)

        val packs = repo.list(profileId)
        assertEquals("Apple", packs.first().displayName)
        assertTrue(packs.first().enabled)
        assertFalse(packs.last().enabled)
    }

    // ---- enable and disable -----------------------------------------------

    @Test
    fun `disabling moves the pack and keeps its files`() {
        install("uuid-a", "Ore Borders", payload = "keep me")

        assertTrue(repo.setEnabled(profileId, "uuid-a", enabled = false))

        assertFalse(File(repo.packsDir(profileId), "uuid-a").exists())
        val moved = File(repo.disabledDir(profileId), "uuid-a")
        assertTrue(moved.isDirectory)
        assertEquals("keep me", File(moved, "payload.txt").readText())
    }

    @Test
    fun `enabling moves the pack back`() {
        install("uuid-a", "Ore Borders", payload = "keep me")
        repo.setEnabled(profileId, "uuid-a", enabled = false)

        assertTrue(repo.setEnabled(profileId, "uuid-a", enabled = true))

        val back = File(repo.packsDir(profileId), "uuid-a")
        assertTrue(back.isDirectory)
        assertEquals("keep me", File(back, "payload.txt").readText())
        assertFalse(File(repo.disabledDir(profileId), "uuid-a").exists())
    }

    @Test
    fun `the list reflects the disabled state after the move`() {
        install("uuid-a", "Ore Borders")
        repo.setEnabled(profileId, "uuid-a", enabled = false)

        val pack = repo.list(profileId).single()
        assertFalse(pack.enabled)
        assertEquals("Ore Borders", pack.displayName)
    }

    @Test
    fun `enabling a pack that is not installed is refused`() {
        assertFalse(repo.setEnabled(profileId, "ghost", enabled = true))
    }

    @Test
    fun `disabling twice does not throw and leaves one copy`() {
        install("uuid-a", "Ore Borders")
        assertTrue(repo.setEnabled(profileId, "uuid-a", enabled = false))
        assertFalse("second disable should find nothing to move",
            repo.setEnabled(profileId, "uuid-a", enabled = false))
        assertEquals(1, repo.list(profileId).size)
    }

    // ---- delete -----------------------------------------------------------

    @Test
    fun `delete removes an enabled pack`() {
        install("uuid-a", "Ore Borders")
        assertTrue(repo.delete(profileId, "uuid-a"))
        assertTrue(repo.list(profileId).isEmpty())
        assertFalse(File(repo.packsDir(profileId), "uuid-a").exists())
    }

    @Test
    fun `delete removes a disabled pack`() {
        install("uuid-a", "Ore Borders")
        repo.setEnabled(profileId, "uuid-a", enabled = false)

        assertTrue(repo.delete(profileId, "uuid-a"))
        assertTrue(repo.list(profileId).isEmpty())
        assertFalse(File(repo.disabledDir(profileId), "uuid-a").exists())
    }

    @Test
    fun `delete removes both copies when both exist`() {
        install("uuid-a", "Ore Borders")
        // A leftover copy in the other folder must not survive a delete.
        val stray = File(repo.disabledDir(profileId), "uuid-a")
        stray.mkdirs()
        File(stray, "manifest.json").writeText(manifest("Ore Borders", "uuid-a"))

        assertTrue(repo.delete(profileId, "uuid-a"))
        assertFalse(File(repo.packsDir(profileId), "uuid-a").exists())
        assertFalse(File(repo.disabledDir(profileId), "uuid-a").exists())
    }

    @Test
    fun `delete of an unknown pack reports nothing removed`() {
        assertFalse(repo.delete(profileId, "ghost"))
    }

    // ---- location ---------------------------------------------------------

    @Test
    fun `location follows the pack between folders`() {
        install("uuid-a", "Ore Borders")
        assertEquals(
            File(repo.packsDir(profileId), "uuid-a").canonicalPath,
            repo.location(profileId, "uuid-a")?.canonicalPath,
        )
        repo.setEnabled(profileId, "uuid-a", enabled = false)
        assertEquals(
            File(repo.disabledDir(profileId), "uuid-a").canonicalPath,
            repo.location(profileId, "uuid-a")?.canonicalPath,
        )
        assertNull(repo.location(profileId, "ghost"))
    }

    // ---- import then disable ----------------------------------------------

    @Test
    fun `an imported pack can be disabled and re-enabled`() {
        val packFile = File(root, "ore.mcpack")
        ZipOutputStream(packFile.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(manifest("Ore Borders", "uuid-imported").toByteArray())
            zip.closeEntry()
        }
        val temp = File(root, "temp").also { it.mkdirs() }
        val imported = PackImporter.import(packFile, repo.packsDir(profileId), temp)
        assertTrue(imported is PackImportResult.Imported)

        assertEquals(1, repo.list(profileId).size)
        assertTrue(repo.setEnabled(profileId, "uuid-imported", enabled = false))
        assertFalse(repo.list(profileId).single().enabled)
        assertTrue(repo.setEnabled(profileId, "uuid-imported", enabled = true))
        assertTrue(repo.list(profileId).single().enabled)
    }
}
