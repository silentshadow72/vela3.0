package dev.vela;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Recognises and unpacks the two kinds of shader pack Vela supports.
 *
 * They are completely different mechanisms and must not be confused:
 *
 *   BEDROCK  - an .mcpack built for MB Loader and friends. It ships compiled
 *              .material.bin files under materials/ (sometimes assets/renderer/
 *              materials/). No compiler is needed: the files are already built
 *              for RenderDragon, they just have to be placed where the game
 *              reads its materials from.
 *
 *   JAVA     - an OptiFine/Iris shaderpack: a zip with shaders/ inside holding
 *              composite.fsh, gbuffers_*, shaders.properties. These are source,
 *              and are executed by Vela's own runtime, not by RenderDragon.
 *
 * Detection is by contents, not by file extension, because both arrive as .zip
 * or .mcpack and users rename things constantly.
 */
public final class ShaderPackImporter {

    private static final String TAG = "VelaShaders";

    public enum Kind { BEDROCK, JAVA, UNKNOWN }

    public static final class Result {
        public final Kind kind;
        public final int files;        // how many shader files were found
        public final String detail;    // human-readable summary

        Result(Kind kind, int files, String detail) {
            this.kind = kind;
            this.files = files;
            this.detail = detail;
        }
    }

    private ShaderPackImporter() {
    }

    /** Inspects a zip without extracting anything. */
    public static Result inspect(Context context, File pack) {
        int materialBins = 0;
        int javaPrograms = 0;
        boolean hasShadersProperties = false;
        boolean hasManifest = false;

        try (ZipInputStream zip = open(context, pack)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName().toLowerCase();
                if (name.endsWith(".material.bin")) materialBins++;
                if (name.endsWith("shaders.properties")) hasShadersProperties = true;
                if (name.endsWith("manifest.json")) hasManifest = true;
                if (name.contains("/shaders/") || name.startsWith("shaders/")) {
                    if (name.endsWith(".fsh") || name.endsWith(".vsh")) javaPrograms++;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "inspect failed for " + pack.getName(), e);
            return new Result(Kind.UNKNOWN, 0, "Not a readable zip");
        }

        // A Bedrock pack's defining feature is compiled materials.
        if (materialBins > 0) {
            return new Result(Kind.BEDROCK, materialBins,
                    materialBins + " compiled material file(s) for RenderDragon");
        }
        // A Java pack's defining feature is OptiFine program sources.
        if (javaPrograms > 0 || hasShadersProperties) {
            return new Result(Kind.JAVA, javaPrograms,
                    (hasShadersProperties ? "shaders.properties, " : "")
                            + javaPrograms + " OptiFine program(s)");
        }
        if (hasManifest) {
            return new Result(Kind.UNKNOWN, 0, "A resource pack, not a shader pack");
        }
        return new Result(Kind.UNKNOWN, 0, "No materials and no shaders/ folder");
    }

    /**
     * Extracts a Bedrock pack's material files into the instance's overlay
     * directory, preserving their relative paths so the game's own layout can be
     * mirrored exactly.
     */
    public static int extractMaterials(Context context, File pack, File overlayDir) {
        int written = 0;
        try (ZipInputStream zip = open(context, pack)) {
            ZipEntry entry;
            byte[] buffer = new byte[16384];
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                String name = entry.getName();
                if (!name.toLowerCase().endsWith(".material.bin")) continue;

                // Flatten to the basename: the game reads materials by name, and
                // packs disagree about whether they nest under assets/renderer/.
                String base = name.substring(name.lastIndexOf('/') + 1);
                File out = new File(overlayDir, base);
                if (!out.getCanonicalPath().startsWith(overlayDir.getCanonicalPath())) {
                    Log.w(TAG, "skipping unsafe entry " + name);
                    continue;
                }
                File parent = out.getParentFile();
                if (parent != null && !parent.exists() && !parent.mkdirs()) continue;
                try (OutputStream os = new FileOutputStream(out)) {
                    int read;
                    while ((read = zip.read(buffer)) > 0) os.write(buffer, 0, read);
                }
                written++;
            }
        } catch (Exception e) {
            Log.w(TAG, "extractMaterials failed", e);
        }
        return written;
    }

    /** Copies a Java pack's zip into the runtime's pack directory. */
    public static boolean installJavaPack(Context context, File pack, File packDir) {
        File dest = new File(packDir, pack.getName());
        try {
            if (!packDir.exists() && !packDir.mkdirs()) return false;
            try (InputStream in = open(context, pack);
                 OutputStream out = new FileOutputStream(dest)) {
                byte[] buffer = new byte[16384];
                int read;
                while ((read = in.read(buffer)) > 0) out.write(buffer, 0, read);
            }
            return true;
        } catch (Exception e) {
            Log.w(TAG, "installJavaPack failed", e);
            return false;
        }
    }

    /** Lists the material files currently overlaid for an instance. */
    public static List<String> overlaidMaterials(File overlayDir) {
        List<String> names = new ArrayList<>();
        File[] files = overlayDir == null ? null : overlayDir.listFiles();
        if (files == null) return names;
        for (File f : files) {
            if (f.getName().endsWith(".material.bin")) names.add(f.getName());
        }
        java.util.Collections.sort(names);
        return names;
    }

    private static ZipInputStream open(Context context, File pack) throws Exception {
        return new ZipInputStream(new java.io.FileInputStream(pack));
    }
}
