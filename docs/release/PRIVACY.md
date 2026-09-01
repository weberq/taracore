# Privacy Policy — Tara Core

*Last updated: 30 August 2026*

Tara Core is an on-device language model engine. This policy is short because the
app does very little that concerns your data.

## The short version

**Nothing you type, and nothing a model says, ever leaves your device.** There is no
account, no analytics, no crash reporting to us, no advertising identifier, and no
telemetry of any kind.

## What the app stores, and where

All of it stays in Tara Core's private storage on your device:

| What | Why | Removed when |
|---|---|---|
| Model files (GGUF) | To run models offline | You delete the model, or uninstall |
| Your settings | Context size, threads, port, timeouts | Uninstall |
| An API token | To stop other apps using the local HTTP server without permission | Uninstall, or you regenerate it |
| A list of which apps have used the engine | So you can see who is using your battery | Uninstall |

**Prompts and responses are not stored.** The Playground's conversation lives in
memory only and is gone when you close the app. Nothing is written to disk, and
nothing is logged to a file.

## Network access

Tara Core makes exactly two kinds of network request, both of which you control:

1. **Model downloads.** Only when you tap Download. The request goes to the model's
   host (Hugging Face by default) and contains no information about you beyond what
   any HTTP download requires.
2. **Update checks.** If enabled — it can be turned off in Settings, and is disabled
   automatically for installs from Google Play — the app asks
   `api.github.com/repos/weberq/taracore/releases/latest` once a day whether a newer
   version exists. This is an unauthenticated read of a public page. Your version,
   device, and identity are **not** sent; the comparison happens on your device using
   the answer.

There is no other outbound traffic. The app has no server.

## The local HTTP server

Tara Core can run an OpenAI-compatible server, off by default. When enabled it binds
`127.0.0.1` only — the loopback address — so it is not reachable from your network,
your Wi-Fi, or the internet. It is protected by a bearer token generated on your
device, because on Android any installed app can reach loopback without a permission.

## Other apps on your device

Apps holding the `dev.taracore.permission.BIND_INFERENCE` permission can send prompts
to the engine. Tara Core sees those prompts in order to answer them, and does not
store or transmit them. The Dashboard shows which apps have used the engine and how
much.

Those apps have their own privacy policies. What they do with an answer is between
you and them.

## Children

Tara Core is a developer and power-user tool with no content directed at children and
no data collection. Model outputs are generated on your device and are not filtered
or moderated by us.

## Permissions, and why each exists

| Permission | Why |
|---|---|
| `INTERNET` | Downloading models; the optional update check |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE` | Keeping the engine alive while it answers a request from a backgrounded app |
| `POST_NOTIFICATIONS` | Showing what the engine is doing while it works |
| `RECEIVE_BOOT_COMPLETED` | Only if you turn on "Start on boot" |
| `WAKE_LOCK` | Finishing an in-flight answer without the CPU sleeping mid-token |

## Changes

Material changes will be noted in the GitHub release notes and in this file's history.

## Contact

Issues and questions: <https://github.com/weberq/taracore/issues>
