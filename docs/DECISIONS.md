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

## Open `TODO`s

*(none yet — this section is filled in as the phases land, and every `TODO` in the
tree must appear here with a reason)*
