package dev.vela.app

import android.content.Context
import android.os.Build
import java.io.File

/**
 * Preloads the game's libraries in a fixed order, by absolute path.
 *
 * The order is deliberate: the C++ runtime first, then the media and network
 * libraries the game expects to already be in the process, then PairIP's core,
 * then the PlayFab and MSA SDKs. Each is a Reason stage written before the load,
 * so a fatal load leaves a line naming itself with conclusion pending.
 *
 * A library that is not present is logged ABSENT and skipped, not silently
 * ignored — "we did not try" and "it failed" are different facts.
 *
 * Nothing here patches or disables anything. libpairipcore is loaded because the
 * game loads it, and its integrity checks are left alone.
 */
object NativePreload {

    /** Order matters. "media/network" are the four the game expects pre-present. */
    private val ORDER = listOf(
        "libc++_shared.so",
        "libfmod.so",
        "libMediaDecoders_Android.so",
        "libHttpClient.Android.so",
        "libconscrypt_jni.so",
        "libpairipcore.so",
        "libPlayFabMultiplayer.so",
        "libmaesdk.so",
    )

    data class Report(
        val loaded: List<String>,
        val absent: List<String>,
        val failed: Map<String, String>,
    ) {
        fun summary(): String =
            "loaded ${loaded.size}, absent ${absent.size}, failed ${failed.size}"
    }

    fun run(context: Context, filesDir: File?): Report {
        val info = try {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(VelaComponentFactory.GAME_PACKAGE, 0)
        } catch (t: Throwable) {
            filesDir?.let {
                Reason.result(
                    it,
                    question = "can the game's libraries be preloaded?",
                    attempt = "resolve the package",
                    observation = "${t.javaClass.name}: ${t.message}",
                    conclusion = "no - the package could not be resolved",
                )
            }
            return Report(emptyList(), ORDER, mapOf())
        }

        val dir = info.applicationInfo?.nativeLibraryDir
        if (dir.isNullOrEmpty()) {
            filesDir?.let {
                Reason.result(
                    it,
                    question = "can the game's libraries be preloaded?",
                    attempt = "read nativeLibraryDir",
                    observation = "nativeLibraryDir is empty",
                    conclusion =
                        "no - the game keeps its libraries inside its APK, so there is " +
                            "nothing to preload from a directory",
                )
            }
            return Report(emptyList(), ORDER, mapOf())
        }

        val available = (File(dir).listFiles { f -> f.isFile && f.name.endsWith(".so") } ?: emptyArray())
            .associateBy { it.name }

        filesDir?.let {
            Reason.result(
                it,
                question = "what is in the game's native library directory?",
                attempt = "list $dir",
                observation = available.keys.sorted().joinToString(),
                conclusion = "${available.size} libraries present",
            )
        }

        val loaded = ArrayList<String>()
        val absent = ArrayList<String>()
        val failed = LinkedHashMap<String, String>()

        for (name in ORDER) {
            val file = available[name]
            if (file == null) {
                absent.add(name)
                filesDir?.let {
                    Reason.result(
                        it,
                        question = "is $name present to preload?",
                        attempt = "look for $name in $dir",
                        observation = "absent",
                        conclusion = "skipped - it is not in the native library directory",
                    )
                }
                continue
            }

            filesDir?.let {
                Reason.stage(
                    it,
                    question = "does $name load into this process?",
                    attempt = "System.load(${file.absolutePath})",
                )
            }
            try {
                System.load(file.absolutePath)
                loaded.add(name)
                filesDir?.let {
                    Reason.result(
                        it,
                        question = "does $name load into this process?",
                        attempt = "System.load(...)",
                        observation = "loaded, ${file.length() / 1024} KB",
                        conclusion = "yes",
                    )
                }
            } catch (e: UnsatisfiedLinkError) {
                failed[name] = e.message ?: "UnsatisfiedLinkError"
                filesDir?.let {
                    Reason.result(
                        it,
                        question = "does $name load into this process?",
                        attempt = "System.load(...)",
                        observation = "UnsatisfiedLinkError: ${e.message}",
                        conclusion = "no - see the message above",
                    )
                }
                break
            } catch (t: Throwable) {
                failed[name] = "${t.javaClass.name}: ${t.message}"
                filesDir?.let {
                    Reason.result(
                        it,
                        question = "does $name load into this process?",
                        attempt = "System.load(...)",
                        observation = "${t.javaClass.name}: ${t.message}",
                        conclusion = "no",
                    )
                }
                break
            }
        }

        return Report(loaded, absent, failed)
    }
}
