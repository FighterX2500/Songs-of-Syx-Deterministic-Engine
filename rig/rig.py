"""SOSDET test rig runner: the same run in several game copies at different speeds, compared step by step.

Each copy gets its own data folder (build/rig/inst/<k>/songsofsyx: settings, mods, saves, rig output), so
copies never share anything and the user's own settings and saves are only read. Every copy runs with the
SOSDET mod and the SOSDET-rig mod (build them first: gradlew.bat assemble).

  python rig/rig.py field --tag F1                  2,000 v 2,000 field battle (SOS-11): copy 1 generates it,
                                                    every copy loads that battle save and runs it
  python rig/rig.py city --tag C1 --save Mogus-72-19f86f1e048-47002c-0-121c.save
                                                    a city under a 1,000-man invasion (SOS-23)

Options: --copies 4, --speeds 5,7,8,10, --steps 36000, --keep (leave the copies open).
The slowest copy runs exactly --steps and names the run ("5x baseline test"); the others run with no last step and are held
the moment it stops, each at its own step. Acceptance: every copy matches it at every step to --steps.
Logged as well: each copy's end step, the steps every copy reached, and each copy's end state against the
copies that passed that step.
While they run, the runner compares the copies' logs and holds every copy at the first step where any differs
(--run-on: run the whole length anyway).
Results: build/rig/runs/<tag>/ (per-step hash logs, events, summary.txt); exit code 0 = all copies identical.
"""
import argparse
import json
import os
import pathlib
import re
import shutil
import subprocess
import sys
import threading
import time
import zlib

ROOT = pathlib.Path(__file__).resolve().parent.parent
BUILD = ROOT / "build"
MODS = {"SOSDET": BUILD / "mod" / "SOSDET", "SOSDET-rig": BUILD / "rig" / "mod" / "SOSDET-rig"}
INST = BUILD / "rig" / "inst"
RUNS = BUILD / "rig" / "runs"
REAL = pathlib.Path(os.environ["APPDATA"]) / "songsofsyx"
BASE_SAVE = "sosmp-det-base.save"  # the SOS-11 base save in the user's saves
BATTLE_SEED, MEN_PER_SIDE, LOAD_SEED, RUN_SEED, INVADE_MEN = 12345, 2000, 555, 777, 1000
HASH_EVERY = 30  # the engine's default hash interval (steps)


def log(msg):
    print("[rig %s] %s" % (time.strftime("%H:%M:%S"), msg), flush=True)


def fail(msg):
    log("FAILED: " + msg)
    sys.exit(2)


