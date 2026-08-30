#!/usr/bin/env bash
# adb-driven benchmark: load a model, run three prompts, report tokens/s.
#
# Talks to the HTTP surface rather than AIDL because that is the only interface
# reachable from a shell. Enable the server in Settings first and pass its token.
set -euo pipefail

PORT=8080
TOKEN="${TARACORE_TOKEN:-}"
MODEL=""
RUNS=3
KEEP_FORWARD=0

usage() {
    cat <<EOF
usage: $0 --model MODEL_ID [--token TOKEN] [--port PORT] [--runs N]

  --model   model id as reported by GET /v1/models (required)
  --token   bearer token from Settings; or set TARACORE_TOKEN
  --port    device port the server listens on (default 8080)
  --runs    prompts to run (default 3)

Enable the HTTP server in Tara Core -> Settings before running this.
EOF
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --model) MODEL="$2"; shift 2 ;;
        --token) TOKEN="$2"; shift 2 ;;
        --port)  PORT="$2"; shift 2 ;;
        --runs)  RUNS="$2"; shift 2 ;;
        --keep-forward) KEEP_FORWARD=1; shift ;;
        -h|--help) usage; exit 0 ;;
        *) echo "unknown argument: $1" >&2; usage; exit 2 ;;
    esac
done

[[ -n "$MODEL" ]] || { echo "error: --model is required" >&2; usage; exit 2; }
command -v adb >/dev/null || { echo "error: adb not on PATH" >&2; exit 1; }
command -v jq  >/dev/null || { echo "error: jq not on PATH (apt install jq)" >&2; exit 1; }
adb get-state >/dev/null 2>&1 || { echo "error: no device connected" >&2; exit 1; }

BASE="http://127.0.0.1:$PORT"
echo "forwarding tcp:$PORT -> device tcp:$PORT"
adb forward "tcp:$PORT" "tcp:$PORT" >/dev/null
cleanup() { [[ "$KEEP_FORWARD" -eq 1 ]] || adb forward --remove "tcp:$PORT" >/dev/null 2>&1 || true; }
trap cleanup EXIT

AUTH=()
[[ -n "$TOKEN" ]] && AUTH=(-H "Authorization: Bearer $TOKEN")

echo
echo "== device =="
adb shell getprop ro.product.model | tr -d '\r' | sed 's/^/  model:  /'
adb shell getprop ro.soc.model 2>/dev/null | tr -d '\r' | sed 's/^/  soc:    /'
adb shell "cat /proc/cpuinfo | grep -c ^processor" | tr -d '\r' | sed 's/^/  cpus:   /'
adb shell "grep MemTotal /proc/meminfo" | tr -d '\r' | sed 's/^/  /'

echo
echo "== health =="
HEALTH="$(curl -fsS "$BASE/health")" || { echo "error: server not reachable. Enabled in Settings?" >&2; exit 1; }
echo "$HEALTH" | jq -c .
BACKEND="$(echo "$HEALTH" | jq -r '.backend // "unknown"')"

PROMPTS=(
  "Explain what a Kalman filter does, in two sentences."
  "Write a haiku about a phone that thinks."
  "List three reasons to run a language model on-device."
)

echo
printf "== %s | backend=%s | %d runs ==\n" "$MODEL" "$BACKEND" "$RUNS"
printf "%-4s %10s %10s %12s %12s\n" "run" "prompt_tok" "gen_tok" "prompt_t/s" "gen_t/s"

total_gen_tps=0
for ((i = 0; i < RUNS; i++)); do
    P="${PROMPTS[$((i % ${#PROMPTS[@]}))]}"
    REQ="$(jq -nc --arg m "$MODEL" --arg p "$P" \
        '{model:$m, messages:[{role:"user",content:$p}], max_tokens:128, temperature:0.7}')"

    START="$(date +%s%N)"
    RESP="$(curl -fsS "${AUTH[@]}" -H 'Content-Type: application/json' \
        -d "$REQ" "$BASE/v1/chat/completions")" || { echo "request failed" >&2; exit 1; }
    END="$(date +%s%N)"

    WALL_MS=$(( (END - START) / 1000000 ))
    PT="$(echo "$RESP" | jq -r '.usage.prompt_tokens')"
    GT="$(echo "$RESP" | jq -r '.usage.completion_tokens')"
    # timings is a Tara Core extension to the OpenAI response; fall back to wall time.
    PMS="$(echo "$RESP" | jq -r '.timings.prompt_ms // 0')"
    GMS="$(echo "$RESP" | jq -r '.timings.generation_ms // 0')"
    [[ "$GMS" == "0" ]] && GMS="$WALL_MS"

    PTPS="$(awk -v t="$PT" -v ms="$PMS" 'BEGIN{printf "%.1f", ms>0 ? t*1000/ms : 0}')"
    GTPS="$(awk -v t="$GT" -v ms="$GMS" 'BEGIN{printf "%.1f", ms>0 ? t*1000/ms : 0}')"
    total_gen_tps="$(awk -v a="$total_gen_tps" -v b="$GTPS" 'BEGIN{print a+b}')"

    printf "%-4d %10s %10s %12s %12s\n" "$((i+1))" "$PT" "$GT" "$PTPS" "$GTPS"
done

echo
awk -v t="$total_gen_tps" -v n="$RUNS" -v b="$BACKEND" \
    'BEGIN{printf "mean generation throughput: %.1f tok/s on %s\n", t/n, b}'
echo
echo "To compare backends, install the other flavour and re-run:"
echo "  ./gradlew :app:installGpuDebug && $0 --model $MODEL --token \$TARACORE_TOKEN"
