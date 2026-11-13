package dev.vela.app

import android.content.Context
import android.content.pm.PackageInfo
import android.os.Build
import java.io.File

/**
 * Builds the classloader the game's Activity is hosted through.
 *
 * The research that corrected the earlier approach: `GameActivity.onCreate()`
 * calls `classLoader.findLibrary("minecraftpe")` and then `System.load` itself. So
 * the classloader must be able to answer `findLibrary`, which means its native
 * path list has to include the game's libraries — and, on a Play Store install,
 * those libraries live inside the APK and its split APKs rather than in
 * `nativeLibraryDir`.
 *
 * The path list is therefore built from every place a library can be:
 *
 *   base.apk!/lib/<abi>
 *   each split_config.<abi>.apk!/lib/<abi>
 *   nativeLibraryDir
 *
 * Each entry is logged, and so is whether `com.pairip.VMRunner` resolves through
 * the resulting loader — because PairIP being visible or not changes what can
 * happen next, and guessing about it is worse than recording it.
 */
object GameLoader {

    data class Built(
        val loader: ClassLoader,
        val dexPath: String,
        val nativePath: String,
        val entries: List<String>,
        val vmRunnerResolved: Boolean,
        val gameClass: Class<*>?,
        val error: String? = null,
    )

    fun build(context: Context, filesDir: File?): Built {
        val info: PackageInfo = try {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(VelaComponentFactory.GAME_PACKAGE, 0)
        } catch (t: Throwable) {
            return Built(
                context.classLoader, "", "", emptyList(), false, null,
                "could not resolve ${VelaComponentFactory.GAME_PACKAGE}: ${t.message}",
            )
        }

        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
        val app = info.applicationInfo
        val base = app?.sourceDir
        val splits = app?.splitSourceDirs?.filterNotNull() ?: emptyList()
        val nativeDir = app?.nativeLibraryDir

        filesDir?.let {
            Reason.result(
                it,
                question = "where are the game's code and libraries?",
                attempt = "getPackageInfo(${VelaComponentFactory.GAME_PACKAGE})",
                observation = "abi=$abi base=$base splits=${splits.size} nativeLibraryDir=$nativeDir",
                conclusion = "resolved",
            )
        }

        if (base.isNullOrEmpty()) {
            return Built(
                context.classLoader, "", "", emptyList(), false, null,
                "the game has no sourceDir",
            )
        }

        // ---- dex path: base plus every split -----------------------------
        val dexPath = (listOf(base) + splits).joinToString(":")

        // ---- native path: every place a .so can be -----------------------
        val entries = ArrayList<String>()
        entries.add("$base!/lib/$abi")
        for (split in splits) {
            entries.add("$split!/lib/$abi")
        }
        if (!nativeDir.isNullOrEmpty()) entries.add(nativeDir)
        val nativePath = entries.joinToString(":")

        filesDir?.let {
            Reason.result(
                it,
                question = "what native path list does the loader get?",
                attempt = "join base.apk!/lib/$abi, each split!/lib/$abi, nativeLibraryDir",
                observation = entries.joinToString(" | "),
                conclusion = "findLibrary will search these in order",
            )
        }
        filesDir?.let {
            Reason.result(
                it,
                question = "what dex path does the loader get?",
                attempt = "base + ${splits.size} split(s)",
                observation = dexPath,
                conclusion = "the game's classes come from here",
            )
        }

        // ---- the loader ---------------------------------------------------
        val parent = context.classLoader.parent
        filesDir?.let {
            Reason.stage(
                it,
                question = "does a DexClassLoader with the full path list build?",
                attempt = "new DexClassLoader(dexPath, null, nativePath, parent)",
            )
        }

        val loader = try {
            Class.forName("dalvik.system.DexClassLoader")
                .getConstructor(
                    String::class.java,
                    String::class.java,
                    String::class.java,
                    ClassLoader::class.java,
                )
                .newInstance(dexPath, null, nativePath, parent) as ClassLoader
        } catch (t: Throwable) {
            filesDir?.let {
                Reason.result(
                    it,
                    question = "does a DexClassLoader with the full path list build?",
                    attempt = "new DexClassLoader(dexPath, null, nativePath, parent)",
                    observation = "${t.javaClass.name}: ${t.message}",
                    conclusion = "no - the loader could not be constructed",
                )
            }
            return Built(
                context.classLoader, dexPath, nativePath, entries, false, null,
                "could not build the loader: ${t.message}",
            )
        }

        filesDir?.let {
            Reason.result(
                it,
                question = "does a DexClassLoader with the full path list build?",
                attempt = "new DexClassLoader(dexPath, null, nativePath, parent)",
                observation = "built ${loader.javaClass.name}",
                conclusion = "yes",
            )
        }

        // ---- does findLibrary answer for the game's library? --------------
        val found = try {
            loader.loadClass("dalvik.system.BaseDexClassLoader")
            val method = Class.forName("dalvik.system.BaseDexClassLoader")
                .getMethod("findLibrary", String::class.java)
            method.invoke(loader, "minecraftpe") as String?
        } catch (t: Throwable) {
            null
        }
        filesDir?.let {
            Reason.result(
                it,
                question = "does findLibrary(\"minecraftpe\") resolve?",
                attempt = "BaseDexClassLoader.findLibrary through the new loader",
                observation = if (found == null) "returned null" else "returned $found",
                conclusion = if (found == null)
                    "no - GameActivity.onCreate would call System.load(null) and fail"
                else "yes - the game can load its own library",
            )
        }

        // ---- is PairIP's VMRunner visible? --------------------------------
        val vmRunner = try {
            Class.forName("com.pairip.VMRunner", false, loader)
            true
        } catch (t: Throwable) {
            false
        }
        filesDir?.let {
            Reason.result(
                it,
                question = "is com.pairip.VMRunner visible through the loader?",
                attempt = "Class.forName(\"com.pairip.VMRunner\", false, loader)",
                observation = if (vmRunner) "resolved" else "not resolved",
                conclusion = if (vmRunner)
                    "yes - PairIP's Java side is reachable; it is not patched or disabled"
                else "no - the class is not in the dex this loader sees",
            )
        }

        // ---- the Activity class -------------------------------------------
        val gameClass = try {
            Class.forName(VelaComponentFactory.GAME_ACTIVITY, false, loader)
        } catch (t: Throwable) {
            filesDir?.let {
                Reason.result(
                    it,
                    question = "does the Activity class load through this loader?",
                    attempt = "Class.forName(${VelaComponentFactory.GAME_ACTIVITY}, initialise = false)",
                    observation = "${t.javaClass.name}: ${t.message}",
                    conclusion = "no",
                )
            }
            null
        }

        return Built(loader, dexPath, nativePath, entries, vmRunner, gameClass)
    }
}