class Copy:
    """One game copy and its data folder."""

    def __init__(self, k, game):
        self.k = k
        self.game = game
        self.appdata = INST / str(k)
        self.data = self.appdata / "songsofsyx"
        self.rig = self.data / "sosdet-rig"
        self.events = self.rig / "events.jsonl"

    def prepare(self, saves, window=False):
        shutil.rmtree(self.appdata, ignore_errors=True)
        (self.data / "settings").mkdir(parents=True)
        (self.data / "saves" / "saves").mkdir(parents=True)
        self.rig.mkdir(parents=True)
        shutil.copytree(REAL / "saves" / "profile", self.data / "saves" / "profile")
        if (REAL / "cache").is_dir():
            shutil.copytree(REAL / "cache", self.data / "cache")
        for name, src in MODS.items():
            shutil.copytree(src, self.data / "mods" / name)
        for target, src in saves.items():
            shutil.copy2(src, self.data / "saves" / "saves" / target)
        settings = (REAL / "settings" / "LauncherSettings.txt").read_bytes().decode("utf-8")
        # Windowed and tiny, muted, never minimised, both mods on; with window=True, the user's own screen and sound.
        tiny = () if window else (("SCREEN_MODE", "2"), ("WINDOW_WIDTH", "10"), ("WIDOW_HEIGHT", "10"), ("VOLUME_MASTER", "0"))
        for key, value in tiny + (("WIN_AUTO_ICONIFY", "0"),):
            settings, n = re.subn(r"(?m)^" + key + r": [^,\r\n]*,", key + ": " + value + ",", settings)
            if n != 1:
                fail("LauncherSettings.txt has no single %s line" % key)
        settings, n = re.subn(r"MODS: \[[^\]]*\]", 'MODS: [\r\n\t"SOSDET",\r\n\t"SOSDET-rig",\r\n]', settings)
        if n != 1:
            fail("LauncherSettings.txt has no MODS list")
        (self.data / "settings" / "LauncherSettings.txt").write_bytes(settings.encode("utf-8"))

    def start(self, autoload):
        """Starts the game the way the launcher does after Play (init.Main), with this copy's data folder."""
        env = dict(os.environ, APPDATA=str(self.appdata), SOSDET_RIG_AUTOLOAD=autoload)
        java = self.game / "jre" / "bin" / "java.exe"
        subprocess.Popen([str(java), "-cp", str(self.game / "SongsOfSyx.jar"), "init.Main"], cwd=str(self.game), env=env,
                         stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                         creationflags=getattr(subprocess, "CREATE_NEW_PROCESS_GROUP", 0))

    def events_count(self):
        if not self.events.exists():
            return 0
        return len(self.events.read_text(encoding="utf-8").splitlines())

    def wait(self, event, since, timeout):
        """Waits for an event written after line `since`; an error event fails the run."""
        end = time.time() + timeout
        while time.time() < end:
            if self.events.exists():
                lines = self.events.read_text(encoding="utf-8").splitlines()[since:]
                for line in lines:
                    e = json.loads(line)
                    if e["event"] == "error":
                        fail("copy %d: %s" % (self.k, line))
                    if e["event"] == event:
                        return e
            time.sleep(1)
        fail("copy %d: no '%s' event within %d s" % (self.k, event, timeout))

    def find(self, event, since):
        """The first such event after line `since`, or None (no waiting)."""
        if not self.events.exists():
            return None
        for line in self.events.read_text(encoding="utf-8").splitlines()[since:]:
            e = json.loads(line)
            if e["event"] == event:
                return e
        return None

    def send(self, command, event, timeout=300):
        since = self.events_count()
        cmd = self.rig / "command.txt"
        for _ in range(600):  # the rig deletes the file when it reads it
            if not cmd.exists():
                break
            time.sleep(0.5)
        cmd.write_text(command, encoding="utf-8")
        return self.wait(event, since, timeout)


def stop_copies():
    """Stops every game process whose class path points into the rig's copies, and waits until they are gone
    (a copy still exiting keeps its data folder locked)."""
    marker = str(INST).replace("'", "''")
    subprocess.run(["powershell", "-NoProfile", "-Command",
                    "$p = Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | Where-Object { $_.CommandLine -like '*%s*' };"
                    " $p | ForEach-Object { Stop-Process -Id $_.ProcessId -Force };"
                    " $p | ForEach-Object { Wait-Process -Id $_.ProcessId -Timeout 60 -ErrorAction SilentlyContinue }"
                    % marker], check=False)


class Watcher:
    """Compares the copies' per-step hash logs while they run; finds the first step where any copy differs."""

    def __init__(self, logs):
        self.logs = logs  # copy -> path of its per-step log
        self.offsets = {k: 0 for k in logs}
        self.partial = {k: "" for k in logs}
        self.hashes = {k: {} for k in logs}
        self.next_step = 1
        self.diverged = None

    def _read(self, k):
        path = self.logs[k]
        if not path.exists():
            return
        with open(path, "rb") as f:
            f.seek(self.offsets[k])
            data = f.read()
        self.offsets[k] += len(data)
        lines = (self.partial[k] + data.decode("utf-8")).split("\n")
        self.partial[k] = lines.pop()  # an unfinished last line waits for the next read
        for line in lines:
            parts = line.strip().split(",")
            if len(parts) >= 2 and parts[0].isdigit():
                self.hashes[k][int(parts[0])] = parts[1]

    def check(self):
        """Reads what is new; returns the first differing step, or None."""
        for k in self.logs:
            self._read(k)
        while self.diverged is None and all(self.next_step in h for h in self.hashes.values()):
            s = self.next_step
            if len({h[s] for h in self.hashes.values()}) > 1:
                self.diverged = s
            for h in self.hashes.values():
                del h[s]
            self.next_step += 1
        return self.diverged


