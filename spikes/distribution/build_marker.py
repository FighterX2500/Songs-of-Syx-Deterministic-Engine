"""SOS-25 spike: build a test mod whose jar shadows two game classes.

Compiles two game source files with a one-line marker each, using ECJ 3.40.0
(ADR 0001 flags) on the JDK in JAVA_HOME, against the shipped SongsOfSyx.jar:

- init/MainProcess.java at --release 16 (loaded first, as the main class)
- snake2d/util/rnd/RND.java at --release 8 (static initializer; B10 target)

Before patching, each file is compiled unchanged and compared byte for byte
with the shipped classes, so the patched classes differ only by the marker.

Output (git-ignored): build/mod/SOSDET-spike/_Info.txt and
build/mod/SOSDET-spike/V71/script/SOSDET-spike.jar.

Usage: python build_marker.py [--ecj path/to/ecj-3.40.0.jar]
"""
import argparse
import os
import pathlib
import shutil
import subprocess
import sys
import zipfile

HERE = pathlib.Path(__file__).resolve().parent
BUILD = HERE / "build"
DEFAULT_ECJ = pathlib.Path(r"C:\ClaudeFolder\SOS\spikes\rebuild-check\tools\ecj-3.40.0.jar")
MOD_NAME = "SOSDET-spike"


def marker(tag):
    """Java statement that writes %APPDATA%/songsofsyx/sosdet-spike/<tag>.txt.

    Steam starts the game with javaw, so stdout is lost; a file is the evidence.
    It records the time, the thread, the class path and the stack that ran it.
    Java 8 API only (RND is compiled at --release 8).
    """
    return (
        '\t\ttry {\n'
        '\t\t\tjava.nio.file.Path sosdetDir = java.nio.file.Paths.get(System.getenv("APPDATA"), "songsofsyx", "sosdet-spike");\n'
        '\t\t\tjava.nio.file.Files.createDirectories(sosdetDir);\n'
        '\t\t\tStringBuilder sosdetSb = new StringBuilder();\n'
        '\t\t\tsosdetSb.append("marker: patched ' + tag + ' from the mod jar\\n");\n'
        '\t\t\tsosdetSb.append("time: ").append(System.currentTimeMillis()).append("\\n");\n'
        '\t\t\tsosdetSb.append("thread: ").append(Thread.currentThread().getName()).append("\\n");\n'
        '\t\t\tsosdetSb.append("java.class.path: ").append(System.getProperty("java.class.path")).append("\\n");\n'
        '\t\t\tfor (StackTraceElement sosdetE : new Throwable().getStackTrace())\n'
        '\t\t\t\tsosdetSb.append("  at ").append(sosdetE).append("\\n");\n'
        '\t\t\tjava.nio.file.Files.write(sosdetDir.resolve("' + tag + '.txt"), sosdetSb.toString().getBytes("UTF-8"));\n'
        '\t\t} catch (java.io.IOException sosdetEx) {\n'
        '\t\t\tthrow new RuntimeException("SOSDET-spike marker failed", sosdetEx);\n'
        '\t\t}\n'
    )


# (source path in the game source, release, text to find, text to put after it)
UNITS = [
    ("init/MainProcess.java", "16",
     'LOG.ln("* GAME " + VERSION.VERSION_STRING);\n',
     marker("MainProcess")),
    ("snake2d/util/rnd/RND.java", "8",
     'Printer.ln("[RND] ---Initiating random, seed: " + seed);\n',
     marker("RND")),
]

INFO = """VERSION: "0.0.1",
NAME: "SOSDET spike",
DESC: "SOS-25 test: shadows two game classes with marker lines. Not for play.",
AUTHOR: "SOSDET",
INFO: "https://github.com/FighterX2500/SOSDET",
"""


def fail(msg):
    print("build_marker: " + msg, file=sys.stderr)
    sys.exit(1)


