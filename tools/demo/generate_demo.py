#!/usr/bin/env python3
"""Renders the onboarding demo clip shown on /start and /help.

Draws a Telegram-style chat frame by frame and pipes the frames into ffmpeg. Telegram plays a silent
H.264 MP4 sent via sendAnimation as a looping GIF, at a fraction of a real GIF's size.

    python3 -m venv .venv && .venv/bin/pip install pillow
    .venv/bin/python tools/demo/generate_demo.py

Writes src/main/resources/onboarding/demo.mp4 and docs/demo.gif (README preview).
Needs ffmpeg on PATH. Fonts default to macOS system fonts; override with DEMO_FONT / DEMO_EMOJI_FONT.
"""
import os
import subprocess
from dataclasses import dataclass, field
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[2]
MP4_OUT = ROOT / "src/main/resources/onboarding/demo.mp4"
GIF_OUT = ROOT / "docs/demo.gif"

W, H = 720, 900
FPS = 25
SCALE = 2  # supersampling for smooth edges

FONT_PATH = os.environ.get("DEMO_FONT", "/System/Library/Fonts/SFNS.ttf")
EMOJI_PATH = os.environ.get("DEMO_EMOJI_FONT", "/System/Library/Fonts/Apple Color Emoji.ttc")

# Telegram light theme
WALL_TOP, WALL_BOTTOM = (206, 226, 190), (163, 203, 196)
OUT_BUBBLE, IN_BUBBLE = (239, 253, 222), (255, 255, 255)
TEXT, MUTED, OUT_MUTED = (20, 20, 20), (140, 146, 152), (94, 170, 88)
BUTTON = (0, 0, 0, 42)
ACCENT = (51, 144, 236)


def font(size, weight="Regular"):
    f = ImageFont.truetype(FONT_PATH, size * SCALE)
    try:
        f.set_variation_by_name(weight)
    except Exception:
        pass
    return f


EMOJI_NATIVE = 160  # Apple Color Emoji is a bitmap font with fixed strikes
_emoji_font = ImageFont.truetype(EMOJI_PATH, EMOJI_NATIVE)
_emoji_cache = {}


TEXT_SYMBOLS = {0x2713, 0x2192}  # ✓ → live in the text font


def is_emoji(ch):
    cp = ord(ch)
    if cp in TEXT_SYMBOLS:
        return False
    return (cp >= 0x1F000 or 0x2600 <= cp <= 0x27BF or 0x2300 <= cp <= 0x23FF
            or 0x2B00 <= cp <= 0x2BFF or cp in (0x2705, 0x2714))


def clusters(text):
    """Splits text into ('t', str) and ('e', emoji) runs; flags and variation selectors stay together."""
    runs, i = [], 0
    while i < len(text):
        ch = text[i]
        if is_emoji(ch):
            j = i + 1
            if 0x1F1E6 <= ord(ch) <= 0x1F1FF and j < len(text) and 0x1F1E6 <= ord(text[j]) <= 0x1F1FF:
                j += 1
            while j < len(text) and ord(text[j]) in (0xFE0F, 0x200D):
                j += 2 if ord(text[j]) == 0x200D else 1
            runs.append(("e", text[i:j]))
            i = j
        else:
            j = i
            while j < len(text) and not is_emoji(text[j]):
                j += 1
            runs.append(("t", text[i:j]))
            i = j
    return runs


def emoji_image(e, px):
    key = (e, px)
    if key not in _emoji_cache:
        img = Image.new("RGBA", (EMOJI_NATIVE * 2, EMOJI_NATIVE * 2), (0, 0, 0, 0))
        ImageDraw.Draw(img).text((0, 0), e, font=_emoji_font, embedded_color=True)
        img = img.crop(img.getbbox() or (0, 0, 1, 1))
        ratio = px / max(img.height, 1)
        _emoji_cache[key] = img.resize((max(1, int(img.width * ratio)), px), Image.LANCZOS)
    return _emoji_cache[key]


def text_width(text, f):
    total = 0
    for kind, run in clusters(text):
        total += f.getlength(run) if kind == "t" else int(f.size * 1.18)
    return total


def draw_text(img, xy, text, f, fill):
    x, y = xy
    d = ImageDraw.Draw(img)
    for kind, run in clusters(text):
        if kind == "t":
            d.text((x, y), run, font=f, fill=fill)
            x += f.getlength(run)
        else:
            px = int(f.size * 1.02)
            e = emoji_image(run, px)
            img.alpha_composite(e, (int(x), int(y + f.size * 0.12)))
            x += int(f.size * 1.18)


