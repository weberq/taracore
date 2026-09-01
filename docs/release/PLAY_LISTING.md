# Play Store listing

Everything needed for the Play Console entry, in the form the console asks for it.

## App details

**Title** (30 char max)
```
Tara Core
```

**Short description** (80 char max — 79 used)
```
Run AI language models on your phone. Offline, private, shared by all your apps.
```

**Full description** (4000 char max)

```
Tara Core runs large language models directly on your phone. Nothing you type is
sent anywhere — there is no account, no server, and no internet connection required
once a model is downloaded.

WHAT MAKES IT DIFFERENT

Most on-device AI apps bundle their own engine and their own copy of the model. Three
such apps means three engines and three multi-gigabyte downloads. Tara Core is
installed once and shared: any app on your device can use it, so they each ship
nothing.

FEATURES

• Runs GGUF models through llama.cpp, optimised for arm64 phones
• Download models from a curated catalogue — Gemma, Qwen, Llama, Phi, SmolLM
• A chat playground to try any model straight away
• An OpenAI-compatible server on localhost, so existing tools work unchanged
• Constrained output: force answers into a fixed set of options or a JSON shape
• Frees the model's memory when idle, so it costs nothing while you are not using it
• A home screen widget showing what is loaded and how fast it is running

FOR DEVELOPERS

Add local inference to your app in three lines, without shipping an engine or model:

    val client = TaraCoreClient(context).apply { connect() }
    client.chatStream(listOf(ChatMessageParcel("user", "Hello")))
        .collect { piece -> print(piece) }

Or point any OpenAI client at http://127.0.0.1:8080/v1 — the Python openai package
works unmodified.

PRIVACY

Prompts and responses are never stored and never transmitted. The only network
requests the app makes are model downloads you start, and an optional update check
that sends nothing about you. The local server binds 127.0.0.1 only and is protected
by a token generated on your device.

WHAT YOU NEED

• A 64-bit arm phone (any phone since roughly 2018)
• 4 GB of RAM for small models; 8 GB is comfortable
• Storage for the models you choose — from 100 MB to several GB

A NOTE ON SPEED

Everything runs on your phone's CPU, so bigger models are slower. A 0.5B model
answers at conversational speed on a modern phone; a 7B model works but is closer to
reading speed. The app shows you the estimated memory each model needs before you
download it.

Open source, Apache-2.0: https://github.com/weberq/taracore
```

## Categorisation

- **App category:** Tools
- **Tags:** Developer tools, Productivity, Utilities
- **Content rating:** Everyone *(see the questionnaire notes below)*

## Content rating questionnaire

The one that needs care is **user-generated content**. Answer honestly:

> Does your app allow users to generate content? — **Yes.** Model output is generated
> locally in response to what the user types. It is not shared with other users, not
> transmitted, and not stored. There is no social feature, no moderation surface, and
> no way for one user's content to reach another.

This normally lands at **Everyone** or **Teen**. Do not claim there is no generated
content; a language model app plainly has some.

## Data safety form

| Question | Answer |
|---|---|
| Does your app collect or share any user data? | **No** |
| Is all user data encrypted in transit? | N/A — no user data is transmitted |
| Do you provide a way to request data deletion? | N/A — uninstalling removes everything |

Nothing is collected. No SDK in the app collects anything: the dependency list is
AndroidX, Kotlin, Ktor and OkHttp, none of which phone home.

Be ready to justify `INTERNET` in review: it is for model downloads and the optional
update check, both user-initiated, neither carrying user data.

## Foreground service declaration — the one that gets rejected

Play reviews `specialUse` by hand and rejects vague answers. Submit this:

> Tara Core hosts a large language model in memory and serves inference requests from
> other applications on the device, through a bound AIDL service and a loopback HTTP
> server. The work is initiated by a user of another app, continues while that app is
> in the foreground and Tara Core is not, and takes from seconds to minutes per
> request.
>
> No existing foreground service type describes hosting a shared inference engine for
> other applications. `dataSync` covers transfers that complete; `shortService` is
> capped at three minutes, which is shorter than a single generation on a large model;
> the remaining types describe specific hardware or user-visible activities.
>
> The service is foreground only while a model is loading, a request is being answered,
> or the user has explicitly enabled the local HTTP server. It leaves the foreground
> as soon as that work ends, and the model is unloaded after an idle period that
> defaults to five minutes.

That last paragraph is the one that matters, and it is true — see
`foregroundIsJustified()` in `TaraCoreService`.

## Graphic assets

| Asset | Spec | Status |
|---|---|---|
| App icon | 512×512 PNG, 32-bit | Export from `ic_launcher_foreground` on `#1B1F3B` |
| Feature graphic | 1024×500 PNG | Needed — star mark on indigo, tagline |
| Phone screenshots | 2–8, min 320px, 16:9 or 9:16 | Dashboard, Models, Playground, Settings |
| Tablet screenshots | Optional | Skip for the first release |

Screenshots can be captured from the running app:

```bash
adb exec-out screencap -p > dashboard.png
```

## Release checklist

- [ ] `versionCode` incremented (Play rejects a re-used one)
- [ ] `./gradlew :app:bundleCpuRelease` produces a signed `.aab`
- [ ] Upload key backed up somewhere other than this machine
- [ ] Play App Signing enrolled
- [ ] Native debug symbols uploaded (produced automatically by `debugSymbolLevel`)
- [ ] Privacy policy URL live and reachable
- [ ] `specialUse` declaration submitted with the wording above
- [ ] Internal testing track first; production only after a real install
