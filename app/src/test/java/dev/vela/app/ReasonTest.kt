package dev.vela.app

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Tests for the reason log's file format and rotation.
 *
 * The format is the contract the viewer and any future reader depend on, and
 * rotation is what stops a long unattended session from filling the disk. Both
 * are testable without a device.
 */
class ReasonTest {

    private lateinit var filesDir: File

    @Before
    fun setUp() {
        filesDir = File(System.getProperty("java.io.tmpdir"), "vela-reason-${System.nanoTime()}")
        filesDir.mkdirs()
        Reason.configure("test-build", 7)
    }

    @After
    fun tearDown() {
        filesDir.deleteRecursively()
    }

    private fun lines(): List<JSONObject> =
        Reason.currentFile(filesDir).readLines().filter { it.isNotBlank() }.map { JSONObject(it) }

    // ---- format -----------------------------------------------------------

    @Test
    fun `a stage line is one JSON object per line with the required fields`() {
        Reason.stage(filesDir, question = "does it record?", attempt = "call stage")

        val entries = lines()
        assertEquals(1, entries.size)
        val entry = entries.single()

        assertEquals("stage", entry.getString("kind"))
        assertEquals("does it record?", entry.getString("question"))
        assertEquals("call stage", entry.getString("attempt"))
        assertEquals("pending", entry.getString("conclusion"))
        assertEquals("test-build", entry.getString("buildId"))
        assertEquals(7, entry.getInt("versionCode"))
        assertNotNull(entry.getString("ts"))
        assertNotNull(entry.getString("thread"))
        assertTrue("observation should be present as null", entry.isNull("observation"))
    }

    @Test
    fun `a result line carries observation and conclusion`() {
        Reason.result(
            filesDir,
            question = "did the load work?",
            attempt = "System.load",
            observation = "UnsatisfiedLinkError: not found",
            conclusion = "the dependency is missing from the namespace",
        )
        val entry = lines().single()
        assertEquals("note", entry.getString("kind"))
        assertEquals("UnsatisfiedLinkError: not found", entry.getString("observation"))
        assertEquals(
            "the dependency is missing from the namespace",
            entry.getString("conclusion"),
        )
    }

    @Test
    fun `a note with no question is still valid JSON`() {
        Reason.note(filesDir, "process started")
        val entry = lines().single()
        assertTrue(entry.isNull("question"))
        assertEquals("process started", entry.getString("attempt"))
    }

    @Test
    fun `a java crash records the cause chain`() {
        val error = IllegalStateException("outer", RuntimeException("inner"))
        Reason.javaCrash(filesDir, error, question = "what killed it")

        val entry = lines().single()
        assertEquals("java", entry.getString("kind"))
        val observation = entry.getString("observation")
        assertTrue("chain should name the outer type: $observation", observation.contains("IllegalStateException"))
        assertTrue("chain should name the cause: $observation", observation.contains("RuntimeException"))
        assertTrue("chain should name the message: $observation", observation.contains("inner"))
    }

    @Test
    fun `entries are appended, never rewritten`() {
        Reason.stage(filesDir, "q1", "a1")
        Reason.stage(filesDir, "q2", "a2")
        Reason.stage(filesDir, "q3", "a3")

        val entries = lines()
        assertEquals(3, entries.size)
        assertEquals("q1", entries[0].getString("question"))
        assertEquals("q3", entries[2].getString("question"))
    }

    @Test
    fun `tail returns the last entries oldest first`() {
        repeat(10) { Reason.stage(filesDir, "q$it", "a$it") }
        val tail = Reason.tail(filesDir, 3)
        assertEquals(3, tail.size)
        assertTrue(tail.last().contains("q9"))
    }

    // ---- rotation ---------------------------------------------------------

    @Test
    fun `rotation moves current to point one`() {
        val directory = Reason.dir(filesDir).also { it.mkdirs() }
        val current = File(directory, "current.log")
        current.writeText("x".repeat(2 * 1024 * 1024))

        Reason.rotateIfNeeded(directory)

        assertTrue("current should be gone after rotation", !current.exists())
        val first = File(directory, "current.log.1")
        assertTrue("current.log.1 should exist", first.isFile)
        assertEquals(2L * 1024 * 1024, first.length())
    }

    @Test
    fun `rotation keeps at most two older files`() {
        val directory = Reason.dir(filesDir).also { it.mkdirs() }
        val current = File(directory, "current.log")

        repeat(4) {
            current.writeText("y".repeat(2 * 1024 * 1024))
            Reason.rotateIfNeeded(directory)
        }

        assertTrue(File(directory, "current.log.1").isFile)
        assertTrue(File(directory, "current.log.2").isFile)
        assertTrue("a third rotated file must not exist", !File(directory, "current.log.3").exists())
    }

    @Test
    fun `a small file is not rotated`() {
        Reason.stage(filesDir, "q", "a")
        val directory = Reason.dir(filesDir)
        Reason.rotateIfNeeded(directory)
        assertTrue(File(directory, "current.log").isFile)
        assertTrue(!File(directory, "current.log.1").exists())
    }

    @Test
    fun `allText includes the build stamp and every file`() {
        Reason.stage(filesDir, "q", "a")
        val text = Reason.allText(filesDir)
        assertTrue(text.contains("test-build"))
        assertTrue(text.contains("(7)"))
        assertTrue(text.contains("current.log"))
    }

    @Test
    fun `clear removes the log`() {
        Reason.stage(filesDir, "q", "a")
        Reason.clear(filesDir)
        assertTrue(Reason.tail(filesDir).isEmpty())
    }
}
