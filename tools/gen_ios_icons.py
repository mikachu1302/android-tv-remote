#!/usr/bin/env python3
"""Draws the iOS app icons (1024 px, one per palette plus the five Progress Pride ones) and their previews into
ios/TVRemote/Assets.xcassets, from the same geometry as the Android icons (tools/gen_launcher_icons.py).
iOS rounds the corners itself, so the icon is a full square and the art is scaled up a little compared with
the Android adaptive icon (which launchers crop to ~66 of 108 units)."""
import json
import math
import pathlib
import re
import sys

from PIL import Image, ImageChops, ImageDraw

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import gen_launcher_icons as g  # noqa: E402

ROOT = g.ROOT
ASSETS = ROOT / "ios/TVRemote/Assets.xcassets"
SIZE = 1024
SS = 3  # supersampling
UNIT = SIZE * SS / 108  # pixels per canvas unit


class Xf:
    """An affine map (a, b, c, d, e, f): x' = a x + c y + e, y' = b x + d y + f."""

    def __init__(self, a=1.0, b=0.0, c=0.0, d=1.0, e=0.0, f=0.0):
        self.m = (a, b, c, d, e, f)

    def then(self, other):  # apply self first, then other
        a, b, c, d, e, f = self.m
        A, B, C, D, E, F = other.m
        return Xf(A * a + C * b, B * a + D * b, A * c + C * d, B * c + D * d, A * e + C * f + E, B * e + D * f + F)

    def apply(self, x, y):
        a, b, c, d, e, f = self.m
        return a * x + c * y + e, b * x + d * y + f

    @property
    def scale(self):
        return math.hypot(self.m[0], self.m[1])


def group_xf(s, tx, ty):
    # android group: scale about the pivot (54, 54), then translate; then canvas units -> pixels
    inner = Xf(s, 0, 0, s, 54 - 54 * s + tx, 54 - 54 * s + ty)
    return inner.then(Xf(UNIT, 0, 0, UNIT, 0, 0))


NUM = re.compile(r"[-+]?(?:\d*\.\d+|\d+\.?)(?:[eE][-+]?\d+)?")


def flatten(d):
    """Path data (M L H V A Z, absolute and relative) -> list of (points, closed)."""
    tokens = re.findall(r"[MmLlHhVvAaZz]|[-+]?(?:\d*\.\d+|\d+\.?)(?:[eE][-+]?\d+)?", d)
    i, cmd, cur, start = 0, None, (0.0, 0.0), (0.0, 0.0)
    subs, pts = [], []

    def num():
        nonlocal i
        v = float(tokens[i])
        i += 1
        return v

    while i < len(tokens):
        if tokens[i].isalpha():
            cmd = tokens[i]
            i += 1
            if cmd in "Zz":
                if pts:
                    subs.append((pts, True))
                    pts = []
                cur = start
                continue
        rel = cmd.islower()
        c = cmd.upper()
        if c == "M":
            x, y = num(), num()
            cur = (cur[0] + x, cur[1] + y) if rel else (x, y)
            start = cur
            if pts:
                subs.append((pts, False))
            pts = [cur]
            cmd = "l" if rel else "L"
        elif c == "L":
            x, y = num(), num()
            cur = (cur[0] + x, cur[1] + y) if rel else (x, y)
            pts.append(cur)
        elif c == "H":
            x = num()
            cur = (cur[0] + x if rel else x, cur[1])
            pts.append(cur)
        elif c == "V":
            y = num()
            cur = (cur[0], cur[1] + y if rel else y)
            pts.append(cur)
        elif c == "A":
            rx, ry, rot, large, sweep, x, y = num(), num(), num(), int(num()), int(num()), num(), num()
            end = (cur[0] + x, cur[1] + y) if rel else (x, y)
            pts.extend(arc(cur, rx, ry, rot, large, sweep, end))
            cur = end
    if pts:
        subs.append((pts, False))
    return subs


