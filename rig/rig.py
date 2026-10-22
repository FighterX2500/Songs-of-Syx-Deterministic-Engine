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

ROOT = pathlib.Path(__file__).resolve().parent.parent
BUILD = ROOT / "build"
MODS = {"SOSDET": BUILD / "mod" / "SOSDET", "SOSDET-rig": BUILD / "rig" / "mod" / "SOSDET-rig"}
INST = BUILD / "rig" / "inst"
RUNS = BUILD / "rig" / "runs"
REAL = pathlib.Path(os.environ["APPDATA"]) / "songsofsyx"
BASE_SAVE = "sosmp-det-base.save"  # the SOS-11 base save in the user's saves
BATTLE_SEED, MEN_PER_SIDE, LOAD_SEED, RUN_SEED, INVADE_MEN = 12345, 2000, 555, 777, 1000


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

    def prepare(self, saves):
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
        # Windowed and tiny, muted, never minimised, both mods on.
        for key, value in (("SCREEN_MODE", "2"), ("WINDOW_WIDTH", "10"), ("WIDOW_HEIGHT", "10"),
                           ("WIN_AUTO_ICONIFY", "0"), ("VOLUME_MASTER", "0")):
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
    """Stops every game process whose class path points into the rig's copies."""
    marker = str(INST).replace("'", "''")
    subprocess.run(["powershell", "-NoProfile", "-Command",
                    "Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | Where-Object { $_.CommandLine -like '*%s*' }"
                    " | ForEach-Object { Stop-Process -Id $_.ProcessId -Force }" % marker], check=False)


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


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("scenario", choices=["field", "city"])
    ap.add_argument("--tag", required=True)
    ap.add_argument("--copies", type=int, default=4)
    ap.add_argument("--speeds", default="5,7,8,10")
    ap.add_argument("--steps", type=int, default=36000)
    ap.add_argument("--save", help="city: the save file in your saves folder")
    ap.add_argument("--keep", action="store_true", help="leave the copies open")
    ap.add_argument("--run-on", action="store_true", help="don't stop at the first difference; run the whole length")
    args = ap.parse_args()

    game = pathlib.Path(os.environ.get("SOS_GAME_DIR") or fail("SOS_GAME_DIR is not set"))
    for name, path in MODS.items():
        if not path.is_dir():
            fail("%s is not built: run gradlew.bat assemble" % path)
    speeds = [float(s) for s in args.speeds.split(",")][:args.copies]
    if len(speeds) < args.copies:
        fail("need a speed per copy")
    # The reference copy runs exactly --steps: the slowest. Its speed names the run ("5x baseline").
    ref = 1 + speeds.index(min(speeds))
    baseline = "%gx baseline" % speeds[ref - 1]
    run_dir = RUNS / args.tag
    shutil.rmtree(run_dir, ignore_errors=True)
    run_dir.mkdir(parents=True)

    if args.scenario == "city":
        if not args.save:
            fail("city needs --save")
        saves = {"sosdet-city.save": REAL / "saves" / "saves" / args.save}
        autoload = "sosdet-city"
    else:
        saves = {BASE_SAVE: REAL / "saves" / "saves" / BASE_SAVE}
        autoload = BASE_SAVE[:-len(".save")]
    for src in saves.values():
        if not src.is_file():
            fail("no save %s" % src)

    stop_copies()
    copies = [Copy(k, game) for k in range(1, args.copies + 1)]
    for c in copies:
        c.prepare(saves)
    for c in copies:
        c.start(autoload)
        time.sleep(3)
    for c in copies:
        c.wait("loaded", 0, 600)
    log("%d copies up; %s, %s test, speeds %s; copy %d runs %d steps, the others until it stops"
        % (len(copies), args.scenario, baseline, speeds, ref, args.steps))

    if args.scenario == "field":
        log("copy 1 generates the battle (seed %d, %d per side)" % (BATTLE_SEED, MEN_PER_SIDE))
        copies[0].send("generate %d %d" % (BATTLE_SEED, MEN_PER_SIDE), "generated", 600)
        battle = copies[0].data / "saves" / "saves" / "__battle.save"
        shutil.copy2(battle, run_dir / "battle.save")
        for c in copies:
            shutil.copy2(battle, c.data / "saves" / "saves" / "sosdet-battle.save")

    starts = {}

    def start_copy(c, speed):
        c.send("begin", "begin")
        c.send("loadseed %d" % LOAD_SEED, "loadseed")
        if args.scenario == "field":
            c.send("loadbattle sosdet-battle", "loaded", 600)
        else:
            c.send("holdworld 1", "holdworld")
            c.send("load sosdet-city", "loaded", 600)
        time.sleep(5)
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
        if diverged is not None and not args.run_on:
            log("copies differ at step %d; holding every copy" % diverged)
            break
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
    summary.append("RESULT: " + ("all copies identical to step %d" % args.steps if identical else "copies differ"))
    (run_dir / "summary.txt").write_text("\n".join(summary) + "\n", encoding="utf-8")
    print("\n".join(summary))
    if not args.keep:
        stop_copies()
    sys.exit(0 if identical else 1)


if __name__ == "__main__":
    main()
