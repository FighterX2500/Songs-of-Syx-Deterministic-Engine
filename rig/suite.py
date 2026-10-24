"""SOSDET scenario suite (SOS-31): every scenario in one local command, pass or fail.

    python rig\\suite.py            the full suite (about 55 minutes)
    python rig\\suite.py --short    the short one, the local check before each pull request (about 5 minutes)
    python rig\\suite.py --record   run each scenario as a host and keep its expected hashes in rig\\golden
    python rig\\suite.py --dry-run  print the rig commands and check the recordings, without starting a game

Each scenario is a rig.py run: several copies at different speeds, compared at every step, with the engines' state
hashes. A recorded scenario is also checked against its expected hashes: --record runs it with copy 1 as the host and
keeps the host's AI log (the general's changes and the player's actions, stamped at their steps) and copy 1's hash
every EVERY steps. A later run replays that log on every copy, so the same steps must give the same hashes; a change
that alters the simulation alike on every copy fails there. A change meant to alter it: record again and say so in
the pull request. A recording names its start (the save's checksum) and the engine's log and hash formats; when
either differs, the scenario says to record again instead of failing.

The own-city world can't be replayed (rig.py --follow runs a host), so it is checked by its copies only.
"""
import argparse
import gzip
import os
import pathlib
import re
import shutil
import subprocess
import sys
import time
import zlib

ROOT = pathlib.Path(__file__).resolve().parent.parent
RIG = ROOT / "rig" / "rig.py"
GOLDEN = ROOT / "rig" / "golden"
RUNS = ROOT / "build" / "rig" / "runs"
HOST_LOG = ROOT / "build" / "rig" / "inst" / "1" / "songsofsyx" / "sosdet-rig" / "ai-host.log"
SAVES = pathlib.Path(os.environ["APPDATA"]) / "songsofsyx" / "saves" / "saves"  # as rig.py's
MOGUS = "Mogus-72-19f86f1e048-47002c-0-121c.save"
EVERY = 300  # steps between expected hashes
FORMATS = {"ai": ("src/main/java/sosdet/ai/AiLog.java", "FORMAT_VERSION"),
           "hash": ("src/main/java/sosdet/hash/HashLog.java", "FORMAT_VERSION")}

# name, rig.py arguments for the full and the short suite (None: not in it), recorded (replayed against expected hashes)
SCENARIOS = [
    ("field", "field --copies 3 --speeds 5,7,10 --steps 36000",
     "field --copies 3 --speeds 5,7,10 --steps 3000", True),
    ("assault", "city --save %s --copies 3 --speeds 5,7,7 --steps 36000" % MOGUS,
     "city --save %s --copies 3 --speeds 5,7,7 --steps 6000" % MOGUS, True),
    ("ownworld", "city --save %s --copies 3 --speeds 5,7,7 --steps 108000 --no-invade --follow --world-resync 3000"
                 " --cmds @rig\\cmds\\m3.txt" % MOGUS,
     "city --save %s --copies 3 --speeds 5,7,7 --steps 6000 --no-invade --follow --world-resync 1500" % MOGUS, False),
    ("mapbattle", "city --save %s --copies 2 --speeds 5,7 --steps 36000 --no-invade --engage 900"
                  " --cmds \"wbesiegenear 0 stronger\"" % MOGUS, None, True),
]


def log(msg):
    print("[suite %s] %s" % (time.strftime("%H:%M:%S"), msg), flush=True)


def split(args):
    """rig.py arguments as a list; a double-quoted part stays one argument."""
    return [a.strip('"') for a in re.findall(r'"[^"]*"|\S+', args)]


def formats():
    out = {}
    for key, (path, name) in FORMATS.items():
        m = re.search(r"static final int %s = (\d+);" % name, (ROOT / path).read_text(encoding="utf-8"))
        if not m:
            sys.exit("suite: no %s in %s" % (name, path))
        out[key] = int(m.group(1))
    return out


def crc(path):
    return "%x" % zlib.crc32(path.read_bytes())


