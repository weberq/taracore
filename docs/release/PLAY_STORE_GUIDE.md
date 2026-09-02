# Publishing Tara Core to Google Play

Everything needed to get from this repository to a live listing, in the order the
Play Console asks for it. Assets are already built and validated in
`docs/release/store-assets/`.

---

## Before you start

| | |
|---|---|
| Package name | `dev.taracore` — **permanent once published**, it can never be changed |
| Version | 1.0.0 (versionCode 1) |
| Upload key | `~/Downloads/auth-keys/auth-keys/cashiflow/upload-keystore.jks`, alias `upload` |
| Certificate | `CN=WeberQ, OU=CashiFlow, O=WeberQ` |
| Cert SHA-256 | `1c943d08bd438479c53697f498433aabedfed4a51af93e799a68fe7c8af2631e` |
| Developer account | One-off $25 registration, and identity verification, before anything can be published |

### One thing to decide first

The upload key above is **Cashi Flow's**. Play allows one upload key across several
apps and many publishers do exactly that, so this works — but it is worth being
deliberate:

- If that key leaks, both apps need a key reset, not one.
- The certificate says `OU=CashiFlow`, which is cosmetically wrong here. Nobody sees
  it, but it is in the certificate for the life of the app.

A separate key costs one command and removes both points:

```bash
keytool -genkeypair -v -keystore taracore-upload.jks -alias upload \
  -keyalg RSA -keysize 4096 -validity 10000 -storetype PKCS12 \
  -dname "CN=WeberQ, OU=TaraCore, O=WeberQ Global Pvt. Ltd., C=IN"
```

Then point `keystore.properties` at it. With **Play App Signing** — which you should
enrol in — the upload key is only how you prove uploads are yours; Google holds the
real signing key. That makes changing your mind later cheap, but only before the
first upload.

Either way: **back the key up somewhere other than this laptop.**

---

## 1. Build the release

```bash
cd /home/pranay/projects/taracore
./gradlew clean :app:bundleCpuRelease
```

Produces `app/build/outputs/bundle/cpuRelease/app-cpu-release.aab` (~9.6 MB).

Signing is read from `keystore.properties`, which is git-ignored. If it is missing
the build still succeeds and produces an **unsigned** artefact — deliberately, since
a debug-signed "release" installs fine locally and is rejected by Play.

Verify before uploading:

```bash
# The APK, for sideloading and for GitHub Releases
./gradlew :app:assembleCpuRelease
$ANDROID_HOME/build-tools/35.0.0/apksigner verify --print-certs \
  app/build/outputs/apk/cpu/release/app-cpu-release.apk
```

Expect `Verified using v2 scheme: true` and `v3 scheme: true`. v1 is off on purpose:
minSdk 26 means every target device honours v2/v3, and v1 signatures carry the
zip-entry weaknesses.

**Install the release build on a real phone and open it.** R8 removes what it cannot
see being used, and this project is full of things it cannot see — JNI methods called
by name from C++, Parcelables the framework reconstructs, Ktor engines loaded through
`ServiceLoader`. Those break at runtime, in release only.

---

## 2. Create the app

Play Console → **Create app**.

| Field | Value |
|---|---|
| App name | `Tara Core` |
| Default language | English (United Kingdom) or (United States) |
| App or game | App |
| Free or paid | Free — **cannot be changed to paid later** |

Tick both declarations (Play policies, US export laws).

---

## 3. Store listing

Under **Grow → Store presence → Main store listing**.

### App name (30 characters)
```
Tara Core
```

### Short description (80 characters)
```
Run AI language models on your phone. Offline, private, shared by all your apps.
```

### Full description (4000 characters)
```
Tara Core runs large language models directly on your phone. Nothing you type is
sent anywhere. There is no account, no server, and no internet connection needed
once a model is downloaded.

WHAT MAKES IT DIFFERENT

Most on-device AI apps bundle their own engine and their own copy of the model.
Three such apps means three engines and three multi-gigabyte downloads. Tara Core
is installed once and shared: any app on your phone can use it, so they each ship
nothing.

WHAT YOU CAN DO

• Chat with a model straight away, offline
• Choose from a catalogue of open models: Gemma, Qwen, Llama, Phi, SmolLM
• See exactly how much memory each model needs before you download it
• Watch speed and memory use on the dashboard, or add the home screen widget
• Let other apps on your phone use the same engine

FOR DEVELOPERS

Add local AI to your app in three lines, without shipping an engine or a model:

    val client = TaraCoreClient(context).apply { connect() }
    client.chatStream(listOf(ChatMessageParcel("user", "Hello")))
        .collect { piece -> print(piece) }

Or point any OpenAI-compatible client at http://127.0.0.1:8080/v1 — the standard
Python openai package works unmodified. Constrained output is supported, so a
small model can be made to answer with exactly one of your options, or with JSON
in a shape you specify.

PRIVACY

Your conversations are never stored and never transmitted. The only network
requests the app makes are the model downloads you start, and an optional update
check that sends nothing about you. The local server listens on 127.0.0.1 only
and is protected by a key generated on your phone.

WHAT YOU NEED

• A 64-bit phone, which means almost any phone since 2018
• 4 GB of memory for small models; 8 GB is comfortable
• Storage for the models you choose, from 100 MB to several GB

A NOTE ON SPEED

Everything runs on your phone's processor, so larger models are slower. A small
model answers at conversation speed; a large one is closer to reading speed. The
app tells you what each model needs before you download it.

Open source, Apache 2.0: https://github.com/weberq/taracore
```

