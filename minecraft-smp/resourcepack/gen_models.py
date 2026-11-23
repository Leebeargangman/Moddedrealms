#!/usr/bin/env python3
"""Generates the train models (locomotive, carriage) and the item art for the Moddedrealms resource pack.

The train models are item models shown by item displays that follow the minecarts. Coordinates are model units
(16 = one block); the front of the locomotive is at -z (an item display shows a model turned 180 degrees, so a display
facing the direction of travel puts the front first). `python3 gen_models.py --preview out.png` draws side views.
"""
import json
import os
import random
import sys

from PIL import Image, ImageDraw

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "src/assets/moddedrealms")
TEX = 64  # train texture size in pixels; uv units are TEX/16 pixels

SWATCH = {
    "body": "#7A1F2A", "body_dark": "#5A1520", "gold": "#D9A93A", "black": "#151515", "under": "#2E2E30",
    "wheel": "#A8242A", "silver": "#B8BCC0", "roof": "#4A4A4E", "boiler_dark": "#1E3A2A", "boiler": "#2A5A3E",
    "brass": "#C8A040", "wood": "#6A4A2A", "cream": "#E8DCC0", "lamp": "#FFE680", "coal": "#202020", "rust": "#7A4A2A",
}
NAMES = list(SWATCH)


def hexc(s, a=255):
    s = s.lstrip("#")
    return tuple(int(s[i:i + 2], 16) for i in (0, 2, 4)) + (a,)


