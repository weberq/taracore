# Store assets

Built by `regenerate.py` from the raw device captures in `raw/`, and validated
against Play's rules before being committed.

| File | Size | Where it goes |
|---|---|---|
| `store-icon-512.png` | 512×512 | Store listing → App icon |
| `feature-graphic-1024x500.png` | 1024×500 | Store listing → Feature graphic |
| `phone/01…05` | 1080×1920 | Store listing → Phone screenshots |
| `tablet7/` | 1200×1920 | 7-inch tablet screenshots |
| `tablet10/` | 1600×2560 | 10-inch tablet screenshots |

## Why the screenshots are framed

Play rejects any image whose long side is more than **twice** its short side. A raw
capture from a modern phone is 1080×2424 — a ratio of 2.24 — so raw screenshots
cannot be uploaded at all. Each one here sits on a compliant canvas with a caption,
which satisfies the rule and reads better in the listing than a bare screenshot.

The tablet sets are the same phone captures on tablet-sized canvases. That is the
normal approach for a phone-first app, and supplying them stops Play showing tablet
users a "not designed for your device" warning.

## Regenerating

Recapture the raw screens with the app in the state you want, then:

```bash
cd docs/release/store-assets
python3 regenerate.py
```

It needs ImageMagick and Roboto (`fonts-roboto`). Captions live at the bottom of the
script, in `SCREENS`.

Recapture with `adb`:

```bash
adb exec-out screencap -p > raw/playground.png
```

Use the **recommended model** (Qwen2.5 1.5B) rather than the smallest, and make sure
the conversation on screen is one you would be happy for a stranger to read. The
numbers in these captures are real, from a Pixel 9a.