def summary(tag):
    path = RUNS / tag / "summary.txt"
    return path.read_text(encoding="utf-8") if path.is_file() else ""


def copies_agree(text):
    """rig.py's verdict: every copy identical, and no engine found a desync (its exit code covers only the first)."""
    m = re.search(r"^RESULT: (.*)$", text, re.M)
    return bool(m) and m.group(1).startswith("all copies identical") and "engine hashes agree" in m.group(1)


def first_line(text, prefix):
    for line in text.splitlines():
        if line.startswith(prefix):
            return line
    return ""


def hashes(csv):
    """Copy 1's hash every EVERY steps and at its last step, by step; 'end' is its whole end state's hash."""
    rows = [l.split(",") for l in csv.read_text(encoding="utf-8").splitlines()[1:] if l]
    steps = [r for r in rows if r[0].isdigit()]
    picked = {r[0]: r[1] for r in steps if int(r[0]) % EVERY == 0}
    if steps:
        picked[steps[-1][0]] = steps[-1][1]
    picked.update({r[0]: r[1] for r in rows if r[0] == "end"})
    return picked


def in_order(picked):
    return sorted(picked, key=lambda k: (k == "end", int(k) if k.isdigit() else 0))


def start_hash(text):
    m = re.search(r"^copy 1 at speed \S+: start (\S+),", text, re.M)
    return m.group(1) if m else ""


class Golden:
    """A recording: rig\\golden\\<name>.txt (header, then 'step hash' lines) and <name>.log.gz, the host's AI log."""

    def __init__(self, name):
        self.txt = GOLDEN / (name + ".txt")
        self.log = GOLDEN / (name + ".log.gz")
        self.head, self.steps = {}, {}
        if self.txt.is_file():
            for line in self.txt.read_text(encoding="utf-8").splitlines():
                if line.startswith("# "):
                    k, _, v = line[2:].partition(" ")
                    self.head[k] = v
                elif line:
                    step, h = line.split()
                    self.steps[step] = h

    def exists(self):
        return self.txt.is_file() and self.log.is_file()

    def stale(self, args, now):
        """Why the recording no longer fits, or ''."""
        if self.head.get("args") != args:
            return "the scenario's arguments changed"
        for k, v in now.items():
            if self.head.get(k + "format") != str(v):
                return "the engine's %s format is %s, recorded with %s" % (k, v, self.head.get(k + "format"))
        save = self.head.get("save", "")
        if save and (not (SAVES / save).is_file() or crc(SAVES / save) != self.head.get("savecrc")):
            return "the save %s is missing or changed" % save
        return ""


def run_rig(tag, args):
    cmd = [sys.executable, "-u", str(RIG)] + split(args) + ["--tag", tag]
    log(" ".join(cmd[2:]))
    out = RUNS.parent / ("suite-%s.log" % tag)
    out.parent.mkdir(parents=True, exist_ok=True)
    start = time.time()
    with open(out, "w", encoding="utf-8") as f:
        code = subprocess.run(cmd, cwd=ROOT, stdout=f, stderr=subprocess.STDOUT).returncode
    return code, time.time() - start, out


def record(name, args, now):
    tag = "SUITE-%s-rec" % name
    code, wall, out = run_rig(tag, args)
    text = summary(tag)
    if not copies_agree(text):
        return "FAIL", wall, "the copies differ while recording: %s (%s)" % (first_line(text, "RESULT") or "no result", out)
    GOLDEN.mkdir(exist_ok=True)
    g = Golden(name)
    save = ""
    if args.startswith("field"):
        save = "SOSDET-suite-%s.save" % name  # the generated battle, kept with the user's saves (game data, never committed)
        shutil.copy2(RUNS / tag / "battle.save", SAVES / save)
    else:
        save = re.search(r"--save (\S+)", args).group(1)
    with open(HOST_LOG, "rb") as src, gzip.open(g.log, "wb", 9) as dst:
        shutil.copyfileobj(src, dst)
    picked = hashes(RUNS / tag / ("%s-c1.csv" % tag))
    head = {"args": args, "save": save, "savecrc": crc(SAVES / save), "start": start_hash(text)}
    head.update({k + "format": str(v) for k, v in now.items()})
    lines = ["# %s %s" % kv for kv in head.items()] + ["%s %s" % (k, picked[k]) for k in in_order(picked)]
    g.txt.write_text("\n".join(lines) + "\n", encoding="utf-8")
    return "RECORDED", wall, "%d expected hashes" % len(picked)


