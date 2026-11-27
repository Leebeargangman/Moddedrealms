#!/usr/bin/env python3
"""Generates the NPC skins (64x64, classic "wide" arms) for the Moddedrealms resource pack.

Every skin is painted from a short description: skin tone, hair, clothes, and a few details (badges, ties, hats on the
overlay layer). Run it again after changing a description; it rewrites src/assets/moddedrealms/textures/entity/npc/.
`python3 gen_skins.py --preview out.png` also writes a sheet with the front of every skin, for checking by eye.
"""
import os
import random
import sys

from PIL import Image

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "src/assets/moddedrealms/textures/entity/npc")

# Box layouts in the 64x64 skin: (origin x, origin y, width, height, depth), base layer and overlay layer.
BOX = {
    "head": ((0, 0, 8, 8, 8), (32, 0, 8, 8, 8)),
    "body": ((16, 16, 8, 12, 4), (16, 32, 8, 12, 4)),
    "rarm": ((40, 16, 4, 12, 4), (40, 32, 4, 12, 4)),
    "larm": ((32, 48, 4, 12, 4), (48, 48, 4, 12, 4)),
    "rleg": ((0, 16, 4, 12, 4), (0, 32, 4, 12, 4)),
    "lleg": ((16, 48, 4, 12, 4), (0, 48, 4, 12, 4)),
}


def faces(ox, oy, w, h, d):
    """The six faces of a box as (name, x, y, width, height)."""
    return {
        "top": (ox + d, oy, w, d),
        "bottom": (ox + d + w, oy, w, d),
        "right": (ox, oy + d, d, h),
        "front": (ox + d, oy + d, w, h),
        "left": (ox + d + w, oy + d, d, h),
        "back": (ox + 2 * d + w, oy + d, w, h),
    }


def hexc(s):
    s = s.lstrip("#")
    return tuple(int(s[i:i + 2], 16) for i in (0, 2, 4)) + (255,)


def shade(c, k):
    return tuple(max(0, min(255, int(v * k))) for v in c[:3]) + (c[3],)


class Skin:
    def __init__(self, seed):
        self.img = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
        self.r = random.Random(seed)

    def px(self, x, y, c, noise=6):
        if c[3] == 0:
            self.img.putpixel((x, y), c)
            return
        n = self.r.randint(-noise, noise) if noise else 0
        self.img.putpixel((x, y), tuple(max(0, min(255, v + n)) for v in c[:3]) + (c[3],))

    def band(self, part, y0, y1, c, layer=0, sides=("front", "back", "left", "right"), noise=6, top=False, bottom=False):
        """Paint rows y0..y1-1 of the side faces of a part (rows count from the top of the part)."""
        box = BOX[part][layer]
        f = faces(*box)
        for s in sides:
            fx, fy, fw, fh = f[s]
            for y in range(max(0, y0), min(fh, y1)):
                k = 1.0 - 0.012 * y  # a little darker towards the bottom
                if s in ("left", "right"):
                    k *= 0.9
                if s == "back":
                    k *= 0.85
                for x in range(fw):
                    self.px(fx + x, fy + y, shade(c, k), noise)
        for which, on in (("top", top), ("bottom", bottom)):
            if on:
                fx, fy, fw, fh = f[which]
                for y in range(fh):
                    for x in range(fw):
                        self.px(fx + x, fy + y, shade(c, 1.05 if which == "top" else 0.8), noise)

    def dot(self, part, face, x, y, c, layer=0, noise=0):
        fx, fy, fw, fh = faces(*BOX[part][layer])[face]
        if 0 <= x < fw and 0 <= y < fh:
            self.px(fx + x, fy + y, c, noise)

    def save(self, name):
        os.makedirs(OUT, exist_ok=True)
        self.img.save(os.path.join(OUT, name + ".png"))


TONES = ["#F2CDB0", "#E8B894", "#D19A72", "#B07A52", "#8A5A3A", "#5E3B26"]
HAIRS = ["#2B1D14", "#4A2E1A", "#7A4E2A", "#B8863B", "#D9C27A", "#9A9A9A", "#E8E8E8", "#8A2E1A", "#1A1A1A"]
EYES = ["#3B6FB0", "#3E7A3A", "#5A3A22", "#2A2A2A", "#6A8A9A"]


