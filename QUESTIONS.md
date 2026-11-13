# Questions for silentshadow7

Decisions I could not make alone. Each has the conservative option I took so work
could continue, and what changes if you choose differently.

---

## Q1. Context wrapping cannot be done the way Phase B describes

**The problem.** Phase B asks for a context wrapper whose `getClassLoader()`,
`getAssets()`, `getResources()` and `getFilesDir()` resolve to Minecraft's.
`AppComponentFactory.instantiateActivity` returns an `Activity` instance; Android
then calls `Activity.attach(...)` itself, and `attach` calls `attachBaseContext`
with a context the platform built from our `ActivityInfo`. There is no hook
between our return and that call.

Wrapping would need one of:

- **a subclass of the game's Activity** that overrides `attachBaseContext` — we
  cannot create one at runtime for a class we did not compile
- **bytecode generation** of such a subclass — DexMaker or similar, plus the
  subclass would still be initialised in our process
- **replacing the Activity's `mBase` field by reflection after attach** — it is
  `Context mBase` on `ContextWrapper`; reachable, but it would have to happen
  before the game's `onCreate` runs, and nothing of ours runs in that window
- **an `Instrumentation` subclass** — requires `android:instrumentation` in the
  manifest and a runner; not available to a normal installed app

**Conservative option taken:** the factory returns the real class and lets Android
attach it with our context. The probe records what happens. This proves
`onCreate` is entered (or that it dies before it), which is the stated goal of
Phase B, without inventing a wrapper that would not actually be used.

**What I need from you:** whether to attempt the reflection route (set `mBase`
from a background thread racing `onCreate` — fragile, and I would not ship it), or
to accept that the game runs with our context and see how far it gets. I did the
second.

---

## Q2. `initialise = false` and instantiation

Phase A showed that `Class.forName(initialise = true)` aborts the process. Phase B
still has to *instantiate* the class to return an Activity, and
`newInstance()` runs the static initialiser as a side effect of class
initialisation — the JVM initialises a class before its first instance is created.

So Phase B's factory will, unavoidably, trigger the same initialisation that killed
the process in Phase A — just from inside Android's Activity creation instead of
from our probe. There is no way to create an instance without initialising the
class.

**Conservative option taken:** do it anyway, because that is the only route to
`onCreate`, and record it as a stage first so the crash is attributed correctly.

**What this means:** if the process dies in the factory, that is *not* new
information — it is Phase A's result arriving by a different path. Phase C (the
forwarder that provides a working `libminecraftpe.so`) is what would change it.

---

## Q3. Signing key in a public repository

`ci/debug.keystore` is committed so every CI run signs identically.

**Conservative option taken:** commit it, since it is a throwaway debug key and it
removes cache-eviction and secret-setup failure modes.

**If you ever ship a release build:** that key must not be used. A release key
needs to live outside the repository.