def arc(p0, rx, ry, rot, large, sweep, p1, steps=48):
    (x0, y0), (x1, y1) = p0, p1
    if p0 == p1:
        return []
    rx, ry = abs(rx), abs(ry)
    phi = math.radians(rot)
    cp, sp = math.cos(phi), math.sin(phi)
    dx, dy = (x0 - x1) / 2, (y0 - y1) / 2
    x1p, y1p = cp * dx + sp * dy, -sp * dx + cp * dy
    lam = x1p ** 2 / rx ** 2 + y1p ** 2 / ry ** 2
    if lam > 1:
        rx, ry = rx * math.sqrt(lam), ry * math.sqrt(lam)
    num = rx ** 2 * ry ** 2 - rx ** 2 * y1p ** 2 - ry ** 2 * x1p ** 2
    den = rx ** 2 * y1p ** 2 + ry ** 2 * x1p ** 2
    coef = (1 if large != sweep else -1) * math.sqrt(max(0, num / den))
    cxp, cyp = coef * rx * y1p / ry, -coef * ry * x1p / rx
    cx, cy = cp * cxp - sp * cyp + (x0 + x1) / 2, sp * cxp + cp * cyp + (y0 + y1) / 2

    def ang(ux, uy, vx, vy):
        a = math.acos(max(-1, min(1, (ux * vx + uy * vy) / (math.hypot(ux, uy) * math.hypot(vx, vy)))))
        return -a if ux * vy - uy * vx < 0 else a

    t1 = ang(1, 0, (x1p - cxp) / rx, (y1p - cyp) / ry)
    dt = ang((x1p - cxp) / rx, (y1p - cyp) / ry, (-x1p - cxp) / rx, (-y1p - cyp) / ry)
    if not sweep and dt > 0:
        dt -= 2 * math.pi
    if sweep and dt < 0:
        dt += 2 * math.pi
    out = []
    for k in range(1, steps + 1):
        t = t1 + dt * k / steps
        out.append((cx + rx * math.cos(t) * cp - ry * math.sin(t) * sp, cy + rx * math.cos(t) * sp + ry * math.sin(t) * cp))
    return out


def rgb(hex_color):
    h = hex_color.lstrip("#")
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4))


class Canvas:
    def __init__(self, bg):
        self.img = Image.new("RGB", (SIZE * SS, SIZE * SS), rgb(bg))

    def _mask(self, draw_fn):
        mask = Image.new("L", self.img.size, 0)
        draw_fn(ImageDraw.Draw(mask))
        return mask

    def fill_mask(self, d, xf):
        def draw(dr):
            for pts, _ in flatten(d):
                if len(pts) >= 3:
                    dr.polygon([xf.apply(*p) for p in pts], fill=255)
        return self._mask(draw)

    def stroke_mask(self, d, width, xf):
        w = max(1, round(width * xf.scale))

        def draw(dr):
            for pts, closed in flatten(d):
                line = [xf.apply(*p) for p in pts] + ([xf.apply(*pts[0])] if closed else [])
                if len(line) >= 2:
                    dr.line(line, fill=255, width=w, joint="curve")
                for x, y in (line[0], line[-1]) if not closed else line[:-1]:
                    dr.ellipse((x - w / 2, y - w / 2, x + w / 2, y + w / 2), fill=255)
        return self._mask(draw)

    def paint(self, mask, color, clip=None):
        if clip is not None:
            mask = ImageChops.multiply(mask, clip)
        self.img.paste(Image.new("RGB", self.img.size, rgb(color) if isinstance(color, str) else color), (0, 0), mask)

    def fill(self, d, color, xf, clip=None):
        self.paint(self.fill_mask(d, xf), color, clip)

    def stroke(self, d, color, width, xf, fill_color=None, clip=None):
        if fill_color:
            self.fill(d, fill_color, xf, clip)
        self.paint(self.stroke_mask(d, width, xf), color, clip)

    def rainbow_stroke(self, d, fill_color, width, xf, gx0, gx1):
        self.fill(d, fill_color, xf)
        mask = self.stroke_mask(d, width, xf)
        a, _, _, _, e, _ = xf.m
        row = Image.new("RGB", (self.img.width, 1))
        stops = [rgb(c) for c in g.STRIPES]
        for px in range(self.img.width):
            t = max(0.0, min(1.0, (((px - e) / a) - gx0) / (gx1 - gx0))) * (len(stops) - 1)
            k = min(int(t), len(stops) - 2)
            f = t - k
            row.putpixel((px, 0), tuple(round(stops[k][c] * (1 - f) + stops[k + 1][c] * f) for c in range(3)))
        self.img.paste(row.resize(self.img.size), (0, 0), mask)

    def done(self, size=SIZE):
        return self.img.resize((size, size), Image.LANCZOS)


