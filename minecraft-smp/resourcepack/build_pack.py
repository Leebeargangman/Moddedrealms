#!/usr/bin/env python3
"""Builds the server resource pack: the MythicDepths pack (its resourcepack.zip) plus everything in src/.

    python3 build_pack.py <MythicDepths resourcepack.zip> <out.zip>

Prints the SHA-1 to put in server.properties (resource-pack-sha1). Files from src/ win if both have the same path,
except pack.mcmeta, which is written fresh with both descriptions. Entries get a fixed timestamp, so the same inputs
always give the same zip (and the same SHA-1).
"""
import hashlib
import json
import os
import sys
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(HERE, "src")
STAMP = (2026, 1, 1, 0, 0, 0)


def main(base, out):
    files = {}
    meta = {"pack": {"description": "", "min_format": 75, "max_format": 99}}
    if base:
        with zipfile.ZipFile(base) as z:
            for n in z.namelist():
                if n.endswith("/"):
                    continue
                if n == "pack.mcmeta":
                    meta = json.loads(z.read(n))
                    continue
                files[n] = z.read(n)
    for root, _, names in os.walk(SRC):
        for f in names:
            p = os.path.join(root, f)
            files[os.path.relpath(p, SRC).replace(os.sep, "/")] = open(p, "rb").read()
    meta["pack"]["description"] = [{"text": "Moddedrealms", "color": "#E0B53A"},
                                   {"text": " - townsfolk, the Meadowbrook Line, bosses & relics", "color": "gray"}]
    files["pack.mcmeta"] = json.dumps(meta, indent=1).encode()
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
        for n in sorted(files):
            info = zipfile.ZipInfo(n, STAMP)
            info.compress_type = zipfile.ZIP_DEFLATED
            z.writestr(info, files[n])
    sha = hashlib.sha1(open(out, "rb").read()).hexdigest()
    print(f"{out}: {len(files)} files, {os.path.getsize(out)} bytes, sha1 {sha}")
    return sha


if __name__ == "__main__":
    if len(sys.argv) < 3:
        sys.exit(__doc__)
    main(sys.argv[1] if sys.argv[1] != "-" else None, sys.argv[2])