def compile_file(java, ecj, release, src_file, game_jar, out_dir, use_release=True):
    """use_release=False uses -source/-target instead of --release, which needs
    the JDK's lib/ct.sym; the game's bundled JRE has none (option C)."""
    if out_dir.exists():
        shutil.rmtree(out_dir)
    out_dir.mkdir(parents=True)
    if use_release:
        level = ["--release", release]
    else:
        v = "1.8" if release == "8" else release
        level = ["-source", v, "-target", v]
    cmd = [str(java), "-jar", str(ecj)] + level + ["-encoding", "UTF-8",
           "-g", "-preserveAllLocals", "-nowarn", "-proc:none",
           "-cp", str(game_jar), "-d", str(out_dir)] + (
               [src_file] if isinstance(src_file, str) else [str(src_file)])
    r = subprocess.run(cmd, capture_output=True, text=True)
    if r.returncode != 0:
        fail("ECJ failed for %s:\n%s%s" % (src_file, r.stdout, r.stderr))
    return sorted(p.relative_to(out_dir).as_posix() for p in out_dir.rglob("*.class"))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--ecj", default=str(DEFAULT_ECJ))
    ap.add_argument("--java", choices=["jdk", "bundled"], default="jdk",
                    help="jdk: JAVA_HOME (has ct.sym for --release); bundled: the game's JRE (option C)")
    args = ap.parse_args()

    game = os.environ.get("SOS_GAME_DIR")
    if not game:
        fail("SOS_GAME_DIR is not set")
    game = pathlib.Path(game)
    game_jar = game / "SongsOfSyx.jar"
    sources = game / "info" / "SongsOfSyx-sources"
    if args.java == "bundled":
        java = game / "jre" / "bin" / "java.exe"
    else:
        home = os.environ.get("JAVA_HOME")
        if not home:
            fail("JAVA_HOME is not set (need JDK 21+)")
        java = pathlib.Path(home) / "bin" / "java.exe"
    ecj = pathlib.Path(args.ecj)
    for p in (game_jar, sources, java, ecj):
        if not p.exists():
            fail("missing: %s" % p)

    if BUILD.exists():
        shutil.rmtree(BUILD)
    shipped = zipfile.ZipFile(game_jar)
    patched_classes = {}

    for rel, release, anchor, marker in UNITS:
        original = (sources / rel).read_text(encoding="utf-8")
        if original.count(anchor) != 1:
            fail("%s: anchor found %d times, expected once" % (rel, original.count(anchor)))

        # Control: the unchanged file must rebuild the shipped classes exactly.
        src_dir = BUILD / "src-orig"
        (src_dir / rel).parent.mkdir(parents=True, exist_ok=True)
        (src_dir / rel).write_text(original, encoding="utf-8")
        out = BUILD / "orig" / rel
        classes = compile_file(java, ecj, release, src_dir / rel, game_jar, out)
        for c in classes:
            same = (out / c).read_bytes() == shipped.read(c)
            print("control %-45s %s" % (c, "identical" if same else "DIFFERS"))
            if not same:
                fail("unchanged %s does not rebuild %s byte for byte" % (rel, c))

        # Patched: one marker line after the anchor.
        src_dir = BUILD / "src-patched"
        (src_dir / rel).parent.mkdir(parents=True, exist_ok=True)
        (src_dir / rel).write_text(original.replace(anchor, anchor + marker), encoding="utf-8")
        out = BUILD / "patched" / rel
        patched = compile_file(java, ecj, release, src_dir / rel, game_jar, out)
        if patched != classes:
            fail("%s: patched build gives other classes: %s vs %s" % (rel, patched, classes))
        for c in patched:
            patched_classes[c] = (out / c).read_bytes()
            print("patched %-45s %d bytes" % (c, len(patched_classes[c])))

    mod = BUILD / "mod" / MOD_NAME
    (mod / "V71" / "script").mkdir(parents=True)
    (mod / "_Info.txt").write_text(INFO, encoding="utf-8")
    jar = mod / "V71" / "script" / (MOD_NAME + ".jar")
    with zipfile.ZipFile(jar, "w", zipfile.ZIP_DEFLATED) as z:
        for name in sorted(patched_classes):
            z.writestr(name, patched_classes[name])
    print("built %s (%d classes)" % (jar, len(patched_classes)))


if __name__ == "__main__":
    main()
