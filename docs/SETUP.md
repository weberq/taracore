# Setup

## What you need

| Tool | Version | Why |
|---|---|---|
| JDK | 17 or 21 | AGP 8.7 targets Java 17 bytecode. A **JDK**, not a JRE — a JRE has no `javac` and Gradle fails with `does not provide the required capabilities: [JAVA_COMPILER]`. |
| Android SDK | Platform 35, Build-Tools 35.0.0 | `compileSdk`/`targetSdk` 35. |
| Android NDK | r27 (`27.0.12077973`) or newer | 16 KB page support and a clang new enough for `armv8.2-a+dotprod+fp16`. |
| CMake | 3.22.1 (the SDK-bundled one) | Pinned in `engine/build.gradle.kts`. |
| Git | any | `llama.cpp` is a submodule. |

## First clone

```bash
git clone --recurse-submodules <repo-url> tara-core
cd tara-core
```

Cloned without `--recurse-submodules`? The CMake configure step fails with an
explicit message. Fix it with:

```bash
git submodule update --init --recursive
```

The submodule is pinned to llama.cpp tag **`b10689`** and registered `shallow = true`,
so it fetches ~60 MB rather than the ~1 GB of full history.

## Install the SDK bits

With Android Studio: **SDK Manager → SDK Tools**, tick *NDK (Side by side)* 27.x and
*CMake* 3.22.1.

Headless:

```bash
export ANDROID_HOME="$HOME/Android/Sdk"
"$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" \
  "platforms;android-35" \
  "build-tools;35.0.0" \
  "ndk;27.0.12077973" \
  "cmake;3.22.1"
yes | "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --licenses
```

Then point the build at it:

```bash
echo "sdk.dir=$ANDROID_HOME" > local.properties
```

`local.properties` is machine-specific and git-ignored. So is your JDK choice — if the
JDK on `PATH` is not the one you want, export `JAVA_HOME` rather than committing
`org.gradle.java.home`:

```bash
export JAVA_HOME=/path/to/jdk-21   # e.g. /opt/android-studio/jbr
```

## Build

```bash
./gradlew assembleCpuDebug          # everything, CPU backend
./gradlew :engine:assembleCpuDebug  # just the native engine
./gradlew test                      # unit tests
```

The first native build compiles all of ggml and llama.cpp and takes **5–15 minutes**.
Later builds are incremental and take seconds. Objects land in `engine/.cxx/`.

## Flavours

| Flavour | CMake | Notes |
|---|---|---|
| `cpu` *(default)* | — | Pure CPU. Works everywhere. |
| `gpu` | `-DTARACORE_VULKAN=ON` | Vulkan compute. Falls back to CPU at runtime when no usable device is found; the Dashboard shows which one is live. |

### Building the `gpu` flavour

ggml's Vulkan backend compiles its shaders with a helper that runs on the **host**,
not the phone, so it has to exist before the cross-compile starts. Two steps:

```bash
./scripts/build-vulkan-shadergen.sh          # once per checkout
./gradlew assembleGpuDebug
```

The script builds `vulkan-shaders-gen` for your workstation and prints the path;
export it as `Vulkan_GLSLC_EXECUTABLE` / put it on `PATH` if the Gradle build cannot
find it. You also need the Vulkan SDK's `glslc` — `apt install glslc` or the LunarG
SDK. Without them the `gpu` flavour fails at configure time; the `cpu` flavour is
unaffected.

### OpenCL (Adreno)

Off by default and not wired to a flavour: `-DTARACORE_OPENCL=ON` targets Qualcomm
Adreno specifically and is a no-op on Mali and Xclipse. Enable it by hand if you are
shipping to known Adreno hardware:

```bash
./gradlew :engine:assembleCpuDebug -PtaracoreOpenCl=true
```

## Getting a model to test with

No weights are committed. `scripts/fetch-model.sh` downloads a small GGUF and pushes
it where the instrumented test expects it:

```bash
./scripts/fetch-model.sh            # ~350 MB Qwen2.5-0.5B-Instruct Q4_K_M
./scripts/fetch-model.sh --tiny     # ~110 MB SmolLM2-135M Q4_K_M, for CI
```

## Running the instrumented engine test

Needs a physical arm64 device or an x86_64 emulator (the debug build carries both):

```bash
./scripts/fetch-model.sh --tiny --no-push
./gradlew :engine:connectedCpuDebugAndroidTest
```

The GGUF is staged **into the test APK** by the `stageTestModel` Gradle task and
extracted to the cache directory at runtime. It is not pushed to the device, and this
is not an arbitrary choice: `connectedAndroidTest` uninstalls the test package when it
finishes, which takes `/sdcard/Android/data/<pkg>/` with it, so anything pushed
beforehand is either already gone or was created by `shell` and is invisible to the
app's storage sandbox. Shipping it inside the APK avoids all of that and behaves
identically on CI.

With no model on disk the task stages nothing and every model-dependent test skips
itself, so the run still proves the JNI layer compiles, links and loads.

Verified on a Pixel 9a (Tensor G4, Android 17): 6/6 tests pass, SmolLM2-135M Q4_K_M
generating at ~28 tok/s on CPU.

## Troubleshooting

**`SDK XML file of version 4 was encountered`** — harmless. Your command-line tools
are newer than the NDK's bundled parser; the build proceeds.

**`CMake Error: third_party/llama.cpp is empty`** — run
`git submodule update --init --recursive`.

**`ninja: build stopped: subcommand failed`** — scroll up for the first `error:`.
The usual cause is an NDK older than r27 rejecting `-march=armv8.2-a+dotprod+fp16`;
the CMake probe should catch that and fall back, so please file it if it does not.

**`Toolchain installation ... does not provide the required capabilities`** — your
`JAVA_HOME` points at a JRE. Point it at a JDK.

**Out of memory during the native build** — ggml's Vulkan sources are heavy. Lower
parallelism: `./gradlew assembleCpuDebug --max-workers=2`.
