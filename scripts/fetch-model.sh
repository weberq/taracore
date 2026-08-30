#!/usr/bin/env bash
# Download a small GGUF for testing and push it to a connected device.
#
# No weights are committed to this repository -- they are large, they carry their own
# licenses, and they change independently of the code. This script fetches one.
set -euo pipefail

TINY_URL="https://huggingface.co/bartowski/SmolLM2-135M-Instruct-GGUF/resolve/main/SmolLM2-135M-Instruct-Q4_K_M.gguf"
TINY_NAME="smollm2-135m-instruct-q4km.gguf"

SMALL_URL="https://huggingface.co/bartowski/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/Qwen2.5-0.5B-Instruct-Q4_K_M.gguf"
SMALL_NAME="qwen2.5-0.5b-instruct-q4km.gguf"

URL="$SMALL_URL"
NAME="$SMALL_NAME"
PUSH=1

usage() {
    cat <<EOF
usage: $0 [--tiny] [--no-push] [--url URL --name NAME]

  --tiny      SmolLM2-135M Q4_K_M (~110 MB). Fast, barely coherent, ideal for CI.
  --no-push   Download only; skip adb push.
  --url/--name  Fetch some other GGUF.

Default: Qwen2.5-0.5B-Instruct Q4_K_M (~350 MB) -- small enough for CI, coherent
enough that a failed generation is obviously a bug rather than the model being tiny.
EOF
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --tiny)    URL="$TINY_URL"; NAME="$TINY_NAME"; shift ;;
        --no-push) PUSH=0; shift ;;
        --url)     URL="$2"; shift 2 ;;
        --name)    NAME="$2"; shift 2 ;;
        -h|--help) usage; exit 0 ;;
        *) echo "unknown argument: $1" >&2; usage; exit 2 ;;
    esac
done

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT_DIR="$ROOT/build/models"
OUT="$OUT_DIR/$NAME"
mkdir -p "$OUT_DIR"

if [[ -f "$OUT" ]]; then
    echo "already have $OUT ($(du -h "$OUT" | cut -f1))"
else
    echo "downloading $NAME"
    echo "  from $URL"
    # -C - resumes a partial download; the temp name means an interrupted run never
    # leaves a truncated file looking like a complete one.
    curl -fL --progress-bar -C - -o "$OUT.part" "$URL"
    mv "$OUT.part" "$OUT"
    echo "saved $OUT ($(du -h "$OUT" | cut -f1))"
fi

echo "sha256: $(sha256sum "$OUT" | cut -d' ' -f1)"

# The instrumented tests do NOT read from the device filesystem: the model is staged
# into the test APK by the stageTestModel Gradle task, because connectedAndroidTest
# uninstalls the test package afterwards and takes its external files directory with
# it. The push below is only a convenience for the Tara Core app itself, so the
# Playground has something to run without downloading it on the phone.
if [[ "$PUSH" -eq 1 ]]; then
    if ! command -v adb >/dev/null 2>&1; then
        echo "adb not on PATH; skipping push" >&2
        exit 0
    fi
    if ! adb get-state >/dev/null 2>&1; then
        echo "no device connected; skipping push" >&2
        exit 0
    fi

    # Where the Tara Core app looks. ModelRepository.sync() adopts any .gguf it finds
    # here as a side-loaded model, so it shows up in the picker on next launch.
    APP_DEST="/sdcard/Android/data/dev.taracore/files/models"
    if adb shell "mkdir -p '$APP_DEST'" 2>/dev/null; then
        echo "pushing to $APP_DEST/$NAME"
        adb push "$OUT" "$APP_DEST/$NAME"
    else
        echo "could not create $APP_DEST (install Tara Core first); skipping push" >&2
    fi

    echo
    echo "For the instrumented tests, no push is needed -- just build:"
    echo "  ./gradlew :engine:connectedCpuDebugAndroidTest"
fi