def train_texture():
    r = random.Random(7)
    img = Image.new("RGBA", (TEX, TEX), (0, 0, 0, 0))
    # carriage side (64x16 px, uv 0,0 -> 16,4): body colour, a gold line, four windows with frames
    for y in range(16):
        for x in range(64):
            c = hexc(SWATCH["body"])
            if y in (1, 14):
                c = hexc(SWATCH["gold"])
            if y == 0 or y == 15:
                c = hexc(SWATCH["body_dark"])
            img.putpixel((x, y), tuple(max(0, min(255, v + r.randint(-5, 5))) for v in c[:3]) + (255,))
    for i in range(4):
        x0 = 4 + i * 15
        for y in range(3, 11):
            for x in range(x0, x0 + 11):
                edge = y in (3, 10) or x in (x0, x0 + 10)
                img.putpixel((x, y), hexc(SWATCH["wood"]) if edge else (0, 0, 0, 0))
    # carriage end (16x16 px at 0,16; uv 0,4 -> 4,8): a door with a window
    for y in range(16):
        for x in range(16):
            c = hexc(SWATCH["body"])
            if 5 <= x <= 10 and y >= 2:
                c = hexc(SWATCH["wood"])
                if 3 <= y <= 7 and 6 <= x <= 9:
                    c = (0, 0, 0, 0)
            if y in (0, 15):
                c = hexc(SWATCH["body_dark"])
            img.putpixel((x, 16 + y), c)
    # boiler side with rivets (16x16 at 16,16; uv 4,4 -> 8,8)
    for y in range(16):
        for x in range(16):
            c = hexc(SWATCH["boiler"])
            if x % 5 == 0:
                c = hexc(SWATCH["brass"]) if y % 4 == 1 else hexc(SWATCH["boiler_dark"])
            img.putpixel((16 + x, 16 + y), tuple(max(0, min(255, v + r.randint(-4, 4))) for v in c[:3]) + (255,))
    # wheel face (16x16 at 32,16; uv 8,4 -> 12,8): red disc with spokes on black
    for y in range(16):
        for x in range(16):
            dx, dy = x - 7.5, y - 7.5
            d = (dx * dx + dy * dy) ** 0.5
            c = hexc(SWATCH["black"])
            if d < 7.6:
                c = hexc(SWATCH["wheel"])
            if d < 6.2 and (abs(dx) < 0.8 or abs(dy) < 0.8 or abs(abs(dx) - abs(dy)) < 0.9):
                c = hexc(SWATCH["silver"])
            if d < 1.6:
                c = hexc(SWATCH["brass"])
            img.putpixel((32 + x, 16 + y), c)
    # swatches: 4x4 px from y=32 (uv: index%16, 8 + index//16)
    for i, n in enumerate(NAMES):
        for y in range(4):
            for x in range(4):
                c = hexc(SWATCH[n])
                img.putpixel(((i % 16) * 4 + x, 32 + (i // 16) * 4 + y), tuple(max(0, min(255, v + r.randint(-4, 4))) for v in c[:3]) + (255,))
    return img


def sw(name):
    i = NAMES.index(name)
    u, v = i % 16, 8 + i // 16
    return [u + 0.1, v + 0.1, u + 0.9, v + 0.9]


SIDE = [0, 0, 16, 4]
END = [0, 4, 4, 8]
BOILER = [4, 4, 8, 8]
WHEEL = [8, 4, 12, 8]


def box(frm, to, uv, faces=None, cull=None):
    """An element with every face using uv, except the ones given in faces (face -> uv, or None to leave it out)."""
    fs = {}
    for f in ("north", "south", "east", "west", "up", "down"):
        u = (faces or {}).get(f, uv)
        if u is None:
            continue
        fs[f] = {"uv": u, "texture": "#0"}
    return {"from": frm, "to": to, "faces": fs}


def carriage():
    els = []
    # underframe and wheels
    els.append(box([-1, 2, -6], [17, 5, 22], sw("under")))
    for z0 in (-4, 14):
        for x0, x1 in ((-2.5, -1), (17, 18.5)):
            wheel_side = {"east": WHEEL, "west": WHEEL}
            els.append(box([x0, 0, z0], [x1, 6, z0 + 6], sw("black"), wheel_side))
    # the lower body hides the minecart
    els.append(box([-4, 5, -8], [20, 14, 24], sw("body"), {"up": None}))
    els.append(box([-4, 13, -8], [20, 14, 24], sw("gold"), {"up": None, "down": None}))
    # walls with windows (thin, textured), end walls with doors
    els.append(box([-4, 14, -8], [-3, 29, 24], sw("body"), {"west": SIDE, "east": SIDE, "up": None, "down": None}))
    els.append(box([19, 14, -8], [20, 29, 24], sw("body"), {"west": SIDE, "east": SIDE, "up": None, "down": None}))
    els.append(box([-3, 14, -8], [19, 29, -7], sw("body"), {"north": END, "south": END, "up": None, "down": None}))
    els.append(box([-3, 14, 23], [19, 29, 24], sw("body"), {"north": END, "south": END, "up": None, "down": None}))
    # roof
    els.append(box([-5, 29, -9], [21, 30.5, 25], sw("roof")))
    els.append(box([-2, 30.5, -8], [18, 32, 24], sw("roof")))
    # buffers
    for z0, z1 in ((-10, -8), (24, 26)):
        for x0 in (-2, 15):
            els.append(box([x0, 6, z0], [x0 + 3, 9, z1], sw("silver")))
    return els


def locomotive():
    els = []
    els.append(box([-1, 2, -10], [17, 5, 24], sw("under")))
    # big driving wheels, small front wheels
    for z0, size in ((-6, 5), (6, 8), (15, 8)):
        for x0, x1 in ((-2.5, -1), (17, 18.5)):
            els.append(box([x0, 0, z0], [x1, size, z0 + size], sw("black"), {"east": WHEEL, "west": WHEEL}))
    # connecting rod
    for x0, x1 in ((-3, -2.5), (18.5, 19)):
        els.append(box([x0, 3, 8], [x1, 4, 21], sw("silver")))
    # boiler: an octagon from two boxes
    els.append(box([0, 7, -9], [16, 19, 13], sw("boiler"), {"east": BOILER, "west": BOILER}))
    els.append(box([2, 5, -9], [14, 21, 13], sw("boiler"), {"east": BOILER, "west": BOILER}))
    for z in (-3, 5):
        els.append(box([-0.3, 6.7, z], [16.3, 19.3, z + 1], sw("brass"), {"north": None, "south": None}))
    # smokebox front, lamp, chimney, dome
    els.append(box([1, 6, -10], [15, 20, -9], sw("coal")))
    els.append(box([6, 15, -11], [10, 18, -10], sw("lamp")))
    els.append(box([5, 21, -7], [11, 30, -1], sw("coal")))
    els.append(box([4, 29, -8], [12, 31, 0], sw("black")))
    els.append(box([6, 21, 4], [10, 25, 8], sw("brass")))
    # cowcatcher
    els.append(box([0, 1, -14], [16, 4, -10], sw("wheel")))
    els.append(box([2, 4, -12], [14, 6, -10], sw("wheel")))
    # cab
    els.append(box([-3, 5, 13], [19, 18, 24], sw("body")))
    els.append(box([-3, 18, 13], [-2, 26, 24], sw("body"), {"west": [2, 0, 5, 3], "east": [2, 0, 5, 3]}))
    els.append(box([18, 18, 13], [19, 26, 24], sw("body"), {"west": [2, 0, 5, 3], "east": [2, 0, 5, 3]}))
    els.append(box([-2, 18, 23], [18, 26, 24], sw("body")))
    els.append(box([-4, 26, 12], [20, 28, 25], sw("roof")))
    els.append(box([-3, 17.5, 13], [19, 18.5, 24.5], sw("gold"), {"up": None, "down": None}))
    # buffers
    for x0 in (-1, 14):
        els.append(box([x0, 6, 24], [x0 + 3, 9, 26], sw("silver")))
    return els


def write_model(name, elements, texture="moddedrealms:item/train"):
    path = os.path.join(ROOT, "models/item", name + ".json")
    os.makedirs(os.path.dirname(path), exist_ok=True)
    for e in elements:
        for i in range(3):
            assert -16 <= e["from"][i] <= 32 and -16 <= e["to"][i] <= 32, (name, e)
    with open(path, "w") as f:
        json.dump({"textures": {"0": texture, "particle": texture}, "elements": elements}, f, indent=1)
    item_def(name)


def item_def(name):
    path = os.path.join(ROOT, "items", name + ".json")
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as f:
        json.dump({"model": {"type": "minecraft:model", "model": "moddedrealms:item/" + name}}, f, indent=1)


def flat_item(name, painter):
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    painter(img)
    tex = os.path.join(ROOT, "textures/item", name + ".png")
    os.makedirs(os.path.dirname(tex), exist_ok=True)
    img.save(tex)
    path = os.path.join(ROOT, "models/item", name + ".json")
    with open(path, "w") as f:
        json.dump({"parent": "minecraft:item/handheld" if name == "bolt_cutters" else "minecraft:item/generated",
                   "textures": {"layer0": "moddedrealms:item/" + name}}, f, indent=1)
    item_def(name)


def pixels(img, rows, palette):
    for y, row in enumerate(rows):
        for x, ch in enumerate(row):
            if ch != ".":
                img.putpixel((x, y), hexc(palette[ch]))


def bolt_cutters(img):
    pixels(img, [
        "................",
        "............aa..",
        "...........abba.",
        "..........abbba.",
        "..........aabba.",
        ".........cc.aa..",
        "........cc......",
        ".......cc.......",
        "......cc........",
        ".....rr.........",
        "....rr.rr.......",
        "...rr...rr......",
        "..rr.....rr.....",
        ".rr.......rr....",
        ".r.........r....",
        "................"], {"a": "#5A5E62", "b": "#A8ACB0", "c": "#3A3A3E", "r": "#B82A2A"})


def loot_bag(img):
    pixels(img, [
        "................",
        "......tt.t......",
        ".......tt.......",
        "......bbbb......",
        ".....bccccb.....",
        "....bccccccb....",
        "...bccccccccb...",
        "...bcc$$$$ccb...",
        "..bccc$cc$cccb..",
        "..bccc$$$$cccb..",
        "..bcccccc$cccb..",
        "..bccc$$$$cccb..",
        "..bccccccccccb..",
        "...bccccccccb...",
        "....bbbbbbbb....",
        "................"], {"t": "#C8A050", "b": "#5A4A2A", "c": "#8A7A4A", "$": "#2A5A2A"})


def gold_bar(img):
    pixels(img, [
        "................",
        "................",
        "................",
        "................",
        "....hhhhhhhh....",
        "...hggggggggh...",
        "..hggggggggggd..",
        ".hggggMMggggggd.",
        ".dggggMMggggggd.",
        ".ddgggggggggggd.",
        "..ddddddddddddd.",
        "................",
        "................",
        "................",
        "................",
        "................"], {"h": "#FFF2A0", "g": "#E8B830", "d": "#A87A18", "M": "#C8961E"})


def newspaper(img):
    pixels(img, [
        "................",
        "..pppppppppppp..",
        "..pTTTTTTTTTTp..",
        "..pTTTTTTTTTTp..",
        "..pppppppppppp..",
        "..pkkkkp.lllpp..",
        "..pkkkkp.pppp...",
        "..pkkkkp.lllpp..",
        "..pppppp.pppp...",
        "..plllll.lllpp..",
        "..pppppp.pppp...",
        "..plllll.lllpp..",
        "..pppppp.pppp...",
        "..plllll.lllpp..",
        "..pppppppppppp..",
        "................"], {"p": "#EEE8D8", "T": "#1A1A1A", "k": "#6A6A6A", "l": "#9A9A9A"})


def preview(path):
    """Side and front views of both models from the element boxes (flat colours, for checking shapes)."""
    tex = train_texture()

    def colour(e):
        uv = next(iter(e["faces"].values()))["uv"]
        px = int((uv[0] + 0.5) * TEX / 16), int((uv[1] + 0.5) * TEX / 16)
        return tex.getpixel((min(TEX - 1, px[0]), min(TEX - 1, px[1])))

    s = 6
    sheet = Image.new("RGBA", (4 * 52 * s // 1, 52 * s), (150, 190, 230, 255))
    d = ImageDraw.Draw(sheet)
    for k, (name, els) in enumerate((("locomotive (side)", locomotive()), ("carriage (side)", carriage()))):
        ox = k * 2 * 52 * s + 10
        for e in sorted(els, key=lambda e: -e["to"][0]):
            (x0, y0, z0), (x1, y1, z1) = e["from"], e["to"]
            d.rectangle([ox + (z0 + 16) * s, (40 - y1) * s, ox + (z1 + 16) * s, (40 - y0) * s], fill=colour(e), outline=(0, 0, 0, 90))
        d.text((ox, 4), name + "  (front = left)", fill=(0, 0, 0, 255))
        ox2 = ox + 52 * s
        for e in sorted(els, key=lambda e: e["to"][2], reverse=True):
            (x0, y0, z0), (x1, y1, z1) = e["from"], e["to"]
            d.rectangle([ox2 + (x0 + 8) * s, (40 - y1) * s, ox2 + (x1 + 8) * s, (40 - y0) * s], fill=colour(e), outline=(0, 0, 0, 90))
        d.text((ox2, 4), "front view", fill=(0, 0, 0, 255))
    sheet.save(path)


def build():
    tex = os.path.join(ROOT, "textures/item/train.png")
    os.makedirs(os.path.dirname(tex), exist_ok=True)
    train_texture().save(tex)
    write_model("train_engine", locomotive())
    write_model("train_carriage", carriage())
    flat_item("bolt_cutters", bolt_cutters)
    flat_item("loot_bag", loot_bag)
    flat_item("gold_bar", gold_bar)
    flat_item("newspaper", newspaper)


if __name__ == "__main__":
    build()
    print("models written to", ROOT)
    if "--preview" in sys.argv:
        preview(sys.argv[sys.argv.index("--preview") + 1])