def last_logged_step(path):
    """The last step in a per-step log that is still being written (0 if none yet)."""
    if not path.exists():
        return 0
    with open(path, "rb") as f:
        f.seek(0, 2)
        f.seek(max(0, f.tell() - 4096))
        lines = f.read().decode("utf-8", "replace").splitlines()
    for line in reversed(lines):
        step = line.split(",")[0]
        if step.isdigit():
            return int(step)
    return 0


def read_log(path):
    """step -> hash for a per-step log (the hash logged at step s is the state step s-1 left), and the end hash."""
    hashes, end = {}, None
    for line in path.read_text(encoding="utf-8").splitlines()[1:]:
        step, h = line.split(",")[:2]
        if step == "end":
            end = h
        else:
            hashes[int(step)] = h
    return hashes, end


def compare(copies, run_dir, tag, ref, steps, ends):
    """Acceptance: every copy matches the reference copy at every step up to its last (--steps).
    Logged: where each copy stopped, the steps every copy reached, and each copy's end state against the
    copies that passed that step (a copy's end state is what another logs at the start of the next step)."""
    logs = {}
    for c in copies:
        src = c.rig / ("%s-c%d.csv" % (tag, c.k))
        shutil.copy2(src, run_dir / src.name)
        logs[c.k] = read_log(src)
    ref_hashes, ref_end = logs[ref]
    lines, identical = [], ends[ref] == steps
    if not identical:
        lines.append("copy %d (reference) stopped at step %d, before step %d" % (ref, ends[ref], steps))
    last = min(steps, max(ref_hashes))
    for k in sorted(logs):
        if k == ref:
            continue
        hashes, _ = logs[k]
        first = next((s for s in range(1, last + 1) if hashes.get(s) != ref_hashes[s]), None)
        at_end = hashes.get(steps + 1)
        if first is None and at_end == ref_end and ends[ref] == steps:
            lines.append("copy %d: identical to copy %d at every step to %d" % (k, ref, steps))
        else:
            identical = False
            if first is not None and first not in hashes:
                lines.append("copy %d: stopped at step %d, before the reference's last step %d" % (k, ends[k], steps))
            elif first is not None:
                lines.append("copy %d: DIFFERS from copy %d at step %d" % (k, ref, first))
            else:
                lines.append("copy %d: DIFFERS from copy %d after step %d" % (k, ref, steps))

    common = set.intersection(*(set(h) for h, _ in logs.values()))
    differ = next((s for s in sorted(common) if any(logs[k][0][s] != ref_hashes[s] for k in logs)), None)
    lines.append("end steps: " + ", ".join("copy %d %d" % (k, ends[k]) for k in sorted(ends)))
    lines.append("steps every copy reached: %d (to step %d), %s"
                 % (len(common), max(common), "identical" if differ is None else "first difference at step %d" % differ))
    for k in sorted(ends):
        checks = []
        for j in sorted(ends):
            if j == k:
                continue
            other = logs[j][0].get(ends[k] + 1)
            if other is None and ends[j] == ends[k]:
                other = logs[j][1]
            if other is not None:
                checks.append("copy %d %s" % (j, "same" if other == logs[k][1] else "DIFFERENT"))
        lines.append("copy %d stopped at step %d, end hash %s; there: %s"
                     % (k, ends[k], logs[k][1], ", ".join(checks) if checks else "no other copy got that far"))
    return identical, lines


