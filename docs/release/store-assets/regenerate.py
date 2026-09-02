#!/usr/bin/env python3
"""Compose Play Store assets from raw device screenshots.

Play rejects any image whose long side is more than twice its short side. A modern
phone screenshot is 1080x2424, a ratio of 2.24, so the raw captures cannot be
uploaded as they are -- every one of them has to sit on a canvas that complies.
"""
import subprocess, pathlib

RAW = pathlib.Path("raw")
OUT = pathlib.Path("out")
OUT.mkdir(exist_ok=True)

BOLD = "/usr/share/fonts/truetype/roboto/unhinted/RobotoTTF/Roboto-Bold.ttf"
REG  = "/usr/share/fonts/truetype/roboto/unhinted/RobotoTTF/Roboto-Regular.ttf"
MED  = "/usr/share/fonts/truetype/roboto/unhinted/RobotoTTF/Roboto-Medium.ttf"

INK      = "#F2F1F7"
MUTED    = "#9DA1BC"
GOLD     = "#F5C542"
TOP      = "#171A30"
BOTTOM   = "#252A4C"

def run(*a):
    subprocess.run([str(x) for x in a], check=True)

def background(w, h, path):
    """Indigo gradient with a soft gold bloom in the upper right."""
    run("convert", "-size", f"{w}x{h}", f"gradient:{TOP}-{BOTTOM}", path)
    glow = f"/tmp/_glow_{w}x{h}.png"
    run("convert", "-size", f"{w}x{h}", "xc:none",
        "-fill", "#F5C54222", "-draw",
        f"circle {int(w*0.86)},{int(h*0.10)} {int(w*0.86)},{int(h*0.10)+int(w*0.42)}",
        "-blur", "0x90", glow)
    run("convert", path, glow, "-compose", "screen", "-composite", path)

def rounded(src, dst, width, radius):
    """Scale a screenshot to `width` and round its corners."""
    run("convert", src, "-resize", f"{width}x", dst)
    out = subprocess.run(["identify", "-format", "%h", dst],
                         capture_output=True, text=True, check=True)
    h = int(out.stdout)
    mask = "/tmp/_mask.png"
    run("convert", "-size", f"{width}x{h}", "xc:none", "-fill", "white",
        "-draw", f"roundrectangle 0,0 {width-1},{h-1} {radius},{radius}", mask)
    run("convert", dst, mask, "-alpha", "off", "-compose", "CopyOpacity",
        "-composite", dst)
    return h

def caption(canvas, w, headline, sub, y, size_h, size_s, pad):
    run("convert", canvas,
        "-font", BOLD, "-pointsize", size_h, "-fill", INK,
        "-annotate", f"+{pad}+{y}", headline,
        "-font", REG, "-pointsize", size_s, "-fill", MUTED,
        "-annotate", f"+{pad}+{y + int(size_h * 0.95)}", sub,
        canvas)

def shot(name, src, headline, sub, w, h, shot_w, shot_y, pad, size_h, size_s, radius):
    dst = OUT / name
    background(w, h, dst)
    dev = "/tmp/_dev.png"
    rounded(RAW / src, dev, shot_w, radius)
    # A hairline gold edge separates the dark UI from the dark background.
    run("convert", dev, "-bordercolor", "#F5C54233", "-border", "1", dev)
    caption(dst, w, headline, sub, pad + int(size_h * 0.9), size_h, size_s, pad)
    run("composite", "-gravity", "north", "-geometry", f"+0+{shot_y}", dev, dst, dst)
    run("convert", dst, "-strip", "-quality", "95", dst)
    print("  ", name)

SCREENS = [
    ("01-playground.png", "playground.png",
     "AI that runs on your phone",
     "No account, no internet, nothing sent anywhere."),
    ("02-models.png", "models.png",
     "Pick a model that fits",
     "See what each one needs before you download it."),
    ("03-dashboard.png", "dashboard.png",
     "Know what it is costing you",
     "Memory, speed and status, at a glance."),
    ("04-settings.png", "settings.png",
     "It gets out of your way",
     "Frees your memory when you are not using it."),
    ("05-server.png", "settings2.png",
     "Shared by all your apps",
     "One engine on your phone, not one per app."),
]

print("phone 1080x1920")
for name, src, hl, sub in SCREENS:
    shot(name, src, hl, sub, 1080, 1920, 640, 430, 80, 62, 34, 34)

print("7-inch tablet 1200x1920")
for name, src, hl, sub in SCREENS:
    shot("tab7-" + name, src, hl, sub, 1200, 1920, 620, 440, 96, 60, 33, 34)

print("10-inch tablet 1600x2560")
for name, src, hl, sub in SCREENS:
    shot("tab10-" + name, src, hl, sub, 1600, 2560, 840, 600, 128, 82, 44, 44)
