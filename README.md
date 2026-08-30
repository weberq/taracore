# Tara Core

**One star every app steers by — on-device LLM inference for the whole phone.**

Tara Core runs GGUF language models on Android through `llama.cpp` and lends that
engine to every other app on the device — over a bound AIDL service, or over an
OpenAI-compatible HTTP server on `127.0.0.1`.

One engine. One copy of the weights. Every app takes its bearing from it.

तारा (*tārā*) is Sanskrit for **star**, and names Tārā, the goddess of guidance and
safe crossing. See [docs/BRAND.md](docs/BRAND.md).

---

## Why

Today every Android app that wants a local model ships its own copy of `llama.cpp`
and its own 2 GB of weights. Three such apps mean three engines, three sets of
weights, and three teams solving foreground-service policy, thermal throttling and
quantisation formats independently.

Tara Core is the other arrangement: one system component that holds the model, and a
contract other apps call. Install it once; every app that speaks the contract gets
local inference with no engine of its own and no weights in its APK.

## Architecture

```mermaid
graph TB
    subgraph other["Other apps on the device"]
        A1["Your app<br/>(Kotlin, :client-sdk)"]
        A2["Java app<br/>(:api AAR)"]
        A3["Flutter / RN / Python<br/>(OpenAI HTTP)"]
    end

    subgraph tara["Tara Core"]
        subgraph uiproc["Process: main"]
            UI[":app<br/>Models · Dashboard · Playground · Settings"]
        end

        subgraph engproc["Process: :engine"]
            SVC[":service<br/>TaraCoreService"]
            Q["RequestQueue<br/>FIFO, one worker"]
            IDLE["IdleUnloader<br/>+ onTrimMemory"]
            HTTP["HttpServer<br/>Ktor CIO · 127.0.0.1"]
            REPO["ModelRepository<br/>Room + WorkManager"]
            ENG[":engine<br/>EngineController"]
            JNI["libtaracore_jni.so"]
            LLAMA["llama.cpp + ggml<br/>(static, submodule)"]
        end
    end

    A1 -->|"AIDL: ITaraCore"| SVC
    A2 -->|"AIDL: ITaraCore"| SVC
    A3 -->|"POST /v1/chat/completions"| HTTP

    UI -->|"via :client-sdk, the public path"| SVC
    HTTP --> Q
    SVC --> Q
    Q --> ENG
    SVC --> IDLE
    SVC --> REPO
    IDLE --> ENG
    ENG --> JNI
    JNI --> LLAMA
    REPO -.->|"GGUF files"| JNI
```

The UI and the engine live in **separate processes**. A native OOM while mapping
several gigabytes takes down the engine, not the interface, and the UI's own heap is
never counted against the model's footprint.

## Quick start

```bash
git clone --recurse-submodules <repo-url> tara-core
cd tara-core
./gradlew assembleCpuDebug
./gradlew :app:installCpuDebug
```

Open Tara Core, pick a model on the **Models** tab, tap **Set active**, and ask it
something on **Playground**. Full toolchain requirements are in
[docs/SETUP.md](docs/SETUP.md).

## Integrating, in three lines

```kotlin
val client = TaraCoreClient(context).apply { connect() }
client.chatStream(listOf(ChatMessageParcel("user", "Hello")))
      .collect { piece -> print(piece) }
```

Plus one line in your manifest:

```xml
<uses-permission android:name="dev.taracore.permission.BIND_INFERENCE" />
```

Java, Flutter and `curl` equivalents are in
[docs/INTEGRATION.md](docs/INTEGRATION.md).

Or skip the SDK entirely and point an OpenAI client at the loopback server:

```python
from openai import OpenAI
client = OpenAI(base_url="http://127.0.0.1:8080/v1", api_key="<token from Settings>")
```

## Security model

Three independent controls, because none of them is sufficient alone:

**A custom permission.** `dev.taracore.permission.BIND_INFERENCE`, `protectionLevel`
`normal`, granted at install time and visible on the app's details page. It gates
`bindService`, and the service re-checks it inside every method — a client can pass
its live binder to a third app, and we do not want to honour that silently.

**Loopback only.** The HTTP server binds `127.0.0.1` and rejects any non-loopback
remote address before routing. It never listens on a network interface.

**A bearer token.** On by default, 32 random bytes generated on first run, compared
in constant time. This is the control that actually matters for HTTP: *any* app on
Android can open a socket to `127.0.0.1` holding no permission at all, so the bind
address is not a boundary. The token is.

Beyond that: nothing leaves the device. Tara Core makes exactly one kind of outbound
request — downloading a model you asked for. No prompt, no completion, and no
telemetry is ever sent anywhere.

## Roadmap

- **Multi-sequence batching** — several clients sharing one context through
  `n_seq_max > 1`, instead of queueing. The largest available throughput win.
- **`/v1/embeddings`** — the pooling paths already exist in `llama.cpp`; this is
  mostly an endpoint and a context flag.
- **Tool calling** — `tools` / `tool_choice` on the chat endpoint, with grammar-
  constrained decoding so a model that has never seen a schema still emits valid JSON.
- **LoRA adapters** — `llama_adapter_lora` lets one base model serve several
  fine-tunes at a few megabytes each, rather than a full model per task.
- **LiteRT backend** — an alternative to `llama.cpp` reaching the NPU on devices that
  expose one, where the gap over CPU is an order of magnitude rather than a factor.

## Licence

Apache-2.0. `llama.cpp` is MIT; see [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

Tara Core ships **no model weights**. Models are downloaded at the user's request and
carry their own licences — Apache-2.0, MIT, the Gemma Terms of Use, the Llama 3.2
Community License — recorded per entry in the catalog and shown in the UI. Those
agreements are between the user and the model's publisher.