def person(s, tone, hair, hair_style="short", eyes="#3B6FB0", beard=None, brows=None):
    """Head, hands and bare skin everywhere; clothes go on top."""
    t = hexc(tone)
    for part in BOX:
        s.band(part, 0, 99, t, top=True, bottom=True, noise=4)
    h = hexc(hair)
    # face
    s.dot("head", "front", 1, 4, hexc("#FFFFFF"))
    s.dot("head", "front", 2, 4, hexc(eyes))
    s.dot("head", "front", 5, 4, hexc(eyes))
    s.dot("head", "front", 6, 4, hexc("#FFFFFF"))
    b = hexc(brows) if brows else shade(h, 0.8)
    for x in (1, 2, 5, 6):
        s.dot("head", "front", x, 3, b)
    for x in (3, 4):
        s.dot("head", "front", x, 5, shade(t, 0.88))
        s.dot("head", "front", x, 6, shade(hexc("#B05A50"), 1.0))
    s.dot("head", "front", 2, 6, shade(t, 0.92))
    s.dot("head", "front", 5, 6, shade(t, 0.92))
    if hair_style == "bald":
        s.band("head", 0, 0, h, top=False)
        fx, fy, fw, fh = faces(*BOX["head"][0])["top"]
        for y in range(fh):
            for x in range(fw):
                s.px(fx + x, fy + y, shade(t, 1.03), 3)
    else:
        s.band("head", 0, 0, h, top=True)
        s.band("head", 0, 2 if hair_style != "fringe" else 3, h, sides=("front",))
        s.band("head", 0, 3, h, sides=("left", "right"))
        back = {"short": 5, "fringe": 5, "long": 8, "ponytail": 8, "bun": 6}.get(hair_style, 5)
        s.band("head", 0, back, h, sides=("back",))
        if hair_style == "long":
            s.band("head", 0, 8, h, sides=("left", "right"))
            for y in range(2, 8):
                s.dot("head", "front", 0, y, h)
                s.dot("head", "front", 7, y, h)
        if hair_style == "ponytail":
            for y in range(3, 8):
                s.dot("head", "back", 3, y, shade(h, 0.8), layer=1)
                s.dot("head", "back", 4, y, shade(h, 0.8), layer=1)
        if hair_style == "bun":
            for x in (3, 4):
                for y in (0, 1):
                    s.dot("head", "back", x, y, shade(h, 0.9), layer=1)
        # the hairline gap between fringe and eyebrows stays skin
    if beard:
        bc = hexc(beard)
        for x in range(1, 7):
            s.dot("head", "front", x, 7, bc)
        for x in (1, 2, 5, 6):
            s.dot("head", "front", x, 6, bc)
        s.band("head", 5, 8, bc, sides=("left", "right"))


def shirt(s, c, sleeves=12, layer=0):
    c = hexc(c)
    s.band("body", 0, 12, c, layer=layer, top=True, bottom=True)
    for arm in ("rarm", "larm"):
        s.band(arm, 0, sleeves, c, layer=layer, top=True)


def trousers(s, c, length=12):
    c = hexc(c)
    for leg in ("rleg", "lleg"):
        s.band(leg, 0, length, c, top=True)


def shoes(s, c, height=2):
    c = hexc(c)
    for leg in ("rleg", "lleg"):
        s.band(leg, 12 - height, 12, c, bottom=True)


def belt(s, c, buckle="#C9A227"):
    s.band("body", 10, 11, hexc(c))
    s.dot("body", "front", 3, 10, hexc(buckle))
    s.dot("body", "front", 4, 10, hexc(buckle))


def column(s, part, face, x, y0, y1, c, layer=0):
    for y in range(y0, y1):
        s.dot(part, face, x, y, hexc(c), layer)


def tie(s, c):
    column(s, "body", "front", 3, 0, 8, c)
    column(s, "body", "front", 4, 0, 8, c)
    s.dot("body", "front", 3, 8, shade(hexc(c), 0.8))


def collar(s, c):
    for x in (2, 5):
        s.dot("body", "front", x, 0, hexc(c))
    for x in (3, 4):
        s.dot("body", "front", x, 0, hexc(c))


def jacket(s, c, open_front=True, inner="#FFFFFF", sleeves=12, long=False):
    shirt(s, c, sleeves)
    if open_front:
        for y in range(0, 7):
            for x in (3, 4):
                s.dot("body", "front", x, y, hexc(inner), noise=3)
        s.dot("body", "front", 2, 0, shade(hexc(c), 0.7))
        s.dot("body", "front", 5, 0, shade(hexc(c), 0.7))
    if long:
        for leg in ("rleg", "lleg"):
            s.band(leg, 0, 8, hexc(c), layer=1)


