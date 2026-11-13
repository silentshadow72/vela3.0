# M4 Part 1 — what was carried across from vela2.0

Source: `silentshadow72/vela2.0` at `main`, unpacked from
`vela-app-with-renderer.zip` in that repository.

The pack the brief calls "Java shaders (optifine)" is
**the pack now shipped as `JavaShadersOptifine.levipack`**. Its original folder name differed;
the manifest is what identifies it:

> "Runs unmodified Java (OptiFine/Iris) shader packs such as BSL and Nostalgia,
> with an in-game shader manager."

Names, manifests and authors are unchanged.

## Binary packs → `app/src/main/assets/mods/`

| file | size | author (from manifest) | version |
| --- | --- | --- | --- |
| `JavaShadersOptifine.levipack` | 427 KB | anonymous, by request | 1.0.0 |
| `VelaRender.levipack` | 636 KB | — | — |
| `DynamicShadows.levipack` | 175 KB | chromehaert | 2.2.5 |
| `CustomBuffers.levipack` | 34 KB | chromehaert | 1.1.1 |
| `VelaComposite.levipack` | 4.8 KB | — | — |

All five are `"type": "preload-native"`: they are native `.so` files with a
manifest, not Java shader packs. The Java shader pack is the one that runs (OptiFine/Iris)
packs.

## Source files → `app/src/main/legacy-shaders/`

| file | size | from |
| --- | --- | --- |
| `ShaderOverlay.kt` | 8.2 KB | `app/src/main/java/dev/vela/ui/` |
| `ShaderPackImporter.java` | 6.6 KB | `app/src/main/java/dev/vela/` |
| `ShaderPackOptions.kt` | 4.7 KB | `app/src/main/java/dev/vela/core/` |

**These are not compiled.** They sit outside the Gradle source set because they
depend on vela2.0's launcher classes — `ModManager`, `GameVersion`, its instance
model — none of which exist in vela3.0. Copying them into `src/main/java` would
break the build immediately, and rewriting them silently against a different
class model would be worse than leaving them legible and honest.

They are carried across as the reference for Parts 3 and 4: what the overlay did,
how the importer copied packs, and how `shaders.properties` was parsed.

## Licensing, stated plainly
The Java shader pack is third-party, bundled with permission and credited as
"anonymous, by request". Its manifest carries that line.
VelaRender, VelaComposite, DynamicShadows and CustomBuffers are from vela2.0.
Bundling them is what was asked for; publishing a derivative of any of them needs
the author's permission. That has not changed since it was last raised.
