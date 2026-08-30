# Integrating with Tara Core

Copy-paste snippets for the four ways in. Every one of them assumes Tara Core is
installed on the device; nothing here bundles a model or an engine.

---

## 1. Kotlin, over AIDL (recommended for Android apps)

### Gradle

```kotlin
dependencies {
    implementation("dev.taracore:client-sdk:1.0.0")
}
```

The SDK brings `dev.taracore:api` with it, and its manifest supplies the `<queries>`
entry for you.

### Manifest

```xml
<manifest>
    <!-- Granted at install time; protectionLevel is "normal". -->
    <uses-permission android:name="dev.taracore.permission.BIND_INFERENCE" />

    <!--
      Android 11+ package visibility. Supplied by :client-sdk's own manifest, so you
      only need this if you vendored the AIDL instead of taking the SDK.
    -->
    <queries>
        <package android:name="dev.taracore" />
    </queries>
</manifest>
```

Leaving out the `<queries>` entry does not throw: `bindService` returns `false` and
`getApplicationInfo` reports the package as missing, so the failure looks exactly
like "Tara Core is not installed". If a device that definitely has Tara Core reports
it as absent, this is why.

### Code

```kotlin
import dev.taracore.api.ChatMessageParcel
import dev.taracore.client.ChatParams
import dev.taracore.client.TaraCore
import dev.taracore.client.TaraCoreClient

class Assistant(private val context: Context) {

    private val client = TaraCoreClient(context)

    suspend fun start() {
        if (!TaraCore.isInstalled(context)) {
            // Tell the user why before you send them anywhere.
            context.startActivity(TaraCore.installIntent())
            return
        }
        client.connect()
    }

    // Streaming: cancelling the collector cancels generation on the service.
    fun ask(question: String): Flow<String> = client.chatStream(
        messages = listOf(
            ChatMessageParcel("system", "You are concise."),
            ChatMessageParcel("user", question),
        ),
        params = ChatParams(maxTokens = 256, temperature = 0.7f),
    )

    // Blocking. Never call this from the main thread.
    suspend fun askOnce(question: String): String = client.chat(
        listOf(ChatMessageParcel("user", question))
    )

    fun stop() = client.close()
}
```

Collecting inside a lifecycle scope is enough to get cancellation right:

```kotlin
lifecycleScope.launch {
    repeatOnLifecycle(Lifecycle.State.STARTED) {
        assistant.ask("Summarise this page").collect { piece -> append(piece) }
    }
}
```

When the user navigates away, the collector is cancelled, the SDK calls `cancel()` on
the service, and generation stops within one token. Nothing keeps running in the
background burning battery on an answer nobody will read.

### Errors worth handling

| Exception | Means |
|---|---|
| `ServiceNotInstalledException` | Tara Core is absent or disabled, or your `<queries>` entry is missing. |
| `SecurityException` | Your manifest is missing `BIND_INFERENCE`. |
| `ServiceDisconnectedException` | The service died mid-call. Reconnect and retry. |
| `InferenceException` | The engine failed. `code` is a `TaraCoreErrors` constant. |

---

## 2. Plain Java, over AIDL

No Kotlin, no coroutines, no SDK — just the `:api` AAR.

```java
public class TaraCoreBridge {

    private ITaraCore service;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ITaraCore.Stub.asInterface(binder);
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            service = null;
        }
    };

    public void bind(Context context) {
        Intent intent = new Intent(TaraCoreContract.ACTION_BIND);
        // An implicit service intent is illegal on Android 5+; the package is required.
        intent.setPackage(TaraCoreContract.SERVICE_PACKAGE);
        context.bindService(intent, connection, Context.BIND_AUTO_CREATE);
    }

    public String ask(String question) throws RemoteException {
        List<ChatMessageParcel> messages = new ArrayList<>();
        messages.add(new ChatMessageParcel("user", question));

        GenerationRequest request = new GenerationRequest(
                messages, null, 256, 0.7f, 0.95f, 40, 1.1f,
                Collections.emptyList(), -1L, null, true);

        // Blocking: run this on a background thread, never on the main thread.
        GenerationResult result = service.generate(request);
        if (result.isError()) {
            throw new IllegalStateException(result.errorMessage);
        }
        return result.text;
    }

    public String askStreaming(String question, final TokenSink sink) throws RemoteException {
        List<ChatMessageParcel> messages = new ArrayList<>();
        messages.add(new ChatMessageParcel("user", question));

        GenerationRequest request = new GenerationRequest(
                messages, null, 256, 0.7f, 0.95f, 40, 1.1f,
                Collections.emptyList(), -1L, null, true);

        return service.startStream(request, new ITokenCallback.Stub() {
            @Override public void onToken(String id, String piece) { sink.onPiece(piece); }
            @Override public void onDone(String id, GenerationResult r) { sink.onDone(r.text); }
            @Override public void onError(String id, int code, String message) {
                sink.onError(code, message);
            }
        });
    }
}
```

`startStream` returns the request id; pass it to `service.cancel(id)` to stop.

---

## 3. Flutter (or anything else), over HTTP

Flutter cannot bind a Binder interface without a platform channel, so use the HTTP
surface. Enable it in **Tara Core → Settings** and copy the token.

### Android manifest of your Flutter app

Cleartext is blocked by default since Android 9, and the loopback server is plain
HTTP. Allow it for `127.0.0.1` only, so every other destination keeps the default
protection:

`android/app/src/main/res/xml/network_security_config.xml`