def hat(s, c, height=3, brim=None, band=None):
    """A hat on the overlay layer: crown rows 0..height-1, optional brim colour on the row below."""
    c = hexc(c)
    s.band("head", 0, height, c, layer=1, top=True)
    if band:
        s.band("head", height - 1, height, hexc(band), layer=1)
    if brim:
        s.band("head", height, height + 1, hexc(brim), layer=1)


def glasses(s, c="#111111", lens="#1E1E2A"):
    for x in (1, 2, 5, 6):
        s.dot("head", "front", x, 4, hexc(lens), layer=1)
    for x in (3, 4):
        s.dot("head", "front", x, 4, hexc(c), layer=1)
    s.dot("head", "front", 0, 4, hexc(c), layer=1)
    s.dot("head", "front", 7, 4, hexc(c), layer=1)


def mask(s, c, rows=(5, 8)):
    s.band("head", rows[0], rows[1], hexc(c), layer=1)


def badge(s, c="#E0B53A", x=5, y=2):
    s.dot("body", "front", x, y, hexc(c))
    s.dot("body", "front", x + 1, y, shade(hexc(c), 0.8))
    s.dot("body", "front", x, y + 1, shade(hexc(c), 0.85))


def buttons(s, c="#E0B53A", x=4, ys=(2, 4, 6, 8)):
    for y in ys:
        s.dot("body", "front", x, y, hexc(c))


def apron(s, c, top=3):
    for y in range(top, 12):
        for x in range(1, 7):
            s.dot("body", "front", x, y, hexc(c), layer=1, noise=4)
    for leg in ("rleg", "lleg"):
        for y in range(0, 6):
            for x in range(4):
                if (leg == "rleg" and x >= 1) or (leg == "lleg" and x <= 2):
                    s.dot(leg, "front", x, y, hexc(c), layer=1, noise=4)


def dress(s, c, length=8):
    for leg in ("rleg", "lleg"):
        s.band(leg, 0, length, hexc(c), layer=1, top=True)


# ---------------------------------------------------------------- medieval helpers

EARTH = ["#6A4A2A", "#5A6A3A", "#7A2A1A", "#3A4A6A", "#8A7A5A", "#4A3A2A", "#6A5A7A", "#2F5A4A", "#9A8A6A", "#5A2A3A"]


def chainmail(s, sleeves=12):
    """Grey mail: a fine checker of two greys."""
    for part, rows in (("body", 12), ("rarm", sleeves), ("larm", sleeves)):
        for face in ("front", "back", "left", "right"):
            fx, fy, fw, fh = faces(*BOX[part][0])[face]
            for y in range(min(rows, fh)):
                for x in range(fw):
                    c = "#8E9296" if (x + y) % 2 else "#6E7276"
                    s.px(fx + x, fy + y, hexc(c), 4)


def tabard(s, c, emblem=None, trim=None):
    """A tabard over the chest and down the front of the legs (overlay)."""
    for y in range(0, 12):
        for x in range(1, 7):
            s.dot("body", "front", x, y, hexc(c), layer=1, noise=4)
            s.dot("body", "back", x, y, hexc(c), layer=1, noise=4)
    for leg, xs in (("rleg", (1, 2, 3)), ("lleg", (0, 1, 2))):
        for y in range(0, 4):
            for x in xs:
                s.dot(leg, "front", x, y, hexc(c), layer=1, noise=4)
    if trim:
        for y in range(12):
            s.dot("body", "front", 1, y, hexc(trim), layer=1)
            s.dot("body", "front", 6, y, hexc(trim), layer=1)
    if emblem:
        for x, y in ((3, 3), (4, 3), (3, 4), (4, 4), (3, 5), (4, 5), (2, 4), (5, 4)):
            s.dot("body", "front", x, y, hexc(emblem), layer=1)


def hood(s, c, deep=False):
    """A hood: covers the hair, frames the face, falls to the shoulders at the back."""
    c = hexc(c)
    s.band("head", 0, 8, c, layer=1, sides=("back", "left", "right"), top=True)
    s.band("head", 0, 2 if not deep else 3, c, layer=1, sides=("front",))
    for y in range(2, 8):
        s.dot("head", "front", 0, y, c, layer=1)
        s.dot("head", "front", 7, y, c, layer=1)
    if deep:  # a shadowed face
        for x in range(1, 7):
            s.dot("head", "front", x, 3, shade(c, 0.5), layer=1)


def cloak(s, c, clasp="#C9A227"):
    """A cloak down the back to the knees."""
    for y in range(12):
        for x in range(8):
            s.dot("body", "back", x, y, hexc(c), layer=1, noise=5)
    for leg in ("rleg", "lleg"):
        for y in range(0, 7):
            for x in range(4):
                s.dot(leg, "back", x, y, hexc(c), layer=1, noise=5)
    s.band("body", 0, 1, hexc(c), layer=1, sides=("left", "right"))
    s.dot("body", "front", 0, 0, hexc(clasp), layer=1)
    s.dot("body", "front", 7, 0, hexc(clasp), layer=1)


