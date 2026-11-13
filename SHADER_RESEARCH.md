# M4 Part 4 — resolved before coding

The brief requires answering one question before building the floating ball:
**can the mod's in-game shader menu be triggered from outside?** The mod is a
closed `.so`, so this was answered by reading its symbols and strings, not by
guessing.

## What the library exposes

Exported dynamic symbols, in full:

```
PLGetModRegistration   (12 bytes)
JNI_OnLoad             (24 bytes)
```

That is all. There is no exported `showMenu`, `openMenu`, `toggle`, or anything
else callable from another process or another `.so`.

## How the menu actually works

The strings name a host-side framework:

```
_ZN2pl7modmenu14registerModuleERKNS0_10ModuleInfoE
_ZN2pl7modmenu16requestCloseMenuEv
_ZN2pl7modmenu16setModuleEnabledENSt6__ndk117basic_string_viewIcNS1_11char_traitsIcEEEEb
_ZN2pl7modmenu16unregisterModuleENSt6__ndk117basic_string_viewIcNS1_11char_traitsIcEEEE
_ZN2pl7modmenu19setConfigSchemaJsonENSt6__ndk117basic_string_viewIcNS1_11char_traitsIcEEEES5_E
```

These are `pl::modmenu` — the **host's** mod-menu API. IriOptic *registers* its
modules with the host and the host draws the menu. The mod does not own the menu,
so there is nothing in the mod to open.

The library even says so itself:

> `mod menu unavailable; the saved pack still loads`

So a floating ball that calls into the mod to open a menu would call nothing.
**That button would do nothing, and the brief says not to ship one.**

## What the mod does watch

```
/files/IriOptic
/sdcard/games/IriOptic
/shaderpacks
/settings.properties
"Looks for new .zip files and folders in the shaderpacks folder."
" Rescanned the shaderpacks folder"
"android.intent.action.OPEN_DOCUMENT"
```

- it scans a **`shaderpacks`** folder and rescans when it changes
- it reads **`settings.properties`** in `/files/IriOptic`
- it can launch its own `OPEN_DOCUMENT` picker to import a `.zip`
- the pause screen already has its full manager: "import .zip packs, options
  screens, settings"

## The bridge I would pick

**Write to the two things the mod already reads, and do not try to open its
menu.**

1. The launcher imports a `.zip` by copying it into the `shaderpacks` folder the
   mod scans. The mod rescans on its own; no call into the `.so` is needed.
2. Settings are written to `/settings.properties`, which the mod reads.

This is a real, working bridge because both are inputs the mod already consumes.
It needs no symbols, no hooks, and no change to PairIP or the mod.

**Rejected alternatives, and why:**

- *Flag file the mod watches* — there is no evidence it watches anything but the
  shaderpacks folder. Inventing a flag file it ignores would be the same silent
  no-op as a dead button.
- *Broadcast receiver or intent* — the only intent in the library is
  `OPEN_DOCUMENT`, which is the mod launching a picker outward, not receiving.
- *Asking the author for a documented entry point* — worth doing eventually, but
  it blocks M4 on someone else's reply.

**Consequence for Part 4 as written:** the floating ball cannot open *the mod's*
menu, because the mod's menu belongs to a host framework we are not shipping. The
ball can open **our** shader panel, which manages the shaderpacks folder and
settings file — the same inputs, our UI. That is what Part 4 should build, and it
is a decision worth confirming before it is built.
