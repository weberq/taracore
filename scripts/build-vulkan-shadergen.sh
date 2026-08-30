#!/usr/bin/env bash
# Build ggml's vulkan-shaders-gen for the HOST machine.
#
# Why this exists: the Vulkan backend generates its compute shaders at build time
# using a helper binary. When we cross-compile for arm64 Android, CMake would build
# that helper for arm64 too -- and then try to execute it on your x86_64 workstation.
# So the helper has to be built separately, for the host, before the Android build
# starts. This is the two-step build referenced by docs/SETUP.md.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LLAMA="$ROOT/third_party/llama.cpp"
BUILD="$ROOT/build/host-vulkan-shadergen"
SRC="$LLAMA/ggml/src/ggml-vulkan/vulkan-shaders"

if [[ ! -d "$LLAMA/ggml" ]]; then
    echo "error: $LLAMA is empty. Run: git submodule update --init --recursive" >&2
    exit 1
fi

if [[ ! -d "$SRC" ]]; then
    echo "error: expected shader generator sources at $SRC" >&2
    echo "The llama.cpp layout may have changed; check upstream." >&2
    exit 1
fi

if ! command -v glslc >/dev/null 2>&1; then
    cat >&2 <<'EOF'
error: glslc not found on PATH.

The generator shells out to glslc to compile each shader. Install it:
  Debian/Ubuntu : sudo apt install glslc
  Fedora        : sudo dnf install glslc
  Arch          : sudo pacman -S shaderc
  macOS         : brew install shaderc
  or install the LunarG Vulkan SDK, which bundles it.
EOF
    exit 1
fi

echo "== step 1/2: building vulkan-shaders-gen for the host =="
cmake -S "$SRC" -B "$BUILD" -DCMAKE_BUILD_TYPE=Release
cmake --build "$BUILD" --config Release -j"$(nproc 2>/dev/null || echo 4)"

GEN="$(find "$BUILD" -name 'vulkan-shaders-gen' -type f -perm -u+x | head -1)"
if [[ -z "$GEN" ]]; then
    echo "error: build succeeded but vulkan-shaders-gen was not found under $BUILD" >&2
    exit 1
fi

cat <<EOF

== step 2/2: build the app ==

Host generator: $GEN

    ./gradlew assembleGpuDebug

If the Android build cannot find the generator on its own, point it there explicitly:

    ./gradlew assembleGpuDebug \\
      -Pandroid.native.buildOutput=verbose \\
      --project-prop VULKAN_SHADER_GEN="$GEN"

or simply put it on PATH:

    export PATH="$(dirname "$GEN"):\$PATH"
EOF
