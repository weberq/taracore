# Tara Core API

Two ways in, one engine behind both:

| Surface | Transport | Best for |
|---|---|---|
| `ITaraCore` | AIDL / Binder | Android apps. Zero-copy for large prompts, cancellation, lifecycle-aware. |
| `POST /v1/chat/completions` | HTTP on `127.0.0.1` | Anything that already speaks OpenAI — Flutter, React Native, Python, a shell script. |

---

## 1. The AIDL contract

Package `dev.taracore.api`, distributed as the `dev.taracore:api` AAR.

### Binding

```kotlin
val intent = Intent(TaraCoreContract.ACTION_BIND).apply {
    setPackage(TaraCoreContract.SERVICE_PACKAGE)
}
context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
```

The caller must hold `dev.taracore.permission.BIND_INFERENCE` and declare a
`<queries>` entry for `dev.taracore` (Android 11+ package visibility). Both snippets
are in [INTEGRATION.md](INTEGRATION.md).

### Methods

#### `int getApiVersion()`

Contract version the **service** implements. Compare against
`TaraCoreContract.API_VERSION`, the version your copy of the AAR was compiled
against. A service reporting a *higher* number is safe to use — the contract is
append-only. A service reporting a *lower* number does not implement methods added
after that version; calling one throws.

#### `List<ModelInfo> listModels()`

Every registry entry, downloaded or not. `downloaded` tells you whether the file is
present, `loaded` whether it is the resident model. `estRamBytes` is the figure to
compare against `ActivityManager.MemoryInfo.availMem` before asking for a load — it
includes the KV cache and compute buffers, which `sizeBytes` does not.

#### `void loadModel(String modelId, IModelCallback cb)`

Asynchronous. Returns as soon as the load is queued; `cb.onProgress` fires with
`0.0f..1.0f`, then exactly one of `onLoaded` / `onError`. Loading replaces whatever
model was resident. Reading several GB from storage takes tens of seconds on a phone,
which is why this is not a blocking call.

Passing an id that is not downloaded fails with `MODEL_NOT_FOUND` — Tara Core will
not start a multi-gigabyte download on a client's behalf without the user seeing it.

#### `void unloadModel()`

Frees the model and its KV cache. Idempotent. The next generation reloads lazily
(subject to the caller's `allowAutoLoad`).

#### `ServiceStatus getStatus()`

In-memory snapshot: state, loaded model, backend, queue depth, last tokens/s, idle
countdown, HTTP server state. Cheap enough to poll at 1 Hz.

#### `GenerationResult generate(GenerationRequest req)`

Blocking. The calling thread is parked for the whole completion — hundreds of
milliseconds to minutes. **Never call it from the main thread.** Binder's thread pool
is 16 threads per process, so a handful of blocked callers can starve every other
transaction into that process; prefer `startStream` for anything interactive.

#### `String startStream(GenerationRequest req, ITokenCallback cb)`

Returns the request id immediately. Tokens arrive on `cb.onToken` (a `oneway` call,
so a slow client never stalls the sampling loop), then exactly one of `onDone` /
`onError`.

#### `void cancel(String requestId)`

Removes the request from the queue, or stops a running generation within one token.
A cancelled stream still ends with `onDone`, carrying the partial text and
`cancelled = true` — partial answers are worth keeping. Cancelling an id that is not
running is a no-op, so a late cancel cannot kill the next request.

### Large prompts

Binder's transaction buffer is **1 MB, shared across every transaction in flight in
the calling process**. A prompt near that size fails intermittently, depending on
what else your app is doing. So:

- Inline payload ≤ **512 KB** (`TaraCoreContract.INLINE_PROMPT_LIMIT_BYTES`): put it
  in `GenerationRequest.messages`.
- Larger: write the UTF-8 prompt into a pipe, pass the read end as
  `GenerationRequest.largePrompt`, and leave `messages` empty. The service reads it to
  EOF on a worker thread and uses the bytes as a **pre-rendered prompt** — no chat
  template is applied, because at that size you are almost certainly assembling the
  prompt yourself.

`:client-sdk` picks between the two automatically. Direct AIDL callers must choose.

### Compatibility policy

The order of methods in `ITaraCore.aidl` **is** the wire format: Binder dispatches on
the ordinal position of each method, not its name. Therefore:

- Never change an existing method's signature, argument order, or position.
- Never remove or reorder a method, even an unused one.
- Add capability by **appending** a method to the end of the interface and bumping
  `API_VERSION`.
- Parcelables may gain fields only at the **end** of `writeToParcel`, and readers must
  tolerate a short parcel (an older writer). New fields need defaults.

A client built against API v1 keeps working against a v5 service, and a v5 client
degrades gracefully against a v1 service by checking `getApiVersion()` first.

---

## 2. The HTTP surface

Ktor CIO, bound to `127.0.0.1` only, default port `8080`, off until enabled in
Settings. Remote addresses are rejected before routing, so a misconfigured bind can
never expose the engine to the network.

### Auth

Bearer token, **on by default**, generated on first run and copyable from Settings.
Loopback is not a permission boundary — any app on the device can reach `127.0.0.1`
without holding anything — so the token is what actually gates access.

```
Authorization: Bearer <token>
```

A missing or wrong token gets `401` with an OpenAI-shaped error body.

### `GET /health`

```json
{ "name": "tara-core", "status": "ok", "model": "qwen2.5-1.5b-instruct-q4km", "backend": "CPU" }
```

Unauthenticated: it exists so a client can find out whether the server is up before
prompting the user for a token.

### `GET /v1/models`

OpenAI `list` envelope over the downloaded models.

### `POST /v1/chat/completions`

Standard OpenAI request. Supported fields: `model`, `messages`, `max_tokens`,
`temperature`, `top_p`, `stop`, `seed`, `stream`.

`stream: false` returns a `chat.completion` with a `usage` block.
`stream: true` returns `text/event-stream` of `chat.completion.chunk` objects
terminated by `data: [DONE]`.

If `model` names a model other than the resident one, the service loads it when
auto-load is enabled (Settings, and per-request in the AIDL path); otherwise it
returns `404`.

### `POST /v1/completions`

The older prompt-based shape, for clients that never moved to chat. The prompt is
used verbatim — no chat template.

### Verifying with the unmodified OpenAI Python client

This is an acceptance test, not an illustration. On a device with the server enabled:

```bash
adb forward tcp:8080 tcp:8080
pip install openai
```

```python
from openai import OpenAI

client = OpenAI(base_url="http://127.0.0.1:8080/v1", api_key="<token from Settings>")

# 1. listing
print([m.id for m in client.models.list().data])

# 2. non-streaming
r = client.chat.completions.create(
    model="qwen2.5-1.5b-instruct-q4km",
    messages=[{"role": "user", "content": "Say hi in five words."}],
    max_tokens=32,
)
print(r.choices[0].message.content, r.usage)

# 3. streaming
for chunk in client.chat.completions.create(
    model="qwen2.5-1.5b-instruct-q4km",
    messages=[{"role": "user", "content": "Count to five."}],
    stream=True,
):
    print(chunk.choices[0].delta.content or "", end="", flush=True)
```

All three must succeed with no shim, no custom transport, and no changes to the
`openai` package. `adb forward` is only there because the laptop running Python is
not the device; on-device clients talk to `127.0.0.1` directly.