def wrap(text, f, max_width):
    lines = []
    for paragraph in text.split("\n"):
        line = ""
        for word in paragraph.split(" "):
            candidate = word if not line else line + " " + word
            if text_width(candidate, f) <= max_width:
                line = candidate
            else:
                lines.append(line)
                line = word
        lines.append(line)
    return lines


# ---------------------------------------------------------------- model

@dataclass
class Bubble:
    text: str
    outgoing: bool
    appear: float                      # seconds
    buttons: list = field(default_factory=list)  # rows of labels
    typing_until: float = 0            # show "..." bubble before appear
    changes: list = field(default_factory=list)  # (time, new_text, new_buttons)
    working: tuple = None              # (start, end, row, col): button shows "Working…" and pulses
    tap: tuple = None                  # (time, row, col)


MSG_FONT = font(26)
TIME_FONT = font(17)
BTN_FONT = font(21, "Medium")
HEAD_FONT = font(27, "Semibold")
SUB_FONT = font(19)

PAD_X, PAD_Y = 22 * SCALE, 14 * SCALE
LINE_H = int(MSG_FONT.size * 1.32)
MAX_BUBBLE = int(W * 0.78) * SCALE
BTN_H, BTN_GAP = 52 * SCALE, 6 * SCALE
GAP = 16 * SCALE


def bubble_state(b, t):
    text, buttons = b.text, b.buttons
    for when, new_text, new_buttons in b.changes:
        if t >= when:
            text, buttons = new_text, new_buttons
    return text, buttons


def bubble_size(text, buttons):
    lines = wrap(text, MSG_FONT, MAX_BUBBLE - 2 * PAD_X)
    width = max(text_width(l, MSG_FONT) for l in lines) + 2 * PAD_X + 70 * SCALE
    width = min(max(width, 160 * SCALE), MAX_BUBBLE)
    height = len(lines) * LINE_H + 2 * PAD_Y + 10 * SCALE
    kb = len(buttons) * (BTN_H + BTN_GAP) if buttons else 0
    return lines, int(width), int(height), kb