def robe(s, c, trim=None):
    """A long robe: body, arms and legs, with a trim colour down the front."""
    shirt(s, c)
    trousers(s, c)
    for leg in ("rleg", "lleg"):
        s.band(leg, 0, 11, hexc(c), layer=1, top=True)
    if trim:
        column(s, "body", "front", 3, 0, 12, trim)
        column(s, "body", "front", 4, 0, 12, trim)
        column(s, "rleg", "front", 3, 0, 11, trim, layer=1)
        column(s, "lleg", "front", 0, 0, 11, trim, layer=1)


def tunic(s, c, sleeves=12, belt_c="#4A2E1A"):
    shirt(s, c, sleeves)
    for leg in ("rleg", "lleg"):  # the skirt of the tunic over the thighs
        s.band(leg, 0, 3, hexc(c), layer=1, top=True)
    belt(s, belt_c, "#8A7A5A")


def hose(s, c):
    trousers(s, c)


def kirtle(s, c, apron_c=None):
    shirt(s, c, s.r.choice([8, 12]))
    trousers(s, c)
    dress(s, c, 11)
    if apron_c:
        apron(s, apron_c, 5)


def coif(s, c="#E8E2D0"):
    """A close cap tied under the chin."""
    s.band("head", 0, 2, hexc(c), layer=1, top=True)
    s.band("head", 0, 6, hexc(c), layer=1, sides=("left", "right"))
    s.band("head", 0, 5, hexc(c), layer=1, sides=("back",))


def wimple(s, c="#ECE6DA"):
    coif(s, c)
    s.band("head", 6, 8, hexc(c), layer=1, sides=("left", "right", "back"))
    for x in (0, 1, 6, 7):
        s.dot("head", "front", x, 7, hexc(c), layer=1)


def helm(s, c="#9A9EA2", nasal=True, plume=None):
    """A kettle or nasal helm."""
    hat(s, c, 3, band=shade_hex(c, 0.8))
    if nasal:
        s.dot("head", "front", 3, 3, hexc(c), layer=1)
        s.dot("head", "front", 4, 3, hexc(c), layer=1)
        s.dot("head", "front", 3, 4, hexc(c), layer=1)
        s.dot("head", "front", 4, 4, hexc(c), layer=1)
    if plume:
        fx, fy, fw, fh = faces(*BOX["head"][1])["top"]
        for x in (3, 4):
            for y in range(1, 7):
                s.px(fx + x, fy + y, hexc(plume), 6)


def shade_hex(c, k):
    r, g, b, _ = shade(hexc(c), k)
    return "#%02X%02X%02X" % (r, g, b)


def feather(s, c="#C82A2A", side="left"):
    for y in range(0, 3):
        s.dot("head", side, 5 + (y % 2), y, hexc(c), layer=1)


# ---------------------------------------------------------------- the cast (medieval)

def officer(s):  # Town Watch guard
    person(s, s.r.choice(TONES[:4]), s.r.choice(HAIRS[:4]), beard=s.r.choice([None, "#4A2E1A"]))
    chainmail(s)
    tabard(s, "#1C3A7A", emblem="#E0B53A", trim="#E0B53A")
    hose(s, "#4A3A2A")
    shoes(s, "#2A1A0A", 4)
    belt(s, "#2A1A0A", "#9A9A9A")
    helm(s)


def warden(s):  # captain of the Watch
    person(s, "#D19A72", "#2B1D14", beard="#2B1D14")
    chainmail(s)
    tabard(s, "#14286A", emblem="#E0B53A", trim="#E0B53A")
    cloak(s, "#7A1A1A")
    hose(s, "#3A2A1A")
    shoes(s, "#1A1008", 4)
    helm(s, "#B0B4B8", plume="#C82A2A")


def sheriff(s):  # the reeve
    person(s, "#E8B894", "#7A4E2A", beard="#7A4E2A")
    tunic(s, "#3A5A2A")
    cloak(s, "#5A3A1A")
    hose(s, "#4A3A2A")
    shoes(s, "#3A2A1A", 4)
    hat(s, "#3A5A2A", 2, brim="#2A4A1A")
    feather(s, "#E8E8E8")


