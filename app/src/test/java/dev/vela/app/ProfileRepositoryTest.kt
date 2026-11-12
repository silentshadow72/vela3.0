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

/**
 * Tests for [ProfileRepository], run on the JVM.
 *
 * Each test gets a fresh temporary directory, so nothing is shared and nothing
 * is left behind.
 */
class ProfileRepositoryTest {

    private lateinit var root: File
    private lateinit var repo: ProfileRepository

    @Before
    fun setUp() {
        root = createTempDir("vela-profiles")
        repo = ProfileRepository(root)
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun createTempDir(prefix: String): File {
        val dir = File(System.getProperty("java.io.tmpdir"), "$prefix-${System.nanoTime()}")
        dir.mkdirs()
        return dir
    }

    // ---- create -----------------------------------------------------------

    @Test
    fun `create makes the folders and settings file`() {
        val profile = repo.create("Survival")

        assertTrue(repo.profileDir(profile.id).isDirectory)
        assertTrue(repo.packsDir(profile.id).isDirectory)
        assertTrue(repo.disabledDir(profile.id).isDirectory)
        assertTrue(repo.worldsDir(profile.id).isDirectory)
        assertTrue(repo.settingsFile(profile.id).isFile)
    }

    @Test
    fun `the first profile becomes active`() {
        val profile = repo.create("First")
        assertEquals(profile.id, repo.active()?.id)
    }

    @Test
    fun `a second profile does not steal active`() {
        val first = repo.create("First")
        repo.create("Second")
        assertEquals(first.id, repo.active()?.id)
    }

    @Test
    fun `an empty name still produces a usable profile`() {
        val profile = repo.create("   ")
        assertEquals("Profile", profile.name)
    }

    // ---- list -------------------------------------------------------------

    @Test
    fun `list returns every profile oldest first`() {
        val a = repo.create("A")
        Thread.sleep(5)
        val b = repo.create("B")
        Thread.sleep(5)
        val c = repo.create("C")

        val names = repo.list().map { it.name }
        assertEquals(listOf(a.name, b.name, c.name), names)
    }

    @Test
    fun `list is empty on a fresh directory`() {
        assertTrue(repo.list().isEmpty())
        assertNull(repo.active())
    }

    // ---- switch -----------------------------------------------------------

    @Test
    fun `switch changes the active profile`() {
        val a = repo.create("A")
        val b = repo.create("B")

        assertEquals(a.id, repo.active()?.id)
        assertTrue(repo.switchTo(b.id))
        assertEquals(b.id, repo.active()?.id)
    }

    @Test
    fun `switch to an unknown id fails and changes nothing`() {
        val a = repo.create("A")
        assertFalse(repo.switchTo("does-not-exist"))
        assertEquals(a.id, repo.active()?.id)
    }

    @Test
    fun `the active choice survives a new repository instance`() {
        repo.create("A")
        val b = repo.create("B")
        repo.switchTo(b.id)

        val reopened = ProfileRepository(root)
        assertEquals(b.id, reopened.active()?.id)
    }

    // ---- delete -----------------------------------------------------------

    @Test
    fun `delete removes the folders and the entry`() {
        val a = repo.create("A")
        assertTrue(repo.delete(a.id))
        assertTrue(repo.list().isEmpty())
        assertFalse(repo.profileDir(a.id).exists())
    }

    @Test
    fun `deleting the active profile promotes the next oldest`() {
        val a = repo.create("A")
        Thread.sleep(5)
        val b = repo.create("B")
        assertEquals(a.id, repo.active()?.id)

        repo.delete(a.id)
        assertEquals(b.id, repo.active()?.id)
    }

    @Test
    fun `deleting the last profile leaves nothing active`() {
        val a = repo.create("A")
        repo.delete(a.id)
        assertNull(repo.active())
    }

    @Test
    fun `delete of an unknown id is refused`() {
        repo.create("A")
        assertFalse(repo.delete("nope"))
    }

    // ---- recovery ---------------------------------------------------------

    @Test
    fun `a corrupt index is rebuilt from the directories`() {
        val a = repo.create("A")
        val b = repo.create("B")

        // Truncated JSON, the shape a killed process leaves behind.
        File(root, "profiles.json").writeText("{\"profiles\": [ {\"id\": \"a\"")

        val recovered = repo.list()
        assertEquals(2, recovered.size)
        assertEquals(setOf(a.id, b.id), recovered.map { it.id }.toSet())
        assertNotNull(repo.active())
    }

    @Test
    fun `recovery keeps the unreadable index instead of discarding it`() {
        repo.create("A")
        File(root, "profiles.json").writeText("not json at all")

        repo.list()

        val bad = File(root, "profiles.json.bad")
        assertTrue(bad.isFile)
        assertTrue(bad.readText().contains("not json at all"))
    }

    @Test
    fun `recovery writes a usable index so the next read is normal`() {
        repo.create("A")
        File(root, "profiles.json").writeText("garbage")

        repo.list()

        val fresh = ProfileRepository(root)
        assertEquals(1, fresh.list().size)
        assertFalse(File(root, "profiles.json.bad").readText().isEmpty())
    }

    @Test
    fun `an index pointing at a missing profile falls back to one that exists`() {
        val a = repo.create("A")
        File(root, "profiles.json").writeText(
            "{\"active\":\"ghost\",\"profiles\":[{\"id\":\"${a.id}\",\"name\":\"A\",\"createdAt\":1}]}"
        )
        assertEquals(a.id, repo.active()?.id)
    }

    @Test
    fun `no temporary file is left behind after a write`() {
        repo.create("A")
        assertFalse(File(root, "profiles.json.tmp").exists())
    }
}
