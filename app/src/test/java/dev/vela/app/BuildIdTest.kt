package dev.vela.app

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards the build stamp.
 *
 * The app once reported 0.12.1 while the log said 0.11.0. The stamp is derived
 * from BuildConfig at runtime, so the fix is to keep it that way: this test fails
 * if the version string is ever written into the source as a literal, because
 * that is how a hardcoded stamp starts.
 */
class BuildIdTest {

    /** Unit tests run with the module as the working directory. */
    private fun sourceRoot(): File {
        val roots = listOf(File("src/main/java"), File("app/src/main/java"))
        for (candidate in roots) {
            if (candidate.isDirectory) return candidate
        }
        throw AssertionError("could not find the source root from ${File(".").absolutePath}")
    }

    @Test
    fun `the version string appears in no source file`() {
        val version = BuildConfig.VERSION_NAME
        assertTrue("BuildConfig.VERSION_NAME is empty", version.isNotBlank())

        // Unit tests run with the module as the working directory.
        val sourceRoot = sourceRoot()

        val offenders = sourceRoot.walkTopDown()
            .filter { it.isFile && (it.name.endsWith(".kt") || it.name.endsWith(".java")) }
            .filter { it.readText().contains("\"$version\"") }
            .map { it.path }
            .toList()

        assertTrue(
            "the version string \"$version\" is hardcoded in: $offenders. " +
                "Use BuildConfig.VERSION_NAME instead.",
            offenders.isEmpty(),
        )
    }

    @Test
    fun `the version code is not written into the source as a literal`() {
        val code = BuildConfig.VERSION_CODE.toString()
        val sourceRoot = sourceRoot()

        val offenders = sourceRoot.walkTopDown()
            .filter { it.isFile && (it.name.endsWith(".kt") || it.name.endsWith(".java")) }
            .filter { file ->
                // Only the assignment form is suspicious; a versionCode mentioned
                // in a comment or a format string is not a hardcoded stamp.
                Regex("""versionCode\s*=\s*$code\b""").containsMatchIn(file.readText())
            }
            .map { it.path }
            .toList()

        assertTrue("versionCode $code is hardcoded in: $offenders", offenders.isEmpty())
    }
}
