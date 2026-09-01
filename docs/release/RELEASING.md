# Releasing

## One-time setup

### 1. Create an upload key

```bash
keytool -genkeypair -v \
  -keystore upload.jks \
  -alias taracore-upload \
  -keyalg RSA -keysize 4096 -validity 10000 \
  -storetype PKCS12
```

**Back `upload.jks` up somewhere other than this machine.** With Play App Signing you
can ask Google to reset a lost upload key, but it is a support round-trip; without it
you cannot ship an update at all.

### 2. Point the build at it

`keystore.properties` in the repository root — git-ignored, never committed:

```properties
storeFile=/absolute/path/to/upload.jks
storePassword=…
keyAlias=taracore-upload
keyPassword=…
```

The build also accepts `TARACORE_KEYSTORE`, `TARACORE_STORE_PASSWORD`,
`TARACORE_KEY_ALIAS` and `TARACORE_KEY_PASSWORD` from the environment, which is how
CI signs without a file on disk.

With neither present the release build still succeeds, **unsigned**. That is
deliberate: falling back to the debug key would produce an artefact that installs
fine locally and is rejected by Play, which is a slow and confusing way to find out.

### 3. GitHub Actions secrets

| Secret | Value |
|---|---|
| `KEYSTORE_BASE64` | `base64 -w0 upload.jks` |
| `KEYSTORE_PASSWORD` | store password |
| `KEY_ALIAS` | `taracore-upload` |
| `KEY_PASSWORD` | key password |

## Cutting a release

### 1. Bump the version

In `app/build.gradle.kts`:

```kotlin
versionCode = 2          // must increase; Play rejects a re-used value
versionName = "1.1.0"    // plain dotted digits: the update checker parses this
```

`versionCode` and `versionName` move together. The release workflow **fails the build**
if the tag does not match `versionName`, because shipping `v1.1.0` from a build that
calls itself `1.0.0` makes the in-app update check offer the same update forever.

### 2. Tag and push

```bash
git tag -a v1.1.0 -m "Tara Core 1.1.0"
git push origin v1.1.0
```

That triggers `.github/workflows/release.yml`, which runs the unit tests, builds a
signed APK and AAB, asserts 16 KB page alignment, generates a changelog from the
commits since the previous tag, and publishes a GitHub release with `SHA256SUMS.txt`.

### 3. Upload to Play

The workflow does not publish to Play — deliberately, because the first upload of any
version should be looked at by a person.

1. Download `tara-core-vX.Y.Z.aab` from the GitHub release.
2. Play Console → **Internal testing** → create a release → upload the `.aab`.
3. Upload `tara-core-vX.Y.Z-native-symbols.zip` under **App bundle explorer →
   Downloads → Native debug symbols**, so a crash inside `llama.cpp` is readable in
   Vitals rather than a hex address.
4. Install from the internal track on a real device and open it.
5. Promote to production.

Never go straight to production. This app ships a native engine; a packaging mistake
is invisible until the `.so` fails to load on someone else's phone.

## Local verification before tagging

```bash
./gradlew clean assembleCpuDebug testCpuDebugUnitTest :api:testDebugUnitTest
./gradlew :app:assembleCpuRelease          # R8 must survive; see proguard-rules.pro
./gradlew :engine:connectedCpuDebugAndroidTest   # needs a device
./gradlew :app:installCpuRelease           # then actually open it
```

**Install and open the release build.** R8 removes what it cannot see being used, and
this project is full of things it cannot see: JNI methods called by name from C++,
Parcelables reconstructed by the framework, Ktor engines loaded through
`ServiceLoader`. Those break at runtime, not at build time, and only in release.

## Versioning

Semantic versioning on the app. Separately, `TaraCoreContract.API_VERSION` versions
the AIDL contract and moves only when the contract gains something — it is at 3, while
the app is at 1.0.0. Clients check the contract version, not the app version.