### Graphics

All in `docs/release/store-assets/`, already validated against Play's rules.

| Asset | File | Notes |
|---|---|---|
| App icon | `store-icon-512.png` | 512×512, required |
| Feature graphic | `feature-graphic-1024x500.png` | 1024×500, required |
| Phone screenshots | `phone/01…05` | 1080×1920, 2–8 allowed, all 5 recommended |
| 7-inch tablet | `tablet7/` | 1200×1920 |
| 10-inch tablet | `tablet10/` | 1600×2560 |

**Why the screenshots are framed rather than raw.** Play rejects any image whose long
side is more than twice its short side. A raw capture from a modern phone is
1080×2424 — a ratio of 2.24 — so it cannot be uploaded as-is. Every screenshot here
sits on a compliant canvas with a caption. `regenerate.py` rebuilds them from the raw
captures in `raw/`.

Tablet screenshots are the same phone captures on tablet-sized canvases. That is
normal for a phone-first app, and supplying them stops Play showing users the
"not designed for your device" warning.

---

## 4. Content rating

**Policy → App content → Content rating**. Answer the questionnaire honestly. The one
that needs care:

> **Does your app allow users to generate content?** — **Yes.**

It does: the model produces text in response to what the user types. Add in the free
text field:

> Content is generated locally on the user's own device in response to their own
> input. It is not shared with other users, not transmitted anywhere, and not stored.
> There is no social feature and no way for one user's content to reach another.

Do not claim there is none. A language model app plainly generates content, and a
rating obtained by a wrong answer can be revoked later.

Category: **Utility / Productivity**. Expect *Everyone* or *Teen*.

---

## 5. Data safety

**Policy → App content → Data safety**.

| Question | Answer |
|---|---|
| Does your app collect or share any user data? | **No** |
| Is data encrypted in transit? | Not applicable — no user data is transmitted |
| Can users request deletion? | Not applicable — uninstalling removes everything |

Nothing in the dependency list collects anything: AndroidX, Kotlin, Ktor and OkHttp
have no telemetry. Be ready to justify `INTERNET`, which is for model downloads and
the optional update check — both user-initiated, neither carrying user data.

Privacy policy URL:
```
https://github.com/weberq/taracore/blob/main/docs/release/PRIVACY.md
```

A GitHub URL is accepted. If you would rather have it on a weberq.com page, the text
is in `docs/release/PRIVACY.md` and can be published anywhere reachable.

---

## 6. The foreground service declaration

**This is the one that gets rejected**, and the one worth reading twice.

Tara Core declares `specialUse`, which Play reviews by hand. Under **App content →
Sensitive app permissions → Foreground service**, paste:

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
> The service is foreground only while a model is loading, a request is being
> answered, or the user has explicitly enabled the local HTTP server. It leaves the
> foreground as soon as that work ends, and the model is unloaded after an idle period
> that defaults to five minutes.

That last paragraph is what makes the case, and it is true — see
`foregroundIsJustified()` in `TaraCoreService.kt`. Reviewers reject vague answers, not
unusual ones.

You will also need a **short demo video** (an unlisted YouTube link) showing the
foreground service in use. Screen-record: opening the app, choosing a model, asking a
question, and the notification appearing while it answers and disappearing afterwards.

---

## 7. Upload and roll out

1. **Test and release → Testing → Internal testing → Create new release**
2. Enrol in **Play App Signing** when prompted. Say yes.
3. Upload `app-cpu-release.aab`
4. Upload native debug symbols — **App bundle explorer → Downloads → Native debug
   symbols**. Without them a crash inside `llama.cpp` is a hex address in Vitals
   rather than a stack trace. The release build produces them automatically
   (`debugSymbolLevel = "SYMBOL_TABLE"`).
5. Release notes: see `RELEASING.md`, or for the first release simply
   `First release of Tara Core.`
6. Add yourself as an internal tester, install **from the Play link**, and open it.
7. Promote: internal → closed → production. Do not skip to production. This app ships
   a native engine, and a packaging mistake is invisible until the `.so` fails to load
   on hardware you do not own.

Expect review to take a few days for a first submission, and longer with the
`specialUse` declaration attached.

---

## 8. After it is live

- Tag the release and let CI publish the GitHub artefacts: `git tag -a v1.0.0 && git push origin v1.0.0`
- The in-app update check disables itself automatically for Play installs, so Play
  users are never offered a sideload APK on top of Play's own updates.
- Watch **Quality → Android vitals** for native crashes in the first week.
- `versionCode` must increase on every upload. Play rejects a repeat, and
  `.github/workflows/release.yml` fails the build if the git tag and `versionName`
  disagree.

---

## Known review risks, in order of likelihood

1. **`specialUse` foreground service.** Mitigated by the wording above and by the app
   genuinely leaving the foreground when idle. Have the demo video ready.
2. **`INTERNET` with a "collects no data" declaration.** Consistent and true, but be
   ready to explain model downloads.
3. **Models are downloaded, not bundled.** Some reviewers query apps that fetch large
   payloads. The catalogue is fixed, in `assets/catalog.json`, and every entry names
   its licence — no arbitrary URLs, no user-supplied endpoints.
4. **Model output.** Tara Core does not filter what a model says. The content rating
   questionnaire is where this is disclosed; answer the generated-content question
   truthfully and it is not a problem.