def check(name, args, recorded, now):
    tag = "SUITE-%s" % name
    if not recorded:
        code, wall, out = run_rig(tag, args)
        text = summary(tag)
        ok = copies_agree(text)
        return ("PASS" if ok else "FAIL"), wall, ("copies only: " if ok else "") + (first_line(text, "RESULT")
                                                                                   or "no result, see %s" % out)
    g = Golden(name)
    if not g.exists():
        return "RECORD", 0, "no recording: run suite.py --record"
    why = g.stale(args, now)
    if why:
        return "RECORD", 0, "record again: " + why
    replay = RUNS.parent / ("suite-%s-host.log" % name)
    with gzip.open(g.log, "rb") as src, open(replay, "wb") as dst:
        shutil.copyfileobj(src, dst)
    extra = " --replay %s" % replay
    if args.startswith("field"):
        extra += " --battle-save %s" % g.head["save"]
    code, wall, out = run_rig(tag, args + extra)
    text = summary(tag)
    if not copies_agree(text):
        return "FAIL", wall, (first_line(text, "copies differ") or first_line(text, "RESULT")
                              or "no result, see %s" % out)
    if start_hash(text) != g.head.get("start"):
        return "FAIL", wall, "start %s, recorded %s" % (start_hash(text), g.head.get("start"))
    got = hashes(RUNS / tag / ("%s-c1.csv" % tag))
    for step in in_order(g.steps):
        if got.get(step) != g.steps[step]:
            return "FAIL", wall, "differs from the recording at step %s (expected %s, got %s)" % (
                step, g.steps[step], got.get(step, "no step"))
    return "PASS", wall, "copies agree and match %d expected hashes" % len(g.steps)


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--short", action="store_true", help="the short suite (before each pull request)")
    ap.add_argument("--record", action="store_true", help="record the expected hashes of the recorded scenarios")
    ap.add_argument("--only", default="", help="these scenarios only, by name (comma-separated)")
    ap.add_argument("--dry-run", action="store_true", help="print what would run; start no game")
    args = ap.parse_args()
    only = {n for n in args.only.split(",") if n}
    now = formats()
    chosen = []
    for name, full, short, recorded in SCENARIOS:
        rig_args = short if args.short else full
        if rig_args is None or (only and name not in only):
            continue
        chosen.append((name + ("-short" if args.short else ""), rig_args, recorded))
    if only and not chosen:
        sys.exit("suite: no scenario %s" % ", ".join(sorted(only)))

    if args.dry_run:
        for name, rig_args, recorded in chosen:
            g = Golden(name)
            state = ("copies only" if not recorded else "not recorded" if not g.exists()
                     else g.stale(rig_args, now) or "recorded, %d expected hashes" % len(g.steps))
            print("%-18s %s\n%18s rig.py %s" % (name, state, "", rig_args))
        return 0

    results = []
    for name, rig_args, recorded in chosen:
        if args.record and not recorded:
            continue
        result = record(name, rig_args, now) if args.record else check(name, rig_args, recorded, now)
        log("%s: %s, %s" % (name, result[0], result[2]))
        results.append((name,) + result)
    print("\nSOSDET scenario suite%s%s" % (" (short)" if args.short else "", " (record)" if args.record else ""))
    for name, verdict, wall, detail in results:
        print("  %-18s %-8s %5.1f min  %s" % (name, verdict, wall / 60, detail))
    passed = all(v in ("PASS", "RECORDED") for _, v, _, _ in results)
    print("RESULT: %s" % ("PASS" if passed else "FAIL"))
    return 0 if passed else 1


if __name__ == "__main__":
    sys.exit(main())