def agent(s):  # an inquisitor
    person(s, "#F2CDB0", "#1A1A1A")
    robe(s, "#141414", trim="#7A1A1A")
    hood(s, "#141414", deep=True)
    belt(s, "#7A1A1A", "#C9A227")
    shoes(s, "#0A0A0A")


def judge(s):
    person(s, "#E8B894", "#9A9A9A")
    robe(s, "#8A1A1A")
    s.band("body", 0, 2, hexc("#EDEDE6"), layer=1)  # fur collar
    for arm in ("rarm", "larm"):
        s.band(arm, 10, 12, hexc("#EDEDE6"), layer=1)  # fur cuffs
    hat(s, "#141414", 2)  # the black coif
    s.band("head", 0, 6, hexc("#EDEDE6"), layer=1, sides=("left", "right", "back"))  # the wig
    shoes(s, "#141414")


def lawyer(s):  # advocate
    person(s, "#D19A72", "#2B1D14", hair_style="fringe")
    robe(s, "#1A1A22")
    for y in range(0, 3):
        s.dot("body", "front", 3, y, hexc("#FFFFFF"))
        s.dot("body", "front", 4, y, hexc("#FFFFFF"))
    shoes(s, "#1A1008")


def lawyer_f(s):
    person(s, "#F2CDB0", "#7A4E2A", hair_style="bun")
    robe(s, "#1F3A2A")
    s.band("body", 0, 1, hexc("#E8E2D0"), layer=1)
    shoes(s, "#111111")


def banker(s):  # moneylender
    person(s, "#F2CDB0", "#D9C27A", beard="#D9C27A")
    shirt(s, "#4A1F5A")
    buttons(s, "#E0B53A", 3, (1, 3, 5, 7, 9))
    buttons(s, "#E0B53A", 4, (1, 3, 5, 7, 9))
    for arm in ("rarm", "larm"):
        s.band(arm, 3, 4, hexc("#E0B53A"))
    hose(s, "#2A1A2A")
    shoes(s, "#1A1008")
    belt(s, "#2A1A0A", "#E0B53A")
    s.dot("body", "front", 6, 9, hexc("#8A6A2A"))  # purse
    s.dot("body", "front", 6, 10, hexc("#6A4A1A"))
    hat(s, "#1A1A1A", 2, band="#E0B53A")


def bartender(s):  # tavernkeeper
    person(s, "#D19A72", "#2B1D14", beard="#2B1D14")
    shirt(s, "#E8E0C8", sleeves=6)
    for y in range(0, 9):
        for x in list(range(0, 3)) + list(range(5, 8)):
            s.dot("body", "front", x, y, hexc("#5A3A1A"), noise=3)
    hose(s, "#3A2A1A")
    shoes(s, "#2A1A0A")
    apron(s, "#D8D0B8", 6)


def croupier(s):  # dice master, in motley
    person(s, "#F2CDB0", "#1A1A1A", hair_style="fringe")
    shirt(s, "#8A1A1A")
    s.band("body", 0, 12, hexc("#1A1A1A"), sides=("left",))
    for y in range(12):
        for x in range(4, 8):
            s.dot("body", "front", x, y, hexc("#1A1A1A"), noise=3)
    s.band("larm", 0, 12, hexc("#1A1A1A"))
    trousers(s, "#1A1A1A")
    s.band("rleg", 0, 12, hexc("#8A1A1A"))
    shoes(s, "#3A2A1A")
    hat(s, "#8A1A1A", 2)
    feather(s, "#E0B53A")


def croupier_f(s):
    person(s, "#E8B894", "#8A2E1A", hair_style="long")
    kirtle(s, "#6A1A2A")
    s.band("body", 0, 1, hexc("#E0B53A"), layer=1)
    shoes(s, "#111111")


def bouncer(s):
    person(s, "#8A5A3A", "#1A1A1A", hair_style="bald", beard="#1A1A1A")
    shirt(s, "#5A3A1A", sleeves=3)  # leather jerkin, bare arms
    for y in (2, 5, 8):
        s.band("body", y, y + 1, hexc("#3A2410"))
    hose(s, "#2A1A10")
    shoes(s, "#1A1008", 4)
    belt(s, "#1A1008", "#9A9A9A")


def gangster(s, coat, inner, mask_c):
    person(s, s.r.choice(TONES), s.r.choice(HAIRS[:5]), hair_style=s.r.choice(["short", "fringe", "bald"]), eyes=s.r.choice(EYES))
    shirt(s, inner)
    for y in (3, 7):
        s.band("body", y, y + 1, hexc(coat))
    hose(s, "#2A2A2A")
    shoes(s, "#1A1A1A", 4)
    belt(s, "#1A1008", "#9A9A9A")
    hood(s, coat)
    if mask_c:
        mask(s, mask_c, (5, 8))
    cloak(s, coat, "#9A9A9A")


