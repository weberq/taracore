# Decisions

Running log of choices made where the specification left room, plus every `TODO`
left in the tree and why it is still a `TODO`.

> Scaffolded under the working name *Local AI Core*, renamed to **Tara Core** before
> the first source file was written; the rename is therefore a clean namespace, not a
> refactor, and no `localai` identifiers exist anywhere in the tree.

---

## D1 — Repository directory is `taracore/`, root project name is `tara-core`

The brand spec asks for a `tara-core/` repo directory. The session's working
directory is `taracore/`, so the on-disk directory keeps that name and
`rootProject.name` is `tara-core` as specified. Nothing in the build depends on the
directory name.

## D2 — llama.cpp pinned to tag `b4585`, shallow submodule

Pinned to a specific tag rather than a moving branch so builds are reproducible.
Cloned `--depth 1` because the full history is ~1 GB and nothing in the build reads
it. Bumping the pin is a deliberate, reviewable commit.

## D3 — `arm64-v8a` everywhere, `x86_64` only for emulator debug

`x86_64` is added by the `debug` build type only, guarded so `release` can never
pick it up. Release APKs would otherwise carry a ~40 MB slice no phone can run.

## D4 — CPU arch flags: `armv8.2-a+dotprod+fp16` with an `armv8-a` fallback

`GGML_CPU_ARM_ARCH` is set from a CMake `check_cxx_compiler_flag` probe. Every arm64
Android device since ~2018 supports v8.2 dotprod, but the probe means an older NDK
that rejects the flag still produces a working (slower) build instead of failing.

## D5 — Single global engine, single in-flight generation

`llama.cpp` contexts are not thread-safe and a phone has RAM for one model. The
service therefore owns exactly one `EngineController`, all native calls run on a
dedicated single-thread dispatcher, and a `Mutex` serialises generation. Concurrent
clients queue in `RequestQueue` (FIFO) rather than sharing a context. Multi-sequence
batching is on the roadmap and would change this.

## D6 — KV-cache reuse by longest common prefix

Before each generation the engine compares the new token sequence against the
previous one and keeps the shared prefix in the KV cache, removing only the divergent
tail. This makes multi-turn chat cheap without a full session-management layer. A
mismatch simply clears the cache — correctness never depends on the optimisation.

## D7 — Bearer-token auth on by default for the HTTP server

The server binds `127.0.0.1` only, but any app on the device can reach loopback
without holding a permission. A token generated on first run (32 random bytes,
base64url) is therefore required by default. Users can disable it in Settings, which
the UI labels as unsafe.

## D8 — `specialUse` foreground-service type

None of the platform FGS types describe "hosts a shared inference engine for other
apps". `specialUse` with the subtype string is the honest declaration. Play requires
a written justification for this type — noted in `docs/ARCHITECTURE.md`.

## D9 — Custom permission is `protectionLevel="normal"`

`signature` would restrict Tara Core to apps signed by the same key, defeating the
point of a shared device-wide engine. `dangerous` would force a runtime prompt with
no system-provided UI string that makes sense. `normal` means install-time grant,
visible in app details, which matches the threat model: the danger is compute and
battery use, not data exfiltration from Tara Core itself.

## D10 — Room for the model registry, DataStore for settings

The registry is relational and queried (`by id`, `by family`, `downloaded only`), so
Room. Settings are a flat bag of scalars read as a `Flow`, so Preferences DataStore.

## D11 — Prompts over 512 KB travel by `ParcelFileDescriptor`

Binder's transaction buffer is 1 MB *shared per process*, so a large prompt in the
parcel can fail for reasons unrelated to the caller. `GenerationRequest` therefore
carries an optional `largePrompt` pipe; `:client-sdk` switches to it automatically.

## D12 — `gpu` flavour falls back to CPU at runtime

The `gpu` flavour compiles the Vulkan backend in, but `ggml` device enumeration can
still find no usable device (no driver, blocklisted GPU, emulator). The engine reports
the backend it actually initialised, and the dashboard shows it, so a silent fallback
is always visible.

---

## D13 — `catalog.json` ships with empty `sha256` fields

