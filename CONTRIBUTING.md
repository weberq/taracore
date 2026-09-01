# Contributing

## Before you start

Read [docs/SETUP.md](docs/SETUP.md) for the toolchain, then:

```bash
git clone --recurse-submodules <repo-url> tara-core
cd tara-core
./gradlew assembleCpuDebug
```

The first native build takes 5–15 minutes. Later ones are incremental.

## What must hold

**`:api` is append-only.** Binder dispatches on a method's ordinal position in the
AIDL file, not its name. Changing a signature, reordering methods, or removing one
breaks every installed client. Add capability by appending and bumping
`API_VERSION`. Parcelables may gain fields only at the end of `writeToParcel`, with
defaults, and readers must tolerate a short parcel.

**Every native call goes through `EngineController`.** A `llama_context` is not
re-entrant. If you find yourself calling `LlamaEngine` from anywhere else, that is
the bug.

**No `TODO` without an entry in [docs/DECISIONS.md](docs/DECISIONS.md).** The tree is
currently free of them and CI is not what keeps it that way — reviewers are.

**16 KB page alignment is not optional.** Any change to a link line keeps
`-Wl,-z,max-page-size=16384`. CI asserts every `LOAD` segment reports `0x4000`,
because the failure it prevents is invisible until you hold a 16 KB-page device.

## Running the tests

```bash
./gradlew testCpuDebugUnitTest                    # unit tests, no device
./scripts/fetch-model.sh --tiny --no-push         # ~110 MB, staged into the test APK
./gradlew :engine:connectedCpuDebugAndroidTest    # instrumented, needs a device
```

Instrumented tests skip rather than fail when no GGUF is present, so a run without
weights still proves the JNI layer compiles, links and loads.

## Bumping the llama.cpp pin

```bash
cd third_party/llama.cpp
git fetch --depth 1 origin tag <new-tag>
git checkout <new-tag>
cd ../..
./gradlew :engine:assembleCpuDebug
./gradlew :engine:connectedCpuDebugAndroidTest
git add third_party/llama.cpp
```

Upstream changes its C API freely between tags — `llama_kv_cache_clear` became
`llama_memory_clear`, `use_mmap` became `load_mode`, the vocab moved out of the model.
Read the headers rather than assuming, update `THIRD_PARTY_NOTICES.md` and
`docs/SETUP.md` with the new tag, and commit the submodule bump on its own.

## Adding a dependency

Add a line to `app/src/main/assets/licenses.json` in the same commit. The About →
Licences screen reads it, and Apache-2.0 — which covers almost everything we depend
on — requires that the notice travels with the software. A dependency that ships
without its attribution is a licence violation, not a missing nicety.

The list is hand-maintained rather than generated. The generated options either want
Google Play services on the classpath or scrape POMs that several of our dependencies
do not publish usefully; the list is short enough that keeping it honest by hand is
less work than keeping a generator honest.

Update `THIRD_PARTY_NOTICES.md` too if the new dependency uses a licence not already
listed there.

## Commit messages

Conventional commits: `feat(service):`, `fix(engine):`, `docs:`, `chore:`. The body
explains *why*; the diff already shows what.