def bandit(s):
    person(s, s.r.choice(TONES[1:]), s.r.choice(HAIRS[:4]), eyes=s.r.choice(EYES))
    tunic(s, "#5A3A22")
    hose(s, "#3A3A2A")
    shoes(s, "#2A1A0A", 4)
    mask(s, "#7A1A1A", (5, 8))
    hood(s, "#4A3A22")


def thief(s):
    person(s, s.r.choice(TONES), "#1A1A1A", eyes=s.r.choice(EYES))
    tunic(s, "#22222A", belt_c="#1A1A1A")
    hose(s, "#1A1A20")
    shoes(s, "#0A0A0A")
    hood(s, "#22222A", deep=True)
    mask(s, "#1A1A1A", (5, 8))


def fence(s):
    person(s, "#D19A72", "#4A2E1A", beard="#4A2E1A")
    tunic(s, "#5A4A2A")
    cloak(s, "#4A3A2A", "#6A6A6A")
    for x, y in ((1, 3), (5, 8), (2, 10)):  # patches
        s.dot("body", "back", x, y, hexc("#7A6A4A"), layer=1)
    hose(s, "#3A2A1A")
    shoes(s, "#1A1A1A")
    hood(s, "#4A3A2A")


def merchant(s, apron_c="#2E6A3A"):
    person(s, s.r.choice(TONES), s.r.choice(HAIRS[:6]), eyes=s.r.choice(EYES), beard=s.r.choice([None, None, "#4A2E1A"]))
    tunic(s, s.r.choice(["#E8E0C8", "#C8B890", "#8A9A7A"]), sleeves=6)
    hose(s, "#4A3A2A")
    shoes(s, "#2A1A0A")
    apron(s, apron_c, 2)
    coif(s, "#D8D0B8") if s.r.random() < 0.5 else hat(s, "#5A4A3A", 2)


def gossip(s):
    person(s, s.r.choice(TONES), s.r.choice(HAIRS), hair_style="long", eyes=s.r.choice(EYES))
    kirtle(s, "#6A3A7A")
    s.band("body", 0, 3, hexc("#C9A0C8"), layer=1)  # shawl
    wimple(s)
    shoes(s, "#2A1A2A")


def traveller(s):  # pilgrim
    person(s, s.r.choice(TONES), s.r.choice(HAIRS[:5]), eyes=s.r.choice(EYES), beard=s.r.choice([None, "#4A2E1A"]))
    tunic(s, "#6A5A3A")
    cloak(s, "#4A3A2A")
    for y in range(1, 9):  # pack under the cloak
        for x in range(2, 6):
            s.dot("body", "back", x, y, hexc("#5A6A3A"), layer=1, noise=5)
    hose(s, "#3A3A2A")
    shoes(s, "#3A2A1A", 4)
    hat(s, "#7A6A4A", 2, brim="#6A5A3A")


def courier(s):  # royal messenger
    person(s, "#E8B894", "#2B1D14")
    tunic(s, "#8A1A1A")
    tabard(s, "#8A1A1A", emblem="#E0B53A", trim="#E0B53A")
    hose(s, "#1A1A1A")
    shoes(s, "#111111", 4)
    hat(s, "#8A1A1A", 2, band="#E0B53A")
    feather(s, "#E0B53A")


def conductor(s):  # the railway's stationmaster
    person(s, "#D19A72", "#9A9A9A", beard="#9A9A9A")
    tunic(s, "#1F3A2A")
    buttons(s, "#E0B53A", 2, (2, 4, 6))
    hose(s, "#2A2A1A")
    shoes(s, "#111111")
    hat(s, "#1F3A2A", 2, brim="#111111", band="#E0B53A")


def editor(s):  # the scribe of the Herald
    person(s, "#F2CDB0", "#7A4E2A", hair_style="fringe")
    robe(s, "#5A4A3A")
    belt(s, "#3A2A1A", "#3A2A1A")
    s.dot("body", "front", 6, 9, hexc("#E8E8E0"))  # quill
    s.dot("body", "front", 6, 8, hexc("#E8E8E0"))
    s.dot("rarm", "front", 1, 9, hexc("#111111"))  # inky fingers
    coif(s, "#3A2A1A")


def newsboy(s):  # the crier's boy
    person(s, s.r.choice(TONES), s.r.choice(HAIRS[:5]), hair_style="fringe", eyes=s.r.choice(EYES))
    tunic(s, "#8A6A3A", sleeves=6)
    hose(s, "#4A4A3A")
    shoes(s, "#2A1A0A")
    hat(s, "#6A5A4A", 2)