def draw_bubble(img, b, t, x_right_edge, y_bottom, progress):
    text, buttons = bubble_state(b, t)
    lines, bw, bh, kb = bubble_size(text, buttons)
    total = bh + (BTN_GAP + kb if buttons else 0)
    offset = int((1 - ease(progress)) * 40 * SCALE)
    top = y_bottom - total + offset
    layer = Image.new("RGBA", img.size, (0, 0, 0, 0))
    ripple = Image.new("RGBA", img.size, (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    x = x_right_edge - bw if b.outgoing else 16 * SCALE
    fill = OUT_BUBBLE if b.outgoing else IN_BUBBLE
    d.rounded_rectangle((x, top, x + bw, top + bh), radius=22 * SCALE, fill=fill + (255,))
    for i, line in enumerate(lines):
        draw_text(layer, (x + PAD_X, top + PAD_Y + i * LINE_H), line, MSG_FONT, TEXT)
    stamp = "12:01 ✓✓" if b.outgoing else "12:01"
    sw = TIME_FONT.getlength(stamp)
    d.text((x + bw - sw - 16 * SCALE, top + bh - 30 * SCALE), stamp, font=TIME_FONT,
           fill=OUT_MUTED if b.outgoing else MUTED)
    if buttons:
        by = top + bh + BTN_GAP
        for r, row in enumerate(buttons):
            cw = (bw - (len(row) - 1) * BTN_GAP) / len(row)
            for c, label in enumerate(row):
                bx = x + c * (cw + BTN_GAP)
                working = b.working and b.working[0] <= t < b.working[1] and (r, c) == b.working[2:]
                shade = BUTTON
                if working:
                    pulse = 0.5 + 0.5 * abs(((t - b.working[0]) * 2) % 2 - 1)
                    shade = (0, 0, 0, int(42 + 40 * pulse))
                    label = "⏳ Working on it…"
                d.rounded_rectangle((bx, by, bx + cw, by + BTN_H), radius=12 * SCALE, fill=shade)
                lw = text_width(label, BTN_FONT)
                draw_text(layer, (bx + (cw - lw) / 2, by + (BTN_H - BTN_FONT.size) / 2 - 3 * SCALE), label,
                          BTN_FONT, (255, 255, 255))
                if b.tap and (r, c) == b.tap[1:] and 0 <= t - b.tap[0] < 0.6:
                    k = (t - b.tap[0]) / 0.6
                    rad = int((18 + 50 * k) * SCALE)
                    cx, cy = bx + cw / 2, by + BTN_H / 2
                    ImageDraw.Draw(ripple).ellipse((cx - rad, cy - rad, cx + rad, cy + rad),
                                                   fill=(255, 255, 255, int(150 * (1 - k))))
            by += BTN_H + BTN_GAP
    layer.alpha_composite(ripple)
    if progress < 1:
        alpha = layer.getchannel("A").point(lambda a: int(a * ease(progress)))
        layer.putalpha(alpha)
    img.alpha_composite(layer)
    return total - offset


def ease(p):
    p = max(0.0, min(1.0, p))
    return 1 - (1 - p) ** 3


def draw_typing_dots(img, t, y_bottom):
    d = ImageDraw.Draw(img)
    w, h = 96 * SCALE, 56 * SCALE
    x, top = 16 * SCALE, y_bottom - h
    d.rounded_rectangle((x, top, x + w, top + h), radius=22 * SCALE, fill=IN_BUBBLE + (255,))
    for i in range(3):
        phase = (t * 3 - i * 0.3) % 1
        r = int((5 + 2 * (1 - abs(phase * 2 - 1))) * SCALE)
        cx, cy = x + 28 * SCALE + i * 20 * SCALE, top + h // 2
        shade = 150 + int(80 * abs(phase * 2 - 1))
        d.ellipse((cx - r, cy - r, cx + r, cy + r), fill=(shade, shade, shade, 255))
    return h


# ---------------------------------------------------------------- script

T_INPUT_1, T_SEND_1 = 0.3, 2.6
FIRST = "i has went to the shop yesterday and buyed some apple"
FIRST_FIXED = "I went to the shop yesterday and bought some apples."
FORMAL = "Yesterday, I visited the store and purchased some apples."
T_INPUT_2, T_SEND_2 = 8.4, 10.2
SECOND = "Привіт! Можеш надіслати звіт до п'ятниці?"
SECOND_FIXED = "Hi! Could you send me the report by Friday?"
DURATION = 14.5


def keyboard(active=None, lang="🇬🇧"):
    tone = lambda e, name: ("✓ " if active == name else "") + e + " " + name
    return [["🔄 Another version", "📋 Copy"],
            [tone("🎩", "Formal"), tone("😎", "Casual"), tone("✂️", "Shorter")],
            ["🌐 Language · " + lang, "💡 What changed"]]


BUBBLES = [
    Bubble(FIRST, True, T_SEND_1),
    Bubble(FIRST_FIXED, False, 3.9, keyboard(), typing_until=3.9,
           changes=[(6.6, FORMAL, keyboard("Formal"))], working=(5.2, 6.6, 1, 0), tap=(5.0, 1, 0)),
    Bubble(SECOND, True, T_SEND_2),
    Bubble(SECOND_FIXED, False, 11.5, keyboard(), typing_until=11.5),
]
TYPING = [(T_SEND_1 + 0.3, 3.9), (T_SEND_2 + 0.3, 11.5)]
INPUTS = [(T_INPUT_1, T_SEND_1, FIRST), (T_INPUT_2, T_SEND_2, SECOND)]


def wallpaper():
    img = Image.new("RGBA", (W * SCALE, H * SCALE))
    d = ImageDraw.Draw(img)
    for y in range(H * SCALE):
        k = y / (H * SCALE)
        c = tuple(int(WALL_TOP[i] + (WALL_BOTTOM[i] - WALL_TOP[i]) * k) for i in range(3))
        d.line([(0, y), (W * SCALE, y)], fill=c + (255,))
    # subtle doodle dots like Telegram's pattern
    for i in range(0, W * SCALE, 90 * SCALE):
        for j in range(0, H * SCALE, 90 * SCALE):
            ox = (j // (90 * SCALE)) % 2 * 45 * SCALE
            d.ellipse((i + ox, j, i + ox + 6 * SCALE, j + 6 * SCALE), fill=(255, 255, 255, 40))
    return img


HEADER_H, INPUT_H = 96 * SCALE, 88 * SCALE


def draw_chrome(img, t):
    d = ImageDraw.Draw(img)
    d.rectangle((0, 0, W * SCALE, HEADER_H), fill=(255, 255, 255, 250))
    d.line((0, HEADER_H, W * SCALE, HEADER_H), fill=(0, 0, 0, 30), width=SCALE)
    cx, cy, r = 70 * SCALE, HEADER_H // 2, 30 * SCALE
    d.ellipse((cx - r, cy - r, cx + r, cy + r), fill=ACCENT + (255,))
    e = emoji_image("✍️", 30 * SCALE)
    img.alpha_composite(e, (cx - e.width // 2, cy - e.height // 2))
    d.text((cx + r + 18 * SCALE, cy - 30 * SCALE), "Rewryt", font=HEAD_FONT, fill=TEXT)
    typing = any(a <= t < b for a, b in TYPING)
    d.text((cx + r + 18 * SCALE, cy + 4 * SCALE), "typing…" if typing else "bot",
           font=SUB_FONT, fill=ACCENT if typing else MUTED)

    top = H * SCALE - INPUT_H
    d.rectangle((0, top, W * SCALE, H * SCALE), fill=(255, 255, 255, 250))
    typed = ""
    for start, send, text in INPUTS:
        if start <= t < send:
            n = int(len(text) * min(1, (t - start) / (send - start - 0.35)))
            typed = text[:n]
    fx = 30 * SCALE
    if typed:
        visible = typed
        while text_width(visible, MSG_FONT) > (W - 130) * SCALE:
            visible = visible[1:]
        draw_text(img, (fx, top + 26 * SCALE), visible, MSG_FONT, TEXT)
        if int(t * 2) % 2 == 0:
            cx = fx + text_width(visible, MSG_FONT) + 3 * SCALE
            d.line((cx, top + 28 * SCALE, cx, top + 60 * SCALE), fill=ACCENT, width=2 * SCALE)
    else:
        d.text((fx, top + 26 * SCALE), "Message", font=MSG_FONT, fill=MUTED)
    sx, sy, sr = (W - 50) * SCALE, top + INPUT_H // 2, 26 * SCALE
    d.ellipse((sx - sr, sy - sr, sx + sr, sy + sr), fill=ACCENT + (255,) if typed else (220, 225, 230, 255))
    d.polygon([(sx - 9 * SCALE, sy - 11 * SCALE), (sx + 12 * SCALE, sy), (sx - 9 * SCALE, sy + 11 * SCALE)],
              fill=(255, 255, 255, 255))


def render(t, base):
    img = base.copy()
    y = H * SCALE - INPUT_H - GAP
    visible = [b for b in BUBBLES if t >= b.appear]
    pending_typing = next((a for a, b in TYPING if a <= t < b), None)
    layer_items = []
    if pending_typing is not None:
        layer_items.append(("typing", None))
    layer_items += [("bubble", b) for b in reversed(visible)]
    for kind, b in layer_items:
        if y < HEADER_H:
            break
        if kind == "typing":
            y -= draw_typing_dots(img, t, y) + GAP
        else:
            progress = min(1.0, (t - b.appear) / 0.35)
            y -= draw_bubble(img, b, t, W * SCALE - 16 * SCALE, y, progress) + GAP
    draw_chrome(img, t)
    return img.resize((W, H), Image.LANCZOS).convert("RGB")


def main():
    MP4_OUT.parent.mkdir(parents=True, exist_ok=True)
    GIF_OUT.parent.mkdir(parents=True, exist_ok=True)
    base = wallpaper()
    ffmpeg = subprocess.Popen(
        ["ffmpeg", "-y", "-loglevel", "error", "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", f"{W}x{H}",
         "-r", str(FPS), "-i", "-", "-an", "-c:v", "libx264", "-preset", "slow", "-crf", "26",
         "-pix_fmt", "yuv420p", "-movflags", "+faststart", str(MP4_OUT)],
        stdin=subprocess.PIPE)
    for i in range(int(DURATION * FPS)):
        ffmpeg.stdin.write(render(i / FPS, base).tobytes())
    ffmpeg.stdin.close()
    if ffmpeg.wait() != 0:
        raise SystemExit("ffmpeg failed")
    subprocess.run(
        ["ffmpeg", "-y", "-loglevel", "error", "-i", str(MP4_OUT), "-vf",
         "fps=12,scale=360:-1:flags=lanczos,split[a][b];[a]palettegen=max_colors=96[p];[b][p]paletteuse=dither=bayer",
         str(GIF_OUT)], check=True)
    print(f"{MP4_OUT.relative_to(ROOT)}: {MP4_OUT.stat().st_size // 1024} KB")
    print(f"{GIF_OUT.relative_to(ROOT)}: {GIF_OUT.stat().st_size // 1024} KB")


if __name__ == "__main__":
    main()