```xml
<network-security-config>
    <domain-config cleartextTrafficPermitted="true">
        <domain includeSubdomains="false">127.0.0.1</domain>
        <domain includeSubdomains="false">localhost</domain>
    </domain-config>
</network-security-config>
```

`android/app/src/main/AndroidManifest.xml`

```xml
<application android:networkSecurityConfig="@xml/network_security_config" ...>
```

A TLS certificate would not help here: it would be either self-signed (so every
client must be told to trust it) or shipped with its private key (so it is not a
secret). Loopback traffic never leaves the device — the token is the control that
matters.

### Dart

```dart
import 'dart:convert';
import 'package:http/http.dart' as http;

const _base = 'http://127.0.0.1:8080';

Future<String> ask(String question, String token) async {
  final response = await http.post(
    Uri.parse('$_base/v1/chat/completions'),
    headers: {
      'Content-Type': 'application/json',
      'Authorization': 'Bearer $token',
    },
    body: jsonEncode({
      'model': 'qwen2.5-1.5b-instruct-q4km',
      'messages': [
        {'role': 'user', 'content': question}
      ],
      'max_tokens': 256,
    }),
  );

  if (response.statusCode != 200) {
    throw Exception('Tara Core returned ${response.statusCode}: ${response.body}');
  }
  // utf8.decode, not response.body: the answer may contain non-ASCII text and
  // http's default decoding assumes latin-1 when the charset is unstated.
  final json = jsonDecode(utf8.decode(response.bodyBytes));
  return json['choices'][0]['message']['content'] as String;
}

/// Streaming, so the UI can render tokens as they arrive.
Stream<String> askStream(String question, String token) async* {
  final request = http.Request('POST', Uri.parse('$_base/v1/chat/completions'))
    ..headers['Content-Type'] = 'application/json'
    ..headers['Authorization'] = 'Bearer $token'
    ..body = jsonEncode({
      'model': 'qwen2.5-1.5b-instruct-q4km',
      'messages': [
        {'role': 'user', 'content': question}
      ],
      'max_tokens': 256,
      'stream': true,
    });

  final response = await request.send();
  await for (final line in response.stream
      .transform(utf8.decoder)
      .transform(const LineSplitter())) {
    if (!line.startsWith('data:')) continue;
    final payload = line.substring(5).trim();
    if (payload == '[DONE]') return;

    final delta = jsonDecode(payload)['choices'][0]['delta']['content'];
    if (delta != null) yield delta as String;
  }
}
```

Check the server is up before prompting the user for a token — `/health` needs no
auth:

```dart
Future<bool> isAvailable() async {
  try {
    final r = await http.get(Uri.parse('$_base/health'))
        .timeout(const Duration(milliseconds: 500));
    return r.statusCode == 200;
  } catch (_) {
    return false;
  }
}
```

---

## 4. curl / shell

From a shell on the device (`adb shell`), or from a workstation after
`adb forward tcp:8080 tcp:8080`:

```bash
TOKEN='paste-from-settings'

# Is it up? No auth needed.
curl -s http://127.0.0.1:8080/health | jq .

# What can it run?
curl -s -H "Authorization: Bearer $TOKEN" \
     http://127.0.0.1:8080/v1/models | jq '.data[].id'

# One completion.
curl -s -H "Authorization: Bearer $TOKEN" \
     -H 'Content-Type: application/json' \
     -d '{
           "model": "qwen2.5-1.5b-instruct-q4km",
           "messages": [{"role": "user", "content": "Say hi in five words."}],
           "max_tokens": 32
         }' \
     http://127.0.0.1:8080/v1/chat/completions | jq -r '.choices[0].message.content'

# Streaming: -N disables curl's own buffering, without which SSE arrives all at once.
curl -N -s -H "Authorization: Bearer $TOKEN" \
     -H 'Content-Type: application/json' \
     -d '{
           "model": "qwen2.5-1.5b-instruct-q4km",
           "messages": [{"role": "user", "content": "Count to ten."}],
           "max_tokens": 64,
           "stream": true
         }' \
     http://127.0.0.1:8080/v1/chat/completions
```

---

## Choosing between them

| | AIDL | HTTP |
|---|---|---|
| Throughput | Same engine, so effectively identical | |
| Time to first token | Both in the low hundreds of milliseconds; see below | |
| Large prompts | Zero-copy over a pipe above 512 KB | Body size limited only by memory |
| Cancellation | Explicit by request id, stops within one token | Implicit on disconnect, also within one token |
| Permissions | `BIND_INFERENCE` | `INTERNET` plus a cleartext exception |
| Type safety | Compile-time, from the AIDL contract | Whatever your JSON client gives you |
| Works from | Android apps | Anything on the device |

**On latency, specifically:** it is tempting to assume AIDL wins, since a Binder
transaction is cheaper than an HTTP chunk. Measured on a Pixel 9a, the transport
difference is swamped by everything else — chiefly whether the prompt's prefix is
still in the KV cache. A back-to-back run of the same prompt gave AIDL 205 ms to first
token and HTTP 108 ms, because by then 19 of 20 prompt tokens were already resident
and the second request skipped the prompt eval entirely.

So do not choose on latency. Choose AIDL because you are writing an Android app and
want typed calls, explicit cancellation and zero-copy large prompts; choose HTTP
because your client is Flutter, React Native, Python, or anything else that already
speaks OpenAI. The `:sample-client` app runs both against the same prompt and prints
the numbers side by side, so you can measure it on your own hardware rather than
trust this section.