def chef(s):  # the cook
    person(s, s.r.choice(TONES), s.r.choice(HAIRS[:6]), eyes=s.r.choice(EYES), beard=s.r.choice([None, "#4A2E1A"]))
    tunic(s, "#E8E2D0", sleeves=8)
    apron(s, "#F4F4F0", 2)
    hose(s, "#4A3A2A")
    shoes(s, "#2A1A0A")
    coif(s, "#F4F4F0")


def farmer(s):
    person(s, s.r.choice(TONES[1:]), s.r.choice(HAIRS[:5]), eyes=s.r.choice(EYES), beard=s.r.choice([None, "#7A4E2A"]))
    tunic(s, s.r.choice(["#8A7A4A", "#6A5A3A", "#7A6A5A"]), sleeves=8)
    hose(s, "#4A3A2A")
    shoes(s, "#4A3A2A", 3)
    hat(s, "#D8B860", 2, brim="#C8A850", band="#8A5A2A")


def bellhop(s):  # the inn's servant, in livery
    person(s, s.r.choice(TONES), "#2B1D14", hair_style="fringe")
    tunic(s, "#A81A2A")
    s.band("body", 0, 1, hexc("#E0B53A"))
    hose(s, "#1A1A1A")
    shoes(s, "#111111")
    hat(s, "#A81A2A", 2)