STEP_MS = 1000 / 60
# Vanilla timing from SOS-23 (runs EVfieldB, EVcityB; SOSGSGMP docs/plan/spikes/city-determinism.md), the same
# measure and saves; the default baseline, since vanilla doesn't change (owner, 2026-10-22). --vanilla measures again.
SOS23_VANILLA = {
    "field": {"p50": 2.21, "p95": 4.36, "p99": 5.81, "p999": 8.4, "max": float("nan"), "mean": 2.30, "stalls10": 10, "over1s": 0},
    "city": {"p50": 3.54, "p95": 7.78, "p99": 10.77, "p999": 17.4, "max": float("nan"), "mean": 3.96, "stalls10": 12, "over1s": 4},
}


def timing_metrics(path, skip_first):
    """The SOS-23 measure: each frame's game update time per 1/60 s of game time it advanced (frames with several
    steps are averaged over them). skip_first leaves out the first frame that advanced game time: the vanilla load
    stall right after a battle save loads (owner, 2026-10-21)."""
    per_step, total_ms, game_s, skipped = [], 0.0, 0.0, None
    for line in path.read_text(encoding="utf-8").splitlines()[1:]:
        nanos, gs = line.split(",")
        ms, gs = int(nanos) / 1e6, float(gs)
        if gs <= 0 or int(nanos) < 0:
            continue
        if skip_first and skipped is None:
            skipped = ms / (gs * 60)
            continue
        total_ms += ms
        game_s += gs
        per_step.append(ms / (gs * 60))
    xs = sorted(per_step)

    def pct(p):
        return xs[min(len(xs) - 1, int(p / 100 * len(xs)))] if xs else float("nan")

    stalls = sum(1 for x in xs if x > 100)
    return {"frames": len(xs), "gameS": game_s, "p50": pct(50), "p95": pct(95), "p99": pct(99), "p999": pct(99.9),
            "max": xs[-1] if xs else float("nan"), "mean": total_ms / max(game_s * 60, 1e-9),
            "stalls10": stalls * 600 / max(game_s, 1e-9), "stalls": stalls, "over1s": sum(1 for x in xs if x > 1000),
            "skipped": skipped}


def stall_causes(path, top=6):
    """The game frames the stall sampler found most often at the top of the simulation thread's stack."""
    if not path.exists():
        return []
    counts = {}
    for block in path.read_text(encoding="utf-8").split("step over 100 ms")[1:]:
        frames = [l.strip()[3:] for l in block.splitlines() if l.strip().startswith("at ")]
        game = next((f for f in frames if not f.startswith(("java.", "jdk.", "sun."))), frames[0] if frames else "?")
        key = game.split("(")[0]
        counts[key] = counts.get(key, 0) + 1
    return sorted(counts.items(), key=lambda kv: -kv[1])[:top]


def scenario_saves(args):
    if args.scenario == "city":
        if not args.save:
            fail("city needs --save")
        saves, autoload = {"sosdet-city.save": REAL / "saves" / "saves" / args.save}, "sosdet-city"
    else:
        saves, autoload = {BASE_SAVE: REAL / "saves" / "saves" / BASE_SAVE}, BASE_SAVE[:-len(".save")]
    for src in saves.values():
        if not src.is_file():
            fail("no save %s" % src)
    return saves, autoload


