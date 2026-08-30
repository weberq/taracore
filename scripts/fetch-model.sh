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

if [[ "$PUSH" -eq 1 ]]; then
    if ! command -v adb >/dev/null 2>&1; then
        echo "adb not on PATH; skipping push" >&2
        exit 0
    fi
    if ! adb get-state >/dev/null 2>&1; then
        echo "no device connected; skipping push" >&2
        exit 0
    fi

    # The instrumented test reads from the app's external files dir, which is
    # world-writable via adb without root and survives an app reinstall.
    DEST="/sdcard/Android/data/dev.taracore.engine.test/files/models"
    adb shell "mkdir -p '$DEST'" || true
    echo "pushing to $DEST/$NAME"
    adb push "$OUT" "$DEST/$NAME"

    # Also place it where the app itself looks, so the Playground has something to run.
    APP_DEST="/sdcard/Android/data/dev.taracore/files/models"
    adb shell "mkdir -p '$APP_DEST'" 2>/dev/null && adb push "$OUT" "$APP_DEST/$NAME" || true

    echo "done. run: ./gradlew :engine:connectedCpuDebugAndroidTest"
fi
