"""Development tool, never part of the build: makes or extends a patch in patches/.

usage: python tools/mkpatch.py <patch name, e.g. 96-cmd-rooms> <edits.py>

It copies the original game files to a/, copies them to b/, applies the patch's current version (if the patch exists)
to b/, applies the new edits to b/, and writes `diff -ru -U2 a b` as patches/<name>.patch. So:
- FILES lists every game file the patch covers, old and new ones alike;
- EDITS holds only the NEW edits, each one of:
  (path, old, new)                         exact text, must occur once; write it with LF line ends, it is matched with
                                           the file's own line ends (most game files are CRLF, a few LF)
  ("lines", path, pattern, repl, count)    regex on every non-comment line; `count` replacements in all
  ("static", path, cls)                    the one static block that draws from RND gets its own fixed generator
Each game file belongs to one patch; the build fails if two patches touch one file."""
import os
import pathlib
import re
import runpy
import shutil
import subprocess
import sys
import tempfile

SRC = pathlib.Path(os.environ["SOS_GAME_DIR"]) / "info" / "SongsOfSyx-sources"
REPO = pathlib.Path(__file__).resolve().parent.parent  # the repository (or worktree) this copy lives in
CR, LF = bytes([13]), bytes([10])
CRLF = chr(13) + chr(10)


def static_block(s, cls):
    """Rewrites the static block that draws RND: a fixed per-class generator instead (SOS-32)."""
    for m in re.finditer(r"static\s*\{", s):
        depth, i = 0, m.end() - 1
        while True:
            if s[i] == "{":
                depth += 1
            elif s[i] == "}":
                depth -= 1
                if depth == 0:
                    break
            i += 1
        body = s[m.end():i]
        if "RND." not in body:
            continue
        b = body
        b = b.replace("DEG.setRandom()", "DEG.setRandom(sosdetR)")
        b = b.replace("RND.rFloat()", "sosdetR.nextFloat()")
        b = b.replace("RND.rSign()", "(sosdetR.nextBoolean() ? 1.0 : -1.0)")
        b = re.sub(r"RND\.rInt0\((\w+)\)", r"(-(\1) + sosdetR.nextInt((\1)*2 + 1))", b)
        if "RND." in b:
            sys.exit("%s: RND left in the static block: %s" % (cls, b))
        decl = (CRLF + "\t\t// SOSDET: a fixed generator per class: the same table in every process, and nothing drawn from"
                + CRLF + "\t\t// the simulation's stream whenever the class happens to load (SOS-32)" + CRLF
                + "\t\tjava.util.Random sosdetR = sosdet.Hooks.tableRandom(" + cls + ".class);")
        return s[:m.end()] + decl + b + s[i:]
    sys.exit(cls + ": no static block draws RND")


name, edits_file = sys.argv[1], sys.argv[2]
spec = runpy.run_path(edits_file)
files, edits = spec["FILES"], spec["EDITS"]
patch = REPO / "patches" / (name + ".patch")

work = pathlib.Path(tempfile.mkdtemp())
for side in ("a", "b"):
    for f in files:
        dst = work / side / f
        dst.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(SRC / f, dst)
if patch.exists():
    r = subprocess.run(["git", "-c", "core.autocrlf=false", "apply", "-p1", str(patch)], cwd=work / "b", capture_output=True, text=True)
    if r.returncode != 0:
        sys.exit("cannot apply the current patch: " + r.stdout + r.stderr)
for e in edits:
    if e[0] == "lines":
        _, path, pattern, repl, want = e
        p = work / "b" / path
        lines = p.read_bytes().decode("utf-8").split(chr(10))  # CRLF or LF files alike
        n = 0
        for k, line in enumerate(lines):
            if line.lstrip().startswith("//"):
                continue
            lines[k], c = re.subn(pattern, repl, line)
            n += c
        if n != want:
            sys.exit("%s: %d replacements of %s, expected %d" % (path, n, pattern, want))
        p.write_bytes(chr(10).join(lines).encode("utf-8"))
    elif e[0] == "static":
        _, path, cls = e
        p = work / "b" / path
        p.write_bytes(static_block(p.read_bytes().decode("utf-8"), cls).encode("utf-8"))
    else:
        path, old, new = e
        p = work / "b" / path
        s = p.read_bytes().decode("utf-8")
        if CRLF in s:  # most game files are CRLF; a few are LF and keep it
            o, n = old.replace(chr(10), CRLF), new.replace(chr(10), CRLF)
        else:
            o, n = old, new
        count = s.count(o)
        if count != 1:
            sys.exit("%s: %d matches for:\n%s" % (path, count, old))
        p.write_bytes(s.replace(o, n).encode("utf-8"))
r = subprocess.run(["diff", "-ru", "-U2", "a", "b"], cwd=work, capture_output=True)
if r.returncode not in (0, 1):
    sys.exit(r.stderr.decode())
out = r.stdout  # as diff writes it: each changed line keeps its file's own line ending (most CRLF, a few LF)
patch.write_bytes(out)
print("wrote %s: %d bytes, %d files" % (patch.name, len(out), out.count(b"diff -ru")))
shutil.rmtree(work)