`grep -rn "TODO\|FIXME\|XXX" --exclude-dir=third_party` over `.kt`, `.cpp`, `.h`,
`.kts`, `.xml` and `.sh` returns nothing: there are **no `TODO` markers in the tree**.
There is, however, one piece of deliberately unfinished *data*, recorded here so it is
not mistaken for an oversight.

Eighteen of the nineteen catalog entries carry `"sha256": ""`. The nineteenth,
`smollm2-135m-instruct-q4km`, has a real digest because it is the model
`scripts/fetch-model.sh --tiny` downloads and the instrumented tests run against, so
it was actually fetched and hashed during development. The digests are not invented, because a
fabricated digest is strictly worse than no digest: it would fail verification on a
correct download and send users hunting a corruption that never happened.
`ModelDownloadWorker` treats an empty digest as "cannot verify", downloads anyway, and
logs a warning that an unverified multi-gigabyte binary is about to be mapped into
memory.

**To close this:** download each catalogued file once, record the real digest, paste it
in. After that the worker verifies as it streams, at no extra cost, and a mismatch
deletes the partial file. Until then the protection is HTTPS to Hugging Face and
nothing more.

`size_bytes` is likewise approximate. It only gates the free-space check; the worker
trusts the server's `Content-Length` for progress and for what it actually writes.

## D14 — unit tests set `isReturnDefaultValues = true`

`android.util.Log` is a throwing stub in the unit-test JAR. The alternatives were a
mocking framework, or an injected logger interface threaded through classes that have
no other reason to want one. These tests exercise queue behaviour and JSON parsing,
where logging is incidental, so returning defaults is the proportionate answer.

## D15 — `:sample-client` carries a `cpu`/`gpu` dimension it does not use

It never touches native code, so the dimension means nothing to it. It is declared so
that a single `./gradlew assembleCpuDebug` builds every module in the repository,
keeping both the acceptance criteria and the CI workflow to one command.

## D16 — every request path goes through `RequestQueue`, HTTP included

The HTTP surface originally called `EngineController.stream` directly. That was
*correct* — the engine's own mutex serialises regardless — but it meant an HTTP
request did not appear in `queueDepth`, was not subject to the capacity limit, and
could overtake a bound client that asked first. `RequestQueue.QueuedRequest` now
carries its own `run` lambda so both callers share one queue without either being
special-cased inside it.

The bug this surfaced is worth recording: `cancelQueued` used to record *any* id it
was handed, and closing an HTTP response cancels by id after the request has already
finished. Every request served would have added one permanent entry to a set. The
queue now tracks which ids are actually waiting, so a late cancel is a no-op rather
than a leak. Covered by a regression test.

## D17 — the instrumented test model ships inside the test APK

The obvious approach is `adb push` into the test app's external files directory. It
does not work, for two compounding reasons:

1. `connectedAndroidTest` **uninstalls the test package** when the run finishes, and
   uninstalling removes `/sdcard/Android/data/<pkg>/`. Anything pushed before the run
   is gone by the time the next one starts.
2. A directory created by `adb push` is owned by `shell`. Under scoped storage the
   app's own sandbox view does not include it, so `File.isFile` returns false for a
   file plainly visible over adb — the failure gives no hint of the cause.

Both were observed on a Pixel 9a running Android 17 before switching approach: the
tests reported `chosen=null` while `adb shell ls` showed the file sitting there.

So the `stageTestModel` Gradle task copies whatever `scripts/fetch-model.sh`
downloaded into the androidTest assets, and the test extracts it to `cacheDir` at
startup — llama.cpp mmaps a real file, and an asset is a compressed zip entry. With
no model on disk the task copies nothing and every model-dependent test skips itself,
so a CI run without weights stays green and still proves the JNI layer links and
loads.

## D18 — JDK selection stays out of the repository

`org.gradle.java.home` is machine-specific, so it is not committed. `docs/SETUP.md`
tells contributors to export `JAVA_HOME` instead. This matters more than it sounds:
the default `java` on many Linux boxes is a JRE, and Gradle's failure for that case
(`does not provide the required capabilities: [JAVA_COMPILER]`) names neither the JDK
nor the fix.

---

## Open `TODO`s

None. `grep -rn "TODO\|FIXME\|XXX" --exclude-dir=third_party` over the source tree
returns no matches. The one knowingly incomplete item in the repository is the
catalog's missing SHA-256 digests, recorded as D13 above.