def run_evals(args):
    """The timing rule (requirements §4): vanilla, host and peer, one copy alone each, 10 minutes at speed 1."""
    game = pathlib.Path(os.environ.get("SOS_GAME_DIR") or fail("SOS_GAME_DIR is not set"))
    run_dir = RUNS / args.tag
    shutil.rmtree(run_dir, ignore_errors=True)
    run_dir.mkdir(parents=True)
    saves, autoload = scenario_saves(args)
    field = args.scenario == "field"
    kind = "battle" if field else "city"
    stop_copies()
    c = Copy(1, game)

    def fresh():
        c.prepare(saves)
        c.start(autoload)
        c.wait("loaded", 0, 600)

    def load_start(session):
        if field:
            c.send("loadbattle sosdet-battle", "loaded", 600)
        else:
            if session:
                c.send("holdworld 1", "holdworld")
            c.send("load sosdet-city", "loaded", 600)
        time.sleep(5)

    if field:
        fresh()
        log("generating the battle (seed %d, %d per side)" % (BATTLE_SEED, MEN_PER_SIDE))
        c.send("generate %d %d" % (BATTLE_SEED, MEN_PER_SIDE), "generated", 600)
        shutil.copy2(c.data / "saves" / "saves" / "__battle.save", run_dir / "battle.save")
        stop_copies()
        saves = dict(saves, **{"sosdet-battle.save": run_dir / "battle.save"})
    start_id = "%x" % zlib.crc32((run_dir / "battle.save" if field else saves["sosdet-city.save"]).read_bytes())
    runs = {}

    if args.vanilla:
        log("vanilla: %d steps of game time at speed 1" % args.steps)
        fresh()
        load_start(False)
        c.send("seed %d" % RUN_SEED, "seeded")
        if not field:
            c.send("invade %d" % INVADE_MEN, "invade")
        mark = c.events_count()
        c.send("vanilla %s %s 1 %s-vanilla" % (kind, args.steps / 60, args.tag), "running")
        runs["vanilla"] = c.wait("finished", mark, 3 * 3600)
        shutil.copy2(c.rig / ("%s-vanilla-timing.csv" % args.tag), run_dir)
        stop_copies()

    for role in ("host", "peer"):
        log("%s: %d steps at speed 1" % (role, args.steps))
        fresh()
        c.send("begin", "begin")
        c.send("ai %s %s" % (role, start_id), "ai")
        c.send("loadseed %d" % LOAD_SEED, "loadseed")
        load_start(True)
        c.send("ailog %s %s" % (role, run_dir / "ai-host.log" if role == "peer" else c.rig / "ai-host.log"), "ailog")
        c.send("seed %d" % RUN_SEED, "seeded")
        if not field:
            c.send("invade %d" % INVADE_MEN, "invade")
        mark = c.events_count()
        c.send("run %s %d 1 %s-%s" % (kind, args.steps, args.tag, role), "running")
        runs[role] = c.wait("finished", mark, 3 * 3600)
        for name in ("%s-%s.csv", "%s-%s-timing.csv", "%s-%s-stalls.txt"):
            src = c.rig / (name % (args.tag, role))
            if src.exists():
                shutil.copy2(src, run_dir)
        if role == "host":
            shutil.copy2(c.rig / "ai-host.log", run_dir / "ai-host.log")
        shutil.copy2(c.events, run_dir / ("events-%s.jsonl" % role))
        stop_copies()

    host_log, _ = read_log(run_dir / ("%s-host.csv" % args.tag))
    peer_log, _ = read_log(run_dir / ("%s-peer.csv" % args.tag))
    first = next((s for s in sorted(host_log) if peer_log.get(s) != host_log[s]), None)
    roles = ("vanilla", "host", "peer") if args.vanilla else ("host", "peer")
    m = {r: timing_metrics(run_dir / ("%s-%s-timing.csv" % (args.tag, r)), field) for r in roles}
    if not args.vanilla:
        m["vanilla"] = dict(SOS23_VANILLA[args.scenario], skipped=None)
    out = ["SOSDET timing evals: %s, tag %s, one copy alone at speed 1, %d steps (%s)"
           % (args.scenario, args.tag, args.steps, "first frame after the battle save loads left out" if field else "all frames"),
           "%-8s %8s %8s %8s %9s %9s %8s %12s %8s" % ("run", "p50", "p95", "p99", "p99.9", "max", "mean", ">100ms/10min", ">1s")]
    if not args.vanilla:
        out.insert(1, "vanilla: SOS-23's measurements on the same save and rule (EV%sB), not run again (owner, 2026-10-22)"
                   % ("field" if field else "city"))
    for r in ("vanilla", "host", "peer"):
        x = m[r]
        out.append("%-8s %8.2f %8.2f %8.2f %9.2f %9.1f %8.2f %12.1f %8d" % (r, x["p50"], x["p95"], x["p99"], x["p999"],
                                                                               x["max"], x["mean"], x["stalls10"], x["over1s"]))
    verdict = True
    for r in ("host", "peer"):
        x = m[r]
        ok = x["p999"] <= 20 and x["over1s"] == 0 and x["stalls10"] <= m["vanilla"]["stalls10"]
        verdict &= ok
        out.append("%s: %s (p99.9 %.1f <= 20 ms, steps over 1 s %d, over 100 ms %.1f vs vanilla %.1f per 10 min)"
                   % (r, "PASS" if ok else "FAIL", x["p999"], x["over1s"], x["stalls10"], m["vanilla"]["stalls10"]))
        if x["skipped"] is not None:
            out.append("  %s: left-out first frame %.0f ms per step" % (r, x["skipped"]))
        for frame, n in stall_causes(run_dir / ("%s-%s-stalls.txt" % (args.tag, r))):
            out.append("  %s stall: %d x %s" % (r, n, frame))
    out.append("vanilla first frame left out: %s ms per step" % (
        "%.0f" % m["vanilla"]["skipped"] if m["vanilla"]["skipped"] is not None else "-"))
    out.append("host vs peer: %s" % ("identical at every step" if first is None else "DIFFER from step %d" % first))
    for r in ("host", "peer"):
        out.append("%s stats: %s" % (r, json.dumps(runs[r]["stats"])))
    out.append("RESULT: timing rule %s" % ("PASSES" if verdict else "FAILS"))
    (run_dir / "evals.txt").write_text("\n".join(out) + "\n", encoding="utf-8")
    print("\n".join(out))
    sys.exit(0 if verdict and first is None else 1)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("scenario", choices=["field", "city"])
    ap.add_argument("--tag", required=True)
    ap.add_argument("--copies", type=int, default=4)
    ap.add_argument("--speeds", default="5,7,8,10")
    ap.add_argument("--steps", type=int, default=36000)
    ap.add_argument("--save", help="city: the save file in your saves folder")
    ap.add_argument("--keep", action="store_true", help="leave the copies open")
    ap.add_argument("--artillery", type=int, default=0, help="field: artillery pieces per side in the generated battle")
    ap.add_argument("--window", action="store_true", help="the user's own screen and sound settings, to watch a copy")
    ap.add_argument("--run-on", action="store_true", help="don't stop at the first difference; run the whole length")
    ap.add_argument("--ai", choices=["instep", "host"], default="instep",
                    help="host: copy 1 hosts the AI general and is the reference, the others are peers fed its AI log")
    ap.add_argument("--nudge", type=int, default=0,
                    help="forced desync: fatal injuries to one soldier in one copy at this step; runs on past the difference")
    ap.add_argument("--nudge-copy", type=int, default=0, help="the copy to nudge (default: the last)")
    ap.add_argument("--evals", action="store_true",
                    help="the timing rule instead: host and peer, one copy alone each, at speed 1")
    ap.add_argument("--vanilla", action="store_true", help="with --evals: measure vanilla again (default: SOS-23's figures)")
    args = ap.parse_args()
    if args.evals:
        return run_evals(args)
    nudged = (args.nudge_copy or args.copies) if args.nudge else 0

    game = pathlib.Path(os.environ.get("SOS_GAME_DIR") or fail("SOS_GAME_DIR is not set"))
    for name, path in MODS.items():
        if not path.is_dir():
            fail("%s is not built: run gradlew.bat assemble" % path)
    speeds = [float(s) for s in args.speeds.split(",")][:args.copies]
    if len(speeds) < args.copies:
        fail("need a speed per copy")
    # The reference copy runs exactly --steps: the slowest, or the host. Its speed names the run ("5x baseline").
    ref = 1 if args.ai == "host" else 1 + speeds.index(min(speeds))
    baseline = "%gx baseline" % speeds[ref - 1] + (", AI on the host" if args.ai == "host" else "")
    run_dir = RUNS / args.tag
    shutil.rmtree(run_dir, ignore_errors=True)
    run_dir.mkdir(parents=True)

    saves, autoload = scenario_saves(args)

    stop_copies()
    copies = [Copy(k, game) for k in range(1, args.copies + 1)]
    for c in copies:
        c.prepare(saves, args.window)
    for c in copies:
        c.start(autoload)
        time.sleep(3)
    for c in copies:
        c.wait("loaded", 0, 600)
    log("%d copies up; %s, %s test, speeds %s; copy %d runs %d steps, the others until it stops"
        % (len(copies), args.scenario, baseline, speeds, ref, args.steps))

    if args.scenario == "field":
        log("copy 1 generates the battle (seed %d, %d men and %d artillery per side)" % (BATTLE_SEED, MEN_PER_SIDE, args.artillery))
        gen = copies[0].send("generate %d %d %d" % (BATTLE_SEED, MEN_PER_SIDE, args.artillery), "generated", 600)
        log("generated: %s" % json.dumps(gen))
        battle = copies[0].data / "saves" / "saves" / "__battle.save"
        shutil.copy2(battle, run_dir / "battle.save")
        for c in copies:
            shutil.copy2(battle, c.data / "saves" / "saves" / "sosdet-battle.save")

    starts = {}
    start_save = run_dir / "battle.save" if args.scenario == "field" else saves["sosdet-city.save"]
    start_id = "%x" % zlib.crc32(start_save.read_bytes())  # names the start; a peer refuses another host's log
    host_log = copies[0].rig / "ai-host.log"

    def start_copy(c, speed):
        c.send("begin", "begin")
        if args.ai == "host":
            c.send("ai %s %s" % ("host" if c.k == 1 else "peer", start_id), "ai")
        c.send("loadseed %d" % LOAD_SEED, "loadseed")
        if args.scenario == "field":
            c.send("loadbattle sosdet-battle", "loaded", 600)
        else:
            c.send("holdworld 1", "holdworld")
            c.send("load sosdet-city", "loaded", 600)
        time.sleep(5)
        if args.ai == "host":
            c.send("ailog %s %s" % ("host" if c.k == 1 else "peer", host_log), "ailog")
        # every copy sends its engine's state hashes to the others through files, as the mod will over the network
        c.send("hashes %s" % (c.rig / "hashes.bin"), "hashes")
        for o in copies:
            if o.k != c.k:
                c.send("hashpeer %d %s" % (o.k, o.rig / "hashes.bin"), "hashpeer")
        if c.k == nudged:
            c.send("nudge %d" % args.nudge, "nudge")
        c.send("seed %d" % RUN_SEED, "seeded")
        starts[c.k] = c.send("hash", "hash")
        if args.scenario == "city":
            c.send("invade %d" % INVADE_MEN, "invade")
        steps = args.steps if c.k == ref else 0
        c.send("run %s %d %s %s-c%d" % ("battle" if args.scenario == "field" else "city", steps, speed, args.tag, c.k), "running")

    threads = [threading.Thread(target=start_copy, args=(c, speeds[i])) for i, c in enumerate(copies)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    if len(starts) != len(copies):
        fail("not every copy started")

    # Until the reference finishes: compare the copies as they run, and stop them all at the first difference.
    watcher = Watcher({c.k: c.rig / ("%s-c%d.csv" % (args.tag, c.k)) for c in copies})
    finished, deadline = {}, time.time() + 6 * 3600
    while True:
        if time.time() > deadline:
            fail("the reference copy did not finish")
        done = copies[ref - 1].find("finished", 0)
        if done:
            finished[ref] = done
            break
        diverged = watcher.check()
        if diverged is not None and not args.run_on and not args.nudge:
            log("copies differ at step %d; holding every copy" % diverged)
            break
        time.sleep(2)
    if args.ai == "host" and ref in finished:
        # Peers can't outrun the host: give them the time to pass its last step before holding them.
        for c in copies[1:]:
            path, deadline = c.rig / ("%s-c%d.csv" % (args.tag, c.k)), time.time() + 600
            while last_logged_step(path) <= args.steps and time.time() < deadline:
                time.sleep(2)
    marks = {c.k: c.events_count() for c in copies if c.k not in finished}
    for c in copies:  # hold the rest now, wherever they are
        if c.k not in finished:
            (c.rig / "command.txt").write_text("hold", encoding="utf-8")
    for c in copies:
        if c.k not in finished:
            finished[c.k] = c.wait("finished", marks[c.k], 600)
    ends = {k: f["endStep"] for k, f in finished.items()}

    identical, lines = compare(copies, run_dir, args.tag, ref, args.steps, ends)
    for c in copies:
        shutil.copy2(c.events, run_dir / ("events-c%d.jsonl" % c.k))
    summary = ["SOSDET rig: %s, %s test, tag %s, %d copies; copy %d runs %d steps, the others until it stops"
               % (args.scenario, baseline, args.tag, len(copies), ref, args.steps)]
    for c in copies:
        f = finished[c.k]
        summary.append("copy %d at speed %.0f: start %s, end step %d, end %s, men %d, %.0f s wall, stats %s"
                       % (c.k, speeds[c.k - 1], starts[c.k]["battle" if args.scenario == "field" else "city"],
                          f["endStep"], f["endHash"], f["men"], f["wallSeconds"], json.dumps(f["stats"])))
    summary += lines
    desyncs = {c.k: c.find("desync", 0) for c in copies}
    for c in copies:
        d = desyncs[c.k]
        if d:
            summary.append("copy %d: engine desync at step %d against copy %d, noticed at its step %d; sections %s, divisions %s"
                           % (c.k, d["step"], d["pc"], d["noticedAt"], ",".join(d["sections"]), d["divisions"]))
            shutil.copy2(c.rig / ("%s-c%d-desync.txt" % (args.tag, c.k)), run_dir / ("desync-c%d.txt" % c.k))
        else:
            summary.append("copy %d: no engine desync (%d hashes compared, %d missed)"
                           % (c.k, finished[c.k]["stats"].get("hashesCompared", 0), finished[c.k]["stats"].get("hashesMissed", 0)))
    if args.nudge:
        nudge = copies[nudged - 1].find("nudged", 0)
        first_hash = -(-args.nudge // HASH_EVERY) * HASH_EVERY
        found = [k for k, d in desyncs.items() if d and d["step"] == first_hash and "soldiers" in d["sections"]
                 and (k == nudged or d["pc"] == nudged)]
        ok = nudge is not None and sorted(found) == [c.k for c in copies]
        summary.append("forced change in copy %d at step %d (division %s); first hash after it: step %d"
                       % (nudged, args.nudge, nudge and nudge["division"], first_hash))
        summary.append("RESULT: " + ("every copy found it at step %d, in soldiers" % first_hash if ok
                                     else "NOT found as expected: %s" % desyncs))
    else:
        clean = not any(desyncs.values())
        summary.append("RESULT: " + ("all copies identical to step %d" % args.steps if identical else "copies differ")
                       + ("; engine hashes agree" if clean else "; ENGINE FOUND A DESYNC"))
    (run_dir / "summary.txt").write_text("\n".join(summary) + "\n", encoding="utf-8")
    print("\n".join(summary))
    if not args.keep:
        stop_copies()
    sys.exit(0 if identical else 1)


if __name__ == "__main__":
    main()
