# Brand

## Tara Core

**Pronunciation:** *TAH-rah core* (`/ˈtɑː.rɑː/`) — two even syllables, a long open
first vowel. Not *TARE-uh*.

**Tagline:** One star every app steers by — on-device LLM inference for the whole phone.

## The name

**तारा** (*tārā*) is Sanskrit for **star**. The same word names **Tārā**, the
bodhisattva of guidance and safe crossing — in the traditional reading, the one who
ferries travellers over water they could not cross alone.

Both senses are load-bearing.

A star is the thing you navigate *by*, not the thing you arrive at. Tara Core is not
an app anyone opens to get work done; it is a fixed point other apps orient
themselves against. One engine, one copy of the weights, one place the model lives —
and every app on the device takes its bearing from it instead of shipping a private
2 GB copy and a private inference loop.

The crossing sense covers the rest. Getting a language model from a file on disk to a
token on screen is genuinely awkward on a phone: memory ceilings, thermal limits,
foreground-service policy, quantisation formats, a GPU driver that may or may not
work. Tara Core carries applications across that, and the crossing is meant to be
uneventful.

**Core** is the plain half of the name, and deliberately so. It says *shared
infrastructure*: a system component with an interface, not a product with a face. The
apps built on top are what users see. This is what they stand on.

## Usage

- Written **Tara Core**, two words, both capitalised. Never *TaraCore* in prose;
  `TaraCore` is only an identifier.
- Namespace and identifiers: `dev.taracore`.
## The mark

A four-point star inside a processor.

The chip silhouette is borrowed deliberately from the visual language Android already
uses for system-level AI — an outlined processor with something at its centre. Tara
Core occupies that same slot: not an app you open, but a component other apps build
on, and looking like one is useful information rather than imitation. What sits inside
the chip is ours: the four-point star, in gold rather than the blue-to-lavender that
convention runs to.

**Colours.** The star and chip carry a gold gradient, `#FFE9A8 → #F5C542 → #E39A12` on
a 45° diagonal, on the brand indigo `#1B1F3B`. A flat gold would have been fine; the
gradient is there because a star should catch the light somewhere.

**Construction.** 108 dp canvas, everything inside the 72 dp safe zone so no launcher
mask clips a pin. Two pins per side, not three: at the 48 dp a launcher actually draws,
three merge into a smear. The star has concave sides — a straight-edged four-point star
reads as a diamond once it is small, and the pinch is what makes it a star.

**Where it does not apply.** The notification icon stays the plain star. The system
flattens it to a single colour at 24 dp, where a chip outline turns to mush, so brand
consistency there would cost legibility.

The source is `docs/brand/launcher-icon.svg`; `docs/brand/icon-variants.png` keeps the
three options that were considered. Vector only, no third-party artwork.