def circle_d(cx, cy, r):
    return g.circle(cx, cy, r)


def remote_art(c, xf, tv, remote, dot1, ring, dot3):
    c.stroke(g.TV, tv, 4.5, xf)
    c.stroke(g.STAND, tv, 4.5, xf)
    c.stroke(g.REMOTE, remote, 4.5, xf)
    c.fill(circle_d(74, 59, 2.8), dot1, xf)
    c.stroke(circle_d(74, 72, 5.5), ring, 3.5, xf)
    c.fill(circle_d(74, 84, 2), dot3, xf)


def flag(c, xf, x, y, scale):
    local = Xf(scale, 0, 0, scale, x, y).then(xf)
    clip = c.fill_mask(g.SCREEN_CLIP, local)
    h = 44 / 6
    for i, color in enumerate(g.STRIPES):
        y0, y1 = i * h, min(44.0, i * h + h + 0.3)
        c.fill(f"M0,{y0:.2f} H68 V{y1:.2f} H0 Z", color, local, clip)
    for color, path in g.CHEVRON:
        c.fill(path, color, local, clip)


def pride_dots(c, xf):
    c.fill(circle_d(74, 59, 2.8), "#E40303", xf)
    c.stroke(circle_d(74, 72, 5.5), "#004DFF", 3.5, xf)
    c.fill(circle_d(74, 84, 2.2), "#FF8C00", xf)


def remote_pride(c, xf, body, outline, width):
    c.stroke(g.REMOTE, outline, width, xf, fill_color=body)
    pride_dots(c, xf)


NIGHT = ("#3A1F6B", "#0F2A5C")  # gradient start / end, 315 degrees


def night_background(c):
    """The 315 degree gradient of the Android night icon: top-left colour to bottom-right colour."""
    w = c.img.width
    a, b = rgb(NIGHT[0]), rgb(NIGHT[1])
    dr = ImageDraw.Draw(c.img)
    for k in range(2 * w - 1):
        t = k / (2 * w - 2)
        dr.line([(k, 0), (0, k)], fill=tuple(round(a[i] * (1 - t) + b[i] * t) for i in range(3)), width=2)


def palette_icons():
    palettes = [dict(zip(("id", "name", "bg", "surface", "border", "text", "dim", "primary", "action", "soft", "warn"), m))
                for m in g.PALETTE.findall(g.THEME.read_text())]
    for p in palettes:
        yield p["id"], draw_palette(p)


S, TX, TY = 0.92, 0.92 * 2, -0.92 * 6  # scale and the translation that centres the art (52, 60) on (54, 54)


def draw_palette(p):
    c = Canvas("#" + p["bg"])
    xf = group_xf(S, TX, TY)
    prim, act = "#" + p["primary"], "#" + p["action"]
    remote_art(c, xf, prim, act, act, act, act)
    return c