def mayor(s):  # lord mayor
    person(s, "#E8B894", "#E8E8E8", beard="#E8E8E8")
    robe(s, "#7A1A2A")
    s.band("body", 0, 2, hexc("#EDEDE6"), layer=1)  # ermine collar
    for x in range(1, 7):  # chain of office
        s.dot("body", "front", x, int(2 + abs(x - 3.5) // 2), hexc("#E0B53A"), layer=1)
    s.dot("body", "front", 3, 4, hexc("#E0B53A"), layer=1)
    s.dot("body", "front", 4, 4, hexc("#E0B53A"), layer=1)
    shoes(s, "#111111")
    hat(s, "#7A1A2A", 2, band="#EDEDE6")


def crier(s):
    person(s, "#E8B894", "#B8863B", beard="#B8863B")
    tunic(s, "#8A1A1A")
    tabard(s, "#E0B53A", emblem="#8A1A1A")
    hose(s, "#F0E8D0")
    shoes(s, "#111111", 4)
    hat(s, "#111111", 2, brim="#111111", band="#E0B53A")
    feather(s, "#E8E8E8")


def enchanter(s):
    person(s, "#F2CDB0", "#E8E8E8", hair_style="long", beard="#E8E8E8")
    robe(s, "#3A1A6A")
    for y in (2, 6, 9):
        s.dot("body", "front", 2, y, hexc("#C8A8F8"))
        s.dot("body", "front", 5, y + 1, hexc("#C8A8F8"))
    hood(s, "#3A1A6A")


def captain(s):  # ship's captain of the old harbour
    person(s, "#D19A72", "#4A4A4A", beard="#4A4A4A")
    tunic(s, "#1A2A4A")
    buttons(s, "#E0B53A", 2, (2, 4, 6, 8))
    buttons(s, "#E0B53A", 5, (2, 4, 6, 8))
    cloak(s, "#2A1A1A")
    hose(s, "#2A2A2A")
    shoes(s, "#111111", 4)
    hat(s, "#1A1A1A", 2, brim="#1A1A1A", band="#E0B53A")


def elder(s):
    person(s, "#D19A72", "#E8E8E8", hair_style="long", beard="#E8E8E8")
    robe(s, "#6A5A4A")
    belt(s, "#3A2A1A", "#3A2A1A")


def questmaster(s):  # Quest Master Bram: an adventurer in a green tunic
    person(s, "#D19A72", "#7A4E2A", beard="#7A4E2A")
    tunic(s, "#2E5A2A")
    s.band("body", 0, 12, hexc("#4A2E1A"), sides=("left",))  # strap
    for i in range(12):
        s.dot("body", "front", 7 - min(7, i * 8 // 12), i, hexc("#4A2E1A"), layer=1)
    cloak(s, "#5A3A1A")
    hose(s, "#4A3A2A")
    shoes(s, "#3A2A1A", 4)
    hat(s, "#2E5A2A", 2)
    feather(s, "#C82A2A")


def resident(s, female, child=False):
    tone = s.r.choice(TONES)
    hair = s.r.choice(HAIRS if not child else HAIRS[:5] + HAIRS[7:])
    style = s.r.choice(["long", "ponytail", "bun", "long"] if female else ["short", "fringe", "short", "bald"] if not child else ["short", "fringe"])
    beard = None if female or child else s.r.choice([None, None, None, hair])
    person(s, tone, hair, style, eyes=s.r.choice(EYES), beard=beard)
    top = s.r.choice(EARTH)
    if female:
        kirtle(s, top, apron_c=s.r.choice([None, "#E8E2D0", "#D8D0B8"]))
        if s.r.random() < 0.5:
            wimple(s) if not child else coif(s)
        elif s.r.random() < 0.3:
            hood(s, s.r.choice(EARTH))
    else:
        tunic(s, top, sleeves=s.r.choice([6, 12]))
        hose(s, s.r.choice(["#4A3A2A", "#3A3A2A", "#5A4A3A", "#2A2A2A", "#6A5A3A"]))
        if s.r.random() < 0.3:
            hood(s, s.r.choice(EARTH))
        elif s.r.random() < 0.3:
            hat(s, s.r.choice(["#5A4A3A", "#3A3A2A", "#6A3A2A"]), 2)
        if not child and s.r.random() < 0.25:
            cloak(s, s.r.choice(EARTH))
    shoes(s, s.r.choice(["#2A1A0A", "#3A2A1A", "#4A3A2A"]), s.r.choice([2, 3]))


def passenger(s):
    resident(s, s.r.random() < 0.5)


CAST = {
    "officer": officer, "warden": warden, "sheriff": sheriff, "agent": agent, "judge": judge,
    "lawyer": lawyer, "lawyer_f": lawyer_f, "banker": banker, "bartender": bartender, "croupier": croupier,
    "croupier_f": croupier_f, "bouncer": bouncer, "bandit": bandit, "thief": thief, "fence": fence,
    "gossip": gossip, "traveller": traveller, "courier": courier, "conductor": conductor, "editor": editor,
    "newsboy": newsboy, "chef": chef, "farmer": farmer, "bellhop": bellhop, "mayor": mayor, "crier": crier,
    "enchanter": enchanter, "captain": captain, "elder": elder, "questmaster": questmaster,
}
VARIANTS = {
    "merchant": (6, merchant),
    "worker": (6, lambda s: resident(s, False) or apron(s, "#6A4A2A", 3)),
    "gang_vipers": (4, lambda s: gangster(s, "#7A1A1A", "#2A1A1A", "#1A1A1A")),
    "gang_jackals": (4, lambda s: gangster(s, "#5A5A5A", "#3A3A3A", None)),
    "resident_m": (12, lambda s: resident(s, False)),
    "resident_f": (12, lambda s: resident(s, True)),
    "child": (8, lambda s: resident(s, s.r.random() < 0.5, child=True)),
}

def build():
    names = []
    for i, (name, fn) in enumerate(sorted(CAST.items())):
        s = Skin(1000 + i)
        fn(s)
        s.save(name)
        names.append(name)
    for j, (name, (count, fn)) in enumerate(sorted(VARIANTS.items())):
        for k in range(count):
            s = Skin(5000 + j * 100 + k)
            fn(s)
            s.save(f"{name}_{k}")
            names.append(f"{name}_{k}")
    return names


def front(img, scale=6):
    """A flat front view (base + overlay) of one skin, 16x32 pixels before scaling."""
    out = Image.new("RGBA", (16, 32), (0, 0, 0, 0))

    def put(part, dx, dy):
        for layer in (0, 1):
            fx, fy, fw, fh = faces(*BOX[part][layer])["front"]
            region = img.crop((fx, fy, fx + fw, fy + fh))
            out.alpha_composite(region, (dx, dy))

    put("head", 4, 0)
    put("body", 4, 8)
    put("rarm", 0, 8)
    put("larm", 12, 8)
    put("rleg", 4, 20)
    put("lleg", 8, 20)
    return out.resize((16 * scale, 32 * scale), Image.NEAREST)


def preview(names, path):
    cols = 12
    rows = (len(names) + cols - 1) // cols
    sheet = Image.new("RGBA", (cols * 110, rows * 210), (54, 57, 63, 255))
    for i, n in enumerate(names):
        img = Image.open(os.path.join(OUT, n + ".png"))
        sheet.alpha_composite(front(img), ((i % cols) * 110 + 7, (i // cols) * 210 + 6))
    sheet.save(path)


if __name__ == "__main__":
    names = build()
    print(f"{len(names)} skins written to {OUT}")
    if "--preview" in sys.argv:
        preview(names, sys.argv[sys.argv.index("--preview") + 1])
