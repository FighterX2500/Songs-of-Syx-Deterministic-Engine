"""SOS-25 spike, option C: build the patched files on a player's PC.

Uses only what a Steam install has: the bundled JRE, SongsOfSyx.jar and
info/SongsOfSyx-sources.jar (zipped there, unpacked in the dev copy), plus
ECJ, which players would have to get from us. Compiles the 32 files the
spike patches touch, unchanged, with -source/-target (the bundled JRE has no
ct.sym for --release), checks each class against the shipped one and times it.

Usage: python option_c.py [steam game folder]
"""
import pathlib
import sys
import time
import zipfile

import build_marker as bm
import scan_test

DEFAULT_STEAM = pathlib.Path(r"C:\Program Files (x86)\Steam\steamapps\common\Songs of Syx")


def main():
    game = pathlib.Path(sys.argv[1]) if len(sys.argv) > 1 else DEFAULT_STEAM
    java = game / "jre" / "bin" / "java.exe"
    game_jar = game / "SongsOfSyx.jar"
    src_jar = game / "info" / "SongsOfSyx-sources.jar"
    for p in (java, game_jar, src_jar, bm.DEFAULT_ECJ):
        if not p.exists():
            bm.fail("missing: %s" % p)

    t0 = time.perf_counter()
    src_dir = bm.BUILD / "c-src"
    with zipfile.ZipFile(src_jar) as z:
        names = set(z.namelist())
        for rel in scan_test.FILES:
            # The sources jar may keep files under a prefix; find the entry.
            hits = [n for n in names if n == rel or n.endswith("/" + rel)]
            if len(hits) != 1:
                bm.fail("%s: %d entries in %s" % (rel, len(hits), src_jar))
            (src_dir / rel).parent.mkdir(parents=True, exist_ok=True)
            (src_dir / rel).write_bytes(z.read(hits[0]))
    t_unpack = time.perf_counter() - t0

    shipped = zipfile.ZipFile(game_jar)
    t0 = time.perf_counter()
    count = 0
    differ = []
    for rel in scan_test.FILES:
        release = "8" if rel.startswith("snake2d/") else "16"
        out = bm.BUILD / "c-out" / rel
        for c in bm.compile_file(java, bm.DEFAULT_ECJ, release, src_dir / rel, game_jar, out,
                                 use_release=False):
            count += 1
            if (out / c).read_bytes() != shipped.read(c):
                differ.append(c)
    t_compile = time.perf_counter() - t0

    print("unpack %d files from the sources jar: %.2f s" % (len(scan_test.FILES), t_unpack))
    print("compile %d files (%d classes) on the bundled JRE, one ECJ run per file: %.1f s"
          % (len(scan_test.FILES), count, t_compile))
    print("classes not byte-identical to shipped: %d %s" % (len(differ), differ[:10]))


if __name__ == "__main__":
    main()