def pride_icons():
    xf = group_xf(S, TX, TY)

    c = Canvas("#17161F")
    c.fill(g.BEZEL, "#2E2D3D", xf)
    flag(c, xf, 18, 26, 1)
    c.stroke(g.STAND, "#55556A", 5, xf)
    remote_pride(c, xf, "#F4F4F8", "#17161F", 3.5)
    yield "pride_bezel", c

    c = Canvas("#F3F2F8")
    c.fill(g.BEZEL, "#2B2A3A", xf)
    flag(c, xf, 18, 26, 1)
    c.stroke(g.STAND, "#2B2A3A", 5, xf)
    remote_pride(c, xf, "#FFFFFF", "#2B2A3A", 4)
    yield "pride_light", c

    c = Canvas("#14131C")
    c.rainbow_stroke(g.RAINBOW_BEZEL, "#1F1E2B", 4, xf, 15, 89)
    c.fill(g.RAINBOW_SCREEN, "#0B0A12", xf)
    c.fill(g.PLAY, "#FFFFFF", xf)
    c.stroke(g.STAND, "#55556A", 5, xf)
    remote_pride(c, xf, "#F4F4F8", "#14131C", 3.5)
    yield "pride_rainbow", c

    c = Canvas("#000000")
    night_background(c)
    c.fill(g.BEZEL, "#1B1A2A", xf)
    flag(c, xf, 18, 26, 1)
    c.stroke(g.STAND, "#9A9AC8", 5, xf)
    remote_pride(c, xf, "#F4F4F8", "#1B1A2A", 3.5)
    yield "pride_night", c

    c = Canvas("#17161F")
    big = group_xf(0.74 * 1.24, 0.74 * 1.24 * 2 - 0.0, -2 * 1.24 - 0.74 * 1.24 * 6 + 0.0)
    flag(c, big, 14, 20, 76 / 68)
    c.stroke(g.BIG_REMOTE, "#17161F", 4, big, fill_color="#F4F4F8")
    c.fill(circle_d(71, 56, 3), "#E40303", big)
    c.stroke(circle_d(71, 69, 6), "#004DFF", 3.6, big)
    c.fill(circle_d(71, 82, 2.4), "#FF8C00", big)
    yield "pride_big", c


def write_appiconset(name, image):
    folder = ASSETS / f"{name}.appiconset"
    folder.mkdir(parents=True, exist_ok=True)
    image.save(folder / "icon.png")
    (folder / "Contents.json").write_text(json.dumps({
        "images": [{"filename": "icon.png", "idiom": "universal", "platform": "ios", "size": "1024x1024"}],
        "info": {"author": "xcode", "version": 1},
    }, indent=2) + "\n")


def write_preview(name, image):
    folder = ASSETS / f"preview_{name}.imageset"
    folder.mkdir(parents=True, exist_ok=True)
    image.resize((192, 192), Image.LANCZOS).save(folder / "preview.png")
    (folder / "Contents.json").write_text(json.dumps({
        "images": [{"filename": "preview.png", "idiom": "universal"}],
        "info": {"author": "xcode", "version": 1},
    }, indent=2) + "\n")


def main():
    for old in list(ASSETS.glob("icon_*.appiconset")) + list(ASSETS.glob("preview_*.imageset")):
        for f in old.iterdir():
            f.unlink()
        old.rmdir()
    alternates = []
    for pid, canvas in palette_icons():
        image = canvas.done()
        if pid == "neon":
            (ASSETS / "AppIcon.appiconset").mkdir(parents=True, exist_ok=True)
            image.save(ASSETS / "AppIcon.appiconset/icon-neon.png")
        else:
            write_appiconset(f"icon_{pid}", image)
            alternates.append(f"icon_{pid}")
        write_preview(pid, image)
    for pid, canvas in pride_icons():
        image = canvas.done()
        write_appiconset(f"icon_{pid}", image)
        alternates.append(f"icon_{pid}")
        write_preview(pid, image)
    print("alternate icons:", " ".join(alternates))
    (ROOT / "ios/alternate-icons.txt").write_text(" ".join(alternates) + "\n")


if __name__ == "__main__":
    main()
