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
import base64
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
# --remote: the other PC, as user@host (SSH key login, Windows with OpenSSH), from the environment, never the repository
REMOTE = os.environ.get("SOSDET_RIG_REMOTE", "")
REMOTE_KEY = os.environ.get("SOSDET_RIG_REMOTE_KEY", str(pathlib.Path.home() / ".ssh" / "sosdet_laptop"))
SSH = ["ssh", "-i", REMOTE_KEY, "-o", "BatchMode=yes", "-o", "ConnectTimeout=20", "-o", "LogLevel=ERROR"]
REMOTE_WINDOW = (1600, 900)  # a watched remote copy's window (the laptop: 1920x1080)
RELAY, RELAY_SRC, RELAY_PORT = BUILD / "rig" / "relay", ROOT / "rig" / "relay" / "Relay.java", 47710
# what a remote copy reads from the copies on this PC; everything else stays here
SHARED = ("hashes.bin,ai-host.log,reports.bin,resync.save,resync.state,wresync.save,wresync.state,wresync.state.done,"
          "battle.save,battle.done,view.save,view.done,view.hash,view.close,view.tape")


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
        self.base = 0  # events before this line belong to a warm-up game, not to the run

    def prepare(self, saves, window=False, size=None):
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
        # Windowed and tiny, muted, never minimised, both mods on; with window=True, a 1280x720 window with the user's
        # sound, never their own full screen (owner, 2026-10-27: the rig runs windowed); with a size, a window of that
        # size with sound (another PC's screen)
        tiny = ((("SCREEN_MODE", "2"), ("WINDOW_WIDTH", "1280"), ("WIDOW_HEIGHT", "720")) if window
                else (("SCREEN_MODE", "2"), ("WINDOW_WIDTH", "10"), ("WIDOW_HEIGHT", "10"), ("VOLUME_MASTER", "0")))
        if size:
            tiny = (("SCREEN_MODE", "2"), ("WINDOW_WIDTH", str(size[0])), ("WIDOW_HEIGHT", str(size[1])))
        for key, value in tiny + (("WIN_AUTO_ICONIFY", "0"),):
            settings, n = re.subn(r"(?m)^" + key + r": [^,\r\n]*,", key + ": " + value + ",", settings)
            if n != 1:
                fail("LauncherSettings.txt has no single %s line" % key)
        settings, n = re.subn(r"MODS: \[[^\]]*\]", 'MODS: [\r\n\t"SOSDET",\r\n\t"SOSDET-rig",\r\n]', settings)
        if n != 1:
            fail("LauncherSettings.txt has no MODS list")
        (self.data / "settings" / "LauncherSettings.txt").write_bytes(settings.encode("utf-8"))

    def put_save(self, src, name):
        """A save into this copy's saves folder, under that name."""
        shutil.copy2(src, self.data / "saves" / "saves" / name)

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
                lines = self.events.read_text(encoding="utf-8").splitlines()[max(since, self.base):]
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
        for line in self.events.read_text(encoding="utf-8").splitlines()[max(since, self.base):]:
            e = json.loads(line)
            if e["event"] == event:
                return e
        return None

    def all_of(self, event):
        """Every such event since the copy started."""
        if not self.events.exists():
            return []
        return [e for e in (json.loads(line) for line in self.events.read_text(encoding="utf-8").splitlines()[self.base:])
                if e["event"] == event]

    def alive(self):
        return copy_running(self.k)

    def wait_finished(self, since, timeout, log_path):
        """Its "finished" event; a copy whose game has died ends where its per-step log does (the summary says so)."""
        end, checked = time.time() + timeout, time.time()
        while time.time() < end:
            e = self.find("finished", since)
            if e:
                return e
            if time.time() - checked > 20:
                checked = time.time()
                if not self.alive():
                    step = last_logged_step(log_path)
                    log("copy %d: its game is gone (a crash file?) at step %d" % (self.k, step))
                    return {"endStep": step, "endHash": None, "men": 0, "wallSeconds": 0, "stats": {}}
            time.sleep(2)
        fail("copy %d: no 'finished' event within %d s" % (self.k, timeout))

    def send(self, command, event, timeout=300):
        since = self.events_count()
        self.command(command)
        return self.wait(event, since, timeout)

    def command(self, command):
        """Hands the rig one command: written whole, then renamed into place, so it never reads half of one."""
        cmd = self.rig / "command.txt"
        for _ in range(600):  # the rig deletes the file when it reads it
            if not cmd.exists():
                break
            time.sleep(0.5)
        tmp = self.rig / "command.tmp"
        tmp.write_text(command, encoding="utf-8")
        for _ in range(100):
            try:
                os.replace(tmp, cmd)
                return
            except PermissionError:  # the rig has the old file open for a moment
                time.sleep(0.1)
        fail("copy %d: cannot hand over the command %r" % (self.k, command))


def remote_ps(script):
    """Runs a PowerShell script on the remote PC; returns its output. Encoded, so nothing in it needs quoting."""
    # an error stops the script with its message; a quiet miss (-ErrorAction SilentlyContinue) is not a failure
    wrapped = "$ProgressPreference='SilentlyContinue'; $ErrorActionPreference='Stop'; try { %s } catch { $_ | Out-String; exit 1 }; exit 0" % script
    enc = base64.b64encode(wrapped.encode("utf-16-le")).decode()
    r = subprocess.run(SSH + [REMOTE, "powershell -NoProfile -NonInteractive -EncodedCommand " + enc],
                       capture_output=True, text=True)
    if r.returncode != 0:
        fail("on %s: %s" % (REMOTE, (r.stderr or r.stdout).strip()[-500:]))
    return r.stdout.replace("\r", "")


def remote_task(name, path):
    """Runs a .cmd on the remote PC in the logged-on user's session (a game started over SSH would have no screen)."""
    # no trigger: the task runs only when started here (a once-trigger fired again at its time, SOS-15)
    remote_ps("Register-ScheduledTask -TaskName '%s' -Action (New-ScheduledTaskAction -Execute '%s')"
              " -Principal (New-ScheduledTaskPrincipal -UserId $env:USERNAME -LogonType Interactive) -Force | Out-Null;"
              " Start-ScheduledTask -TaskName '%s'" % (name, path, name))


class RemoteCopy(Copy):
    """A copy on the other PC (--remote). Its data folder is made here as for a local copy and sent there; its rig
    folder here is a mirror the relay keeps up to date, so the runner reads its events and per-step log and writes
    its commands exactly as for a local copy."""

    def __init__(self, k, game, root):
        super().__init__(k, game)
        self.root = root  # on the remote PC: game\, relay\, inst\<k>\
        self.remote_appdata = "%s\\inst\\%d" % (root, k)
        self.remote_rig = self.remote_appdata + "\\songsofsyx\\sosdet-rig"

    def prepare(self, saves, window=False, size=None):
        # watched there: a window that fits the remote PC's screen, not this PC's screen settings
        super().prepare(saves, False, REMOTE_WINDOW if window else None)
        # the remote copy's last data folder goes, but its cache stays (the first run there sends it)
        cached = remote_ps("$d = '%s\\songsofsyx'; New-Item -ItemType Directory -Force $d | Out-Null;"
                           " Get-ChildItem $d -Exclude cache | Remove-Item -Recurse -Force; Test-Path $d\\cache\\*"
                           % self.remote_appdata).strip() == "True"
        tar = [r"C:\Windows\System32\tar.exe", "-cf", "-", "-C", str(self.appdata)]
        tar += (["--exclude", "songsofsyx/cache"] if cached else []) + ["songsofsyx"]
        pack = subprocess.Popen(tar, stdout=subprocess.PIPE)
        unpack = subprocess.run(SSH + [REMOTE, 'tar -xf - -C "%s"' % self.remote_appdata], stdin=pack.stdout,
                                capture_output=True, text=True)
        pack.stdout.close()
        if pack.wait() != 0 or unpack.returncode != 0:
            fail("copy %d: sending its data folder to %s failed: %s" % (self.k, REMOTE, unpack.stderr.strip()))
        for f in self.rig.iterdir():
            f.unlink()  # the mirror fills it from the remote copy's rig folder

    def put_save(self, src, name):
        super().put_save(src, name)
        subprocess.run(["scp"] + SSH[1:] + ["-q", str(src), "%s:%s/songsofsyx/saves/saves/%s"
                                           % (REMOTE, self.remote_appdata.replace("\\", "/"), name)], check=True)

    def alive(self):
        # the copy's game is the remote game's Java that isn't the relay (one remote copy at most)
        return remote_ps("@(Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | Where-Object { $_.ExecutablePath"
                         " -like '%s\*' -and $_.CommandLine -notlike '*Relay*' }).Count" % self.root).strip() not in ("", "0")

    def start(self, autoload):
        script = ("@echo off\r\nset APPDATA=%s\r\nset SOSDET_RIG_AUTOLOAD=%s\r\ncd /d %s\\game\r\n"
                  "jre\\bin\\java.exe -cp SongsOfSyx.jar init.Main\r\n" % (self.remote_appdata, autoload, self.root))
        path = self.remote_appdata + "\\start.cmd"
        remote_ps("[IO.File]::WriteAllText('%s', '%s')" % (path, script.replace("'", "''")))
        remote_task("SOSDET-copy-%d" % self.k, path)


def start_relays(copies):
    """The relays between this PC and the remote one: the remote copy's rig folder comes here; the files it reads
    from the copies here (SHARED) and its commands go there, with this PC's paths in the commands made its own."""
    remote = [c for c in copies if isinstance(c, RemoteCopy)]
    if len(remote) != 1:
        fail("one remote copy at most")
    r = remote[0]
    (INST / "relay-pc.log").unlink(missing_ok=True)
    if not (RELAY / "Relay.class").exists() or (RELAY / "Relay.class").stat().st_mtime < RELAY_SRC.stat().st_mtime:
        RELAY.mkdir(parents=True, exist_ok=True)
        subprocess.run(["javac", "--release", "21", "-d", str(RELAY), str(RELAY_SRC)], check=True)
    remote_ps("New-Item -ItemType Directory -Force '%s\\relay' | Out-Null" % r.root)
    subprocess.run(["scp"] + SSH[1:] + ["-q"] + [str(p) for p in RELAY.glob("*.class")]
                   + ["%s:%s/relay/" % (REMOTE, r.root.replace("\\", "/"))], check=True)
    for c in copies:  # stale mirrors from an earlier run (an old resync marker would be loaded)
        if c is not r:
            remote_ps("Remove-Item -Recurse -Force '%s\\inst\\%d' -ErrorAction SilentlyContinue" % (r.root, c.k))
    java = '"%s\\game\\jre\\bin\\java.exe"' % r.root
    serve = ("@echo off\r\n%s -cp %s\\relay Relay serve 0.0.0.0 %d --out %s %s - --map %s %s\\inst"
             " --log %s\\relay\\relay.log\r\n" % (java, r.root, RELAY_PORT, r.remote_rig, r.rig, INST, r.root, r.root))
    remote_ps("Remove-Item '%s\\relay\\relay.log' -ErrorAction SilentlyContinue;"
              " [IO.File]::WriteAllText('%s\\relay\\relay.cmd', '%s')" % (r.root, r.root, serve.replace("'", "''")))
    remote_task("SOSDET-relay", r.root + "\\relay\\relay.cmd")
    outs = []
    for c in copies:
        if c is not r:
            c.rig.mkdir(parents=True, exist_ok=True)
            outs += ["--out", str(c.rig), "%s\\inst\\%d\\songsofsyx\\sosdet-rig" % (r.root, c.k), SHARED]
    # its class path names the rig's build folder and its arguments the copies, so stop_copies() stops it with them
    subprocess.Popen([str(r.game / "jre" / "bin" / "java.exe"), "-cp", str(RELAY), "Relay", "connect",
                      REMOTE.split("@")[-1], str(RELAY_PORT)] + outs
                     + ["--cmd", str(r.rig), r.remote_rig, "--log", str(INST / "relay-pc.log")],
                     stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    log("relays up between this PC and %s; copy %d runs there" % (REMOTE, r.k))


def stop_remote(root):
    """Stops the game copies and the relay on the remote PC (everything run by its game's Java)."""
    remote_ps("Get-Process java -ErrorAction SilentlyContinue | Where-Object { $_.Path -like '%s\\*' } | Stop-Process -Force"
              % root)


def copy_running(k):
    """Whether copy k's game process is still running (not the relay, whose arguments name the copies' folders)."""
    marker = (str(INST / str(k)) + "\\").replace("'", "''")
    out = subprocess.run(["powershell", "-NoProfile", "-Command",
                          "@(Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | Where-Object { $_.CommandLine -like '*%s*'"
                          " -and $_.CommandLine -notlike '*Relay*' }).Count"
                          % marker], capture_output=True, text=True).stdout.strip()
    return out not in ("", "0")


OFFSCREEN = r"""
Add-Type -Namespace SosdetRig -Name Win -MemberDefinition @'
[DllImport("user32.dll")] public static extern bool SetWindowPos(IntPtr h, IntPtr a, int x, int y, int cx, int cy, uint f);
'@
$p = Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Where-Object { $_.CommandLine -like '*MARKER*' }
foreach ($q in $p) {
  $h = (Get-Process -Id $q.ProcessId).MainWindowHandle
  if ($h -ne [IntPtr]::Zero) { [void][SosdetRig.Win]::SetWindowPos($h, [IntPtr]::Zero, -32000, -32000, 0, 0, 0x0015) }
}
"""


def move_offscreen(k):
    """Moves copy k's window off the screen: it still draws and runs (a minimized window could stall), out of sight."""
    marker = (str(INST / str(k)) + "\\").replace("'", "''")
    subprocess.run(["powershell", "-NoProfile", "-Command", OFFSCREEN.replace("MARKER", marker)], capture_output=True)


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
    """Compares the copies' per-step hash logs while they run, from step `start`; finds the first step where any copy
    differs."""

    def __init__(self, logs, start=1):
        self.logs = logs  # copy -> path of its per-step log
        self.offsets = {k: 0 for k in logs}
        self.partial = {k: "" for k in logs}
        self.hashes = {k: {} for k in logs}
        self.next_step = start
        self.diverged = None
        self.resynced = {}  # copy -> the step it resynced at (its marker line)

    def drop(self, k):
        """Copy k stopped (its human removed, SOS-66): the others are compared without it."""
        for d in (self.logs, self.offsets, self.partial, self.hashes):
            d.pop(k, None)

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
            if len(parts) >= 2 and parts[0] == "resync":
                at = int(parts[1])
                self.resynced[k] = at
                self.hashes[k] = {s: h for s, h in self.hashes[k].items() if s <= at}  # from before the load
                self.next_step = min(self.next_step, at + 1)
            elif len(parts) >= 2 and parts[0].isdigit():
                self.hashes[k][int(parts[0])] = parts[1]

    def check(self):
        """Reads what is new; returns the first differing step, or None."""
        for k in self.logs:
            self._read(k)
        while self.diverged is None:
            s = self.next_step
            if self.resynced and (len(self.resynced) < len(self.logs) or s > min(self.resynced.values()) and
                                  len(set(self.resynced.values())) > 1):
                break  # a resync under way: wait until every copy has loaded it
            present = [h[s] for k, h in self.hashes.items() if s in h]
            jumped = [k for k, h in self.hashes.items() if s not in h and self.resynced.get(k, 0) >= s]
            if len(present) + len(jumped) < len(self.logs):
                break
            if len(set(present)) > 1:
                self.diverged = s
            for h in self.hashes.values():
                h.pop(s, None)
            self.next_step += 1
        return self.diverged


def loop_frames_cut(line):
    """A draw's call site without the frames below GAME.update: the game loop's own lines differ per PC's pacing."""
    i = line.find(" < view.main.VIEW.update")
    return line if i < 0 else line[:i]


def first_draw_difference(run_dir):
    """The first simulation draw that differs between two copies' draw traces (both dumped around a desync)."""
    dumps = sorted(run_dir.glob("draws-c*.txt"))
    if len(dumps) < 2:
        return None
    a, b = (p.read_text(encoding="utf-8").splitlines() for p in dumps[:2])
    step = None
    for x, y in zip(a, b):
        if x.startswith("## step"):
            step = x
        if loop_frames_cut(x) != loop_frames_cut(y):
            return "draw trace: %s and %s differ first in %s:\n  %s\n  %s" % (dumps[0].name, dumps[1].name, step, x, y)
    return "draw trace: %s and %s agree over the steps both kept" % (dumps[0].name, dumps[1].name)


def load_resync(copies, saved, save, state):
    """Every copy but copy 1 loads the resync copy 1 saved (copy 1 loads it itself)."""
    log("copy 1 saved a resync at step %d in %d ms; the others load it" % (saved["step"], saved["saveMs"]))
    for c in copies[1:]:
        c.send("resyncload %s %s" % (save, state), "resyncloading")


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
    """step -> hash for a per-step log (the hash logged at step s is the state step s-1 left), and the end hash. A
    resync marker drops the lines logged before it for later steps (they came from the game before the load)."""
    hashes, end = {}, None
    for line in path.read_text(encoding="utf-8").splitlines()[1:]:
        step, h = line.split(",")[:2]
        if step == "end":
            end = h
        elif step == "resync":
            hashes = {s: v for s, v in hashes.items() if s <= int(h)}
        else:
            hashes[int(step)] = h
    return hashes, end


def compare(copies, run_dir, tag, ref, steps, ends, after=1, resync_step=0):
    """Acceptance: every copy matches the reference copy at every step from `after` up to its last (--steps).
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
        # a copy that loaded a resync from behind its step has no lines for the steps it jumped
        # a copy that ran several steps in one frame has one line for them (a watcher catching up, SOS-109)
        first = next((s for s in range(after, last + 1) if s in ref_hashes and hashes.get(s) != ref_hashes[s]
                      and not (s not in hashes and (s <= resync_step or ends[k] >= steps))), None)
        at_end = hashes.get(steps + 1)
        if first is None and (ref_end is None or at_end == ref_end) and ends[ref] == steps:  # no end line: closed by hand
            lines.append("copy %d: identical to copy %d at every step %sto %d"
                         % (k, ref, "from %d " % after if after > 1 else "", steps))
        else:
            identical = False
            if first is not None and first not in hashes:
                lines.append("copy %d: stopped at step %d, before the reference's last step %d" % (k, ends[k], steps))
            elif first is not None:
                lines.append("copy %d: DIFFERS from copy %d at step %d" % (k, ref, first))
            else:
                lines.append("copy %d: DIFFERS from copy %d after step %d" % (k, ref, steps))

    common = set(s for s in set.intersection(*(set(h) for h, _ in logs.values())) if s >= after)
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


def make_humans(game, saves, n, run_dir, new_world=None, difficulty=None, which=None):
    """--humans: copy 1 makes n - 1 NPCs humans in the save and saves it, before the run; every copy then starts from
    that save (SOS-46). With --new-world SEED it builds a fresh world around n humans instead (SOS-55). Returns the saves
    and the human slots, slot 0 first."""
    stop_copies()
    c = Copy(1, game)
    c.prepare(saves)
    shutil.rmtree(c.data / "cache", ignore_errors=True)
    c.start("sosdet-city")
    c.wait("loaded", 0, 600)
    move_offscreen(1)
    c.send("load sosdet-city", "loaded", 600)
    if new_world is None:
        made = c.send("makehuman %s" % (which or "auto %d" % (n - 1)), "makehuman", 600)
    else:
        made = c.send("newworld %d auto %d%s" % (new_world, n, " " + difficulty if difficulty else ""), "newworld", 900)
        log("new world from seed %d: humans at %s" % (new_world, made["locations"]))
        for r in made["routes"]:
            log("route between humans %d and %d, %.1f tiles apart: %s" % (r["a"], r["b"], r["tiles"],
                                                                        "found" if r["ok"] else "NONE"))
        if not all(r["ok"] for r in made["routes"]):
            fail("--new-world: two humans 6-15 tiles apart have no route (mod spec 5)")
    c.send("savegame humans.save", "savegame", 600)
    if new_world is not None:
        # the save -> load round trip keeps every human's slot and capital (SOS-55)
        rt = c.send("roundtrip humans.save humans-rt.save", "roundtrip", 900)
        before = [(h["slot"], h["capitol"]) for h in made["humans"]]
        after = [(h["slot"], h["capitol"]) for h in rt["humans"]]
        log("round trip: humans (slot, capital) %s before, %s after the load; %d of %d sections the same, differ: %s"
            % (before, after, rt["same"], rt["sections"], [d["key"] for d in rt["differ"]]))
        if before != after:
            fail("--new-world: the save -> load round trip changed the humans")
    stop_copies()
    slots = [h["slot"] for h in made["humans"]]
    if len(slots) != n:
        fail("--humans %d: the save has %d humans: %s" % (n, len(slots), slots))
    shutil.copy2(c.rig / "humans.save", run_dir / "humans.save")
    log("the save with %d humans, slots %s: %s" % (n, slots, run_dir / "humans.save"))
    return {"sosdet-city.save": run_dir / "humans.save"}, slots


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
            if session and args.hold_world:
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
        load_start(False)  # no seed: vanilla has no session, and its own RND runs on
        if not field and not args.no_invade:
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
        if not field and not args.no_invade:
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


LOCK = RUNS.parent / "rig.lock"


def take_lock():
    """One runner at a time: two would send every command twice to the same copies (SOS-32)."""
    if LOCK.exists():
        pid = LOCK.read_text().strip()
        # a runner stopped from outside leaves its lock, and Windows soon gives its process number to another program
        alive = pid.isdigit() and subprocess.run(
            ["powershell", "-NoProfile", "-Command", "if ((Get-CimInstance Win32_Process -Filter 'ProcessId=%s').CommandLine"
             " -match 'rig\\.py') { 'yes' }" % pid], capture_output=True, text=True).stdout.strip() == "yes"
        if alive:
            fail("another rig.py (process %s) is running; stop it first" % pid)
    LOCK.parent.mkdir(parents=True, exist_ok=True)
    LOCK.write_text(str(os.getpid()))


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
    ap.add_argument("--battle-save", default="",
                    help="field: start from this battle save in your saves folder instead of generating one (a replayed"
                         " log needs the start it was recorded on; SOS-31)")
    ap.add_argument("--window", action="store_true", help="a 1280x720 window with the user's sound, to watch a copy (always windowed, never full screen)")
    ap.add_argument("--run-on", action="store_true", help="don't stop at the first difference; run the whole length")
    ap.add_argument("--ai", choices=["instep", "host"], default="host",
                    help="host (default, as the engine): copy 1 hosts the AI general and is the reference, the others are"
                         " peers fed its AI log; instep: the general runs inside the step in every copy")
    ap.add_argument("--nudge", type=int, default=0,
                    help="forced desync: fatal injuries to one soldier in one copy at this step; runs on past the difference")
    ap.add_argument("--nudge-copy", type=int, default=0, help="the copy to nudge (default: the last)")
    ap.add_argument("--resync", type=int, default=0, help="copy 1 saves a resync at this step; every copy loads it")
    ap.add_argument("--resync-on-desync", action="store_true",
                    help="at the first desync an engine reports, copy 1 saves a resync at its step; the others load it")
    ap.add_argument("--world-at", type=int, default=0,
                    help="spike (SOS-38): copy 1 saves at this step and runs on; the --world-load copies load only the"
                         " --world-keys sections from that save into their running game, then run on")
    ap.add_argument("--world-keys", default="WORLD,FACTIONS,RAIDING", help="the save sections a world load reads")
    ap.add_argument("--world-load", default="2,3", help="the copies that load the world (1: copy 1 too, after its save)")
    ap.add_argument("--trace-draws", type=int, default=0,
                    help="debugging: every copy records the call site of each simulation draw for this many steps;"
                         " a desync writes the draws around it, and the summary names the first draw that differs")
    ap.add_argument("--stall-profile", action="store_true",
                    help="debugging: during a step over 100 ms every copy records its stack every 50 ms, not once"
                         " (<tag>-stalls.txt), so the samples show where a long step's time goes")
    ap.add_argument("--trace-from", type=int, default=0,
                    help="with --trace-draws: start the trace at this step (a long replay runs at full pace until then)")
    ap.add_argument("--replay", help="a host's AI log (ai-host.log of a played run): every copy is a peer replaying it"
                                     " from the same --save, to reproduce that run's desync without the player")
    ap.add_argument("--agreed-speed", type=float, default=0,
                    help="the speed every copy agrees on at the start, which sets the step length (1/60 s at 1x or"
                         " slower, 1/16 s above); default: copy 1's speed")
    ap.add_argument("--answer-copies", default="1",
                    help="--humans runs: the copies whose player answers its own world-battle prompts, e.g. 1,2 so a"
                         " besieger on copy 2 storms (SOS-92); default: copy 1")
    ap.add_argument("--unanswered", action="store_true",
                    help="copy 1 leaves world-battle prompts unanswered, so their time runs out; reports each (SOS-45)")
    ap.add_argument("--engage", type=int, default=-1,
                    help="city: copy 1 fights field battles on the battle map, retreating this many steps after Start "
                         "(0: fights on to the end); default: auto-resolve (SOS-32)")
    ap.add_argument("--engage-copy", default="1",
                    help="with --engage and --humans: this copy's player fights its human's battles, not copy 1's (SOS-66);"
                         " a comma list for several, such as both humans of a battle between players (SOS-83)")
    ap.add_argument("--retreat-copy", default="",
                    help="with --engage and several --engage-copy: only these copies retreat after --engage steps, the"
                         " others fight on (SOS-83: army 1's retreat, between two humans)")
    ap.add_argument("--restart", type=int, default=0,
                    help="with --engage: copy 1 restarts the battle once, this many steps after Start (SOS-41)")
    ap.add_argument("--in-battle", default="",
                    help="with --engage: 'STEPS ACTION...': copy 1 does the player action once, STEPS steps after the"
                         " battle's Start (SOS-41: a world command in a battle is skipped, with a notice)")
    ap.add_argument("--no-invade", action="store_true", help="city: no invasion, the city as it is")
    ap.add_argument("--hold-world", action="store_true",
                    help="city: hold the world map still, as before SOS-35 (the world runs in the step by default)")
    ap.add_argument("--follow", action="store_true",
                    help="city: own-city mode (SOS-38); copy 1's city is the one the world reads, the other copies follow"
                         " its reports with cities of their own, and the copies are compared on the world only")
    ap.add_argument("--humans", type=int, default=0,
                    help="city: N humans (SOS-46), one per copy: copy 1 first makes N - 1 NPCs humans and saves; copy k"
                         " then runs human k's city in own-city mode and reads every other copy's reports; the copies"
                         " are compared on the world only")
    ap.add_argument("--make-humans", default=None,
                    help="with --humans N: 'A,B,...', the N - 1 NPC slots that become humans, instead of the first ones"
                         " (SOS-77: NPCs whose land borders the player's, so they are trade partners)")
    ap.add_argument("--new-world", type=int, default=None,
                    help="with --humans N: copy 1 builds a fresh world from this terrain seed around N humans (SOS-55,"
                         " Engine.buildWorld) instead of turning NPCs into humans; every copy then loads it")
    ap.add_argument("--humans-saves", default="",
                    help="with --humans N: 'A,B,...', one save per copy from your saves folder, each already holding the"
                         " humans in slots 0 to N-1 (for example each copy's beforebattle.save): copy k resumes save k"
                         " as human k-1, as the mod's campaign resume does, instead of making the humans (SOS-70)")
    ap.add_argument("--battle-hold", action="store_true",
                    help="with --humans: every copy holds before a step whose commands engage a battle, saves its game"
                         " there (beforebattle.save in its rig folder), then lets the step run (SOS-70)")
    ap.add_argument("--dump-at", default="",
                    help="'STEP:K,K': copies K dump the world's state right after STEP (<tag>-at<STEP>.txt) (SOS-82)")
    ap.add_argument("--dump-reload", default="",
                    help="'STEP:K[:PREFIXES]': copy K reloads its own game at STEP; with no PREFIXES it dumps the"
                         " world's state before and after (<tag>-before.txt, <tag>-after.txt), with PREFIXES (comma"
                         " separated, e.g. FACTIONS.npcManager) it writes their values from before back after the"
                         " load (<tag>-restore.txt), to find what a load resets (SOS-82)")
    ap.add_argument("--view", default="",
                    help="with --humans: 'STEP:V:O[:END]': copy 1 starts a city view at STEP, copy V's human viewing"
                         " copy O's city, and ends it at END; the owner's copy sends its city through its rig folder"
                         " and the viewer's copy compares its copy of that city with the owner's view hashes (SOS-82)")
    ap.add_argument("--view-battle", default="",
                    help="with --view (STEP unused, give 999999999): 'AFTER:END': the view is a watch of the owner's local"
                         " battle, starting AFTER steps after it froze and ending END steps after (-1: with the"
                         " battle) (SOS-109)")
    ap.add_argument("--view-limits", default="",
                    help="with --view: 'CATCHUP:KEEPUP:STEPS': the viewer's limits in seconds and steps instead of the"
                         " spec's 60:60:60, so a short run hits one; the viewer's copy asks copy 1 to end the view"
                         " (view.close), as the mod's VIEW_CLOSE does (SOS-82, M2)")
    ap.add_argument("--view-stall", default="",
                    help="with --view: 'STEP:MS': the viewer's copy stalls one frame for MS ms at STEP while it views,"
                         " to fall behind the host after catching up (the keep-up limit) (SOS-82, M2)")
    ap.add_argument("--remove", default="",
                    help="with --humans: 'K:STEP': copy K stops once copy 1 passes STEP, as a PC that drops out, and copy"
                         " 1 removes its human (SOS-66); the other copies are compared to the end without it. 'K:bN':"
                         " N steps after copy 1 loads a battle (with --engage)")
    ap.add_argument("--difficulty", default=None,
                    help="with --new-world: every human gets this difficulty mode (a file in the game's player/mode"
                         " folder, e.g. 00_FIRST_NAME), as the mod's world build gives the lobby's (SOS-69)")
    ap.add_argument("--on-desync", choices=["resync", "exit"], default=None,
                    help="with --humans: the host's choice when an engine finds a desync and every copy holds (SOS-53):"
                         " a world resync at the hold step, or save and exit; the copies are compared from the step after"
                         " the resync")
    ap.add_argument("--world-resync", type=int, default=0,
                    help="with --follow or --humans: copy 1 saves at this step, and every copy reloads only the world from it")
    ap.add_argument("--follower-saves", default="",
                    help="with --follow: each follower's own save (copies 2..N, comma-separated) instead of --save; their"
                         " worlds differ from copy 1's until the world resync, which must come before the first hash"
                         " (1 to %d)" % (HASH_EVERY - 1))
    ap.add_argument("--cmds", default="",
                    help="player actions copy 1 takes once every copy runs, ';'-separated or @file (one per line), 3 s apart, through the UI's"
                         " own calls (e.g. 'curfew 1;men 0 40'); the others get them through the host's log (ADR 0002);"
                         " 'wait <s>' pauses the actions; 'k: <action>' is copy k's player's instead (SOS-46: with"
                         " --humans each copy is its own human)")
    ap.add_argument("--warm", default="",
                    help="copies (e.g. 3 or 2,3) that play the autoloaded game normally for 30 game seconds before the"
                         " session, so their session game is not the first one their process has run")
    ap.add_argument("--play", action="store_true",
                    help="the owner plays copy 1 (the host) in a normal window from 1x, giving orders and changing speed;"
                         " the other copies replay its log at 25x; the run ends when the owner closes the window")
    ap.add_argument("--commanders", default="",
                    help="field: 'ARMY0 ARMY1', who commands each army from the battle's start: a PC (0 the host, K-1"
                         " copy K), 'ai', or 'A/B' (its divisions split in half between PCs A and B); SOS-15")
    ap.add_argument("--watch", default="",
                    help="K[,K]: those copies' players only watch: their clicked orders and commands are dropped (SOS-15)")
    ap.add_argument("--govern", action="store_true",
                    help="every copy follows the agreed speed, and the host lowers it to what the slowest copy holds"
                         " (SOS-42); every copy starts at copy 1's speed")
    ap.add_argument("--link-delay", default="",
                    help="MS[:JITTER]: what each copy reads from the others arrives MS ms late, plus up to JITTER ms, in"
                         " order: a slow link between PCs (SOS-94)")
    ap.add_argument("--pace-lead", type=int, default=60,
                    help="copy 1 (the host) runs at most this many steps past the slowest copy, as the mod will (spec"
                         " section 4: the slowest PC caps the speed); at least the hash interval (%d); 0: off" % HASH_EVERY)
    ap.add_argument("--remote", type=int, default=0,
                    help="this copy runs on the other PC (SOSDET_RIG_REMOTE=user@host, over SSH; its game folder under"
                         " --remote-root\\game); the relay mirrors its files over a direct TCP link")
    ap.add_argument("--remote-root", default=r"C:\SOSTest", help="with --remote: the folder for the game, relay and copies there")
    ap.add_argument("--evals", action="store_true",
                    help="the timing rule instead: host and peer, one copy alone each, at speed 1")
    ap.add_argument("--vanilla", action="store_true", help="with --evals: measure vanilla again (default: SOS-23's figures)")
    args = ap.parse_args()
    take_lock()
    if args.evals:
        return run_evals(args)
    nudged = (args.nudge_copy or args.copies) if args.nudge else 0
    if args.play:
        # the host is played by hand from 1x; the peers replay at 25x so they never lag; no fixed end
        args.ai = "host"
        args.speeds = ",".join(["1"] + ["25"] * (args.copies - 1))
        args.agreed_speed = 1
        args.steps = 10 ** 7

    game = pathlib.Path(os.environ.get("SOS_GAME_DIR") or fail("SOS_GAME_DIR is not set"))
    for name, path in MODS.items():
        if not path.is_dir():
            fail("%s is not built: run gradlew.bat assemble" % path)
    speeds = [float(s) for s in args.speeds.split(",")][:args.copies]
    if len(speeds) < args.copies:
        fail("need a speed per copy")
    if args.govern:
        speeds = [speeds[0]] * args.copies  # every copy runs at the agreed speed
    # The reference copy runs exactly --steps: the slowest, or the host. Its speed names the run ("5x baseline").
    ref = 1 if args.ai == "host" else 1 + speeds.index(min(speeds))
    baseline = "%gx baseline" % speeds[ref - 1] + (", AI on the host" if args.ai == "host" else "")
    run_dir = RUNS / args.tag
    shutil.rmtree(run_dir, ignore_errors=True)
    run_dir.mkdir(parents=True)

    if args.follow and (args.scenario != "city" or args.ai != "host" or args.replay or args.resync
                        or args.resync_on_desync):
        fail("--follow needs a city run with the AI on the host, without --replay or --resync (a whole-game resync"
             " would take the followers' cities; use --world-resync)")
    if args.humans and (args.humans != args.copies or args.humans < 2 or args.scenario != "city" or args.ai != "host"
                        or args.follow or args.replay or args.resync or args.resync_on_desync):
        fail("--humans N needs N copies (2 or more), a city run with the AI on the host, without --follow, --replay"
             " or --resync")
    link_delay = (0, 0)
    if args.link_delay:
        parts = args.link_delay.split(":")
        if len(parts) > 2 or not all(p.isdigit() for p in parts):
            fail("--link-delay MS[:JITTER], whole milliseconds")
        link_delay = (int(parts[0]), int(parts[1]) if len(parts) > 1 else 0)
    if 0 < args.pace_lead < HASH_EVERY:
        fail("--pace-lead %d: the host learns a copy's step from its hashes, every %d steps; use 0 or at least %d"
             % (args.pace_lead, HASH_EVERY, HASH_EVERY))
    if args.new_world is not None and not args.humans:
        fail("--new-world is for --humans runs")
    if args.battle_hold and not args.humans:
        fail("--battle-hold is for --humans runs")
    if args.difficulty and args.new_world is None:
        fail("--difficulty is for --new-world runs")
    if args.on_desync and (not args.humans or args.world_resync):
        fail("--on-desync is for --humans runs, without --world-resync")
    if args.world_resync and not (args.follow or args.humans):
        fail("--world-resync is for --follow or --humans runs; co-op runs use --resync")
    remove_copy, remove_at, remove_in_battle = 0, 0, False
    view = None
    if args.view:
        parts = args.view.split(":")
        if len(parts) not in (3, 4) or not all(p.isdigit() for p in parts) or not args.humans \
                or not all(1 <= int(p) <= args.copies for p in parts[1:3]) or parts[1] == parts[2]:
            fail("--view STEP:V:O[:END] is for --humans runs, V and O two copies from 1 to %d" % args.copies)
        view = [int(p) for p in parts] + ([] if len(parts) == 4 else [-1])
    if args.view_limits and (not view or len(args.view_limits.split(":")) != 3):
        fail("--view-limits CATCHUP:KEEPUP:STEPS is for --view runs")
    if args.view_stall and (not view or len(args.view_stall.split(":")) != 2
                            or not all(p.isdigit() for p in args.view_stall.split(":"))):
        fail("--view-stall STEP:MS is for --view runs")
    if args.remove:
        k, _, at = args.remove.partition(":")
        remove_in_battle = at.startswith("b")
        if remove_in_battle:
            at = at[1:]
        if not (k.isdigit() and at.isdigit()) or not args.humans or not 2 <= int(k) <= args.copies \
                or remove_in_battle and args.engage < 0:
            fail("--remove K:STEP or K:bN is for --humans runs, K from 2 to %d (copy 1 is the host); bN needs --engage"
                 % args.copies)
        remove_copy, remove_at = int(k), int(at)
    engage_copies = {int(k) for k in args.engage_copy.split(",")}
    answer_copies = {int(k) for k in args.answer_copies.split(",")}
    if any(not 1 <= k <= args.copies for k in answer_copies) or answer_copies != {1} and not args.humans:
        fail("--answer-copies is for --humans runs, copies from 1 to %d" % args.copies)
    if any(not 1 <= k <= args.copies for k in engage_copies) or engage_copies != {1} and not (args.humans and args.engage >= 0):
        fail("--engage-copy K[,K...] is for --engage --humans runs, K from 1 to %d" % args.copies)
    follower_saves = [s for s in args.follower_saves.split(",") if s]
    if follower_saves:
        if not args.follow or len(follower_saves) != args.copies - 1:
            fail("--follower-saves needs --follow and one save per follower (%d)" % (args.copies - 1))
        if not 0 < args.world_resync < HASH_EVERY:
            # before it, a follower's world is its own save's: the first hashes (step 30) and the host's first AI log
            # entries (step 30 and on) must find the host's world there
            fail("--follower-saves needs --world-resync 1 to %d" % (HASH_EVERY - 1))
        for s in follower_saves:
            if not (REAL / "saves" / "saves" / s).is_file():
                fail("no save %s" % (REAL / "saves" / "saves" / s))
    humans_saves = [s for s in args.humans_saves.split(",") if s]
    if humans_saves:
        if not args.humans or len(humans_saves) != args.copies or args.new_world is not None:
            fail("--humans-saves needs --humans, one save per copy (%d), and no --new-world" % args.copies)
        for s in humans_saves:
            if not (REAL / "saves" / "saves" / s).is_file():
                fail("no save %s" % (REAL / "saves" / "saves" / s))
    saves, autoload = scenario_saves(args)
    human_slots = []
    if humans_saves:
        human_slots = list(range(args.humans))  # each copy resumes its own game, as the mod's campaign resume does
    elif args.humans:
        saves, human_slots = make_humans(game, saves, args.humans, run_dir, args.new_world, args.difficulty, args.make_humans)

    if args.remote and (not REMOTE or not 1 < args.remote <= args.copies):
        fail("--remote needs SOSDET_RIG_REMOTE=user@host and a copy other than copy 1 (the host)")
    watched = [int(k) for k in args.watch.split(",") if k]
    if any(not 1 < k <= args.copies for k in watched):
        fail("--watch: copies 2 to %d (the host is played or scripted)" % args.copies)
    stop_copies()
    if args.remote:
        stop_remote(args.remote_root)
    copies = [RemoteCopy(k, game, args.remote_root) if k == args.remote else Copy(k, game)
              for k in range(1, args.copies + 1)]
    for c in copies:
        own = {"sosdet-city.save": REAL / "saves" / "saves" / follower_saves[c.k - 2]} if follower_saves and c.k > 1 else saves
        if humans_saves:
            own = {"sosdet-city.save": REAL / "saves" / "saves" / humans_saves[c.k - 1]}
        # a played host gets this PC's screen; a remote copy, with --play or --watch, a window on its own screen
        c.prepare(own, args.window or (args.play and c.k == 1) or (c.k == args.remote and (args.play or c.k in watched)))
    if args.remote:
        start_relays(copies)
    for c in copies:
        c.start(autoload)
        time.sleep(3)
    for c in copies:
        c.wait("loaded", 0, 600)
        if not (args.window or (args.play and c.k == 1) or c.k == args.remote):
            move_offscreen(c.k)  # test copies out of sight; only a played or watched copy stays on screen
    log("%d copies up; %s, %s test, speeds %s; copy %d runs %d steps, the others until it stops"
        % (len(copies), args.scenario, baseline, speeds, ref, args.steps))

    if args.scenario == "field" and args.battle_save:
        battle = REAL / "saves" / "saves" / args.battle_save
        if not battle.is_file():
            fail("no battle save %s" % battle)
        log("the battle from %s" % battle.name)
        shutil.copy2(battle, run_dir / "battle.save")
        for c in copies:
            c.put_save(battle, "sosdet-battle.save")
    elif args.scenario == "field":
        log("copy 1 generates the battle (seed %d, %d men and %d artillery per side)" % (BATTLE_SEED, MEN_PER_SIDE, args.artillery))
        gen = copies[0].send("generate %d %d %d" % (BATTLE_SEED, MEN_PER_SIDE, args.artillery), "generated", 600)
        log("generated: %s" % json.dumps(gen))
        battle = copies[0].data / "saves" / "saves" / "__battle.save"
        shutil.copy2(battle, run_dir / "battle.save")
        for c in copies:
            c.put_save(battle, "sosdet-battle.save")

    starts = {}
    start_save = run_dir / "battle.save" if args.scenario == "field" else saves["sosdet-city.save"]
    start_id = "%x" % zlib.crc32(start_save.read_bytes())  # names the start; a peer refuses another host's log
    host_log = copies[0].rig / "ai-host.log"
    if args.replay:
        host_log = run_dir / "replayed-host.log"
        shutil.copy2(args.replay, host_log)
    resync_save, resync_state = copies[0].rig / "resync.save", copies[0].rig / "resync.state"
    reports_file = copies[0].rig / "reports.bin"
    wresync_save, wresync_state = copies[0].rig / "wresync.save", copies[0].rig / "wresync.state"
    for stale in (wresync_state, pathlib.Path(str(wresync_state) + ".done")):
        if stale.exists():
            stale.unlink()

    warm = {int(k) for k in args.warm.split(",") if k}
    world_loaders = {int(k) for k in args.world_load.split(",") if k}
    world_save = copies[0].rig / "world.save"
    for stale in (world_save, pathlib.Path(str(world_save) + ".done")):
        if stale.exists():
            stale.unlink()

    def start_copy(c, speed):
        if c.k in warm:
            # a game played before the session, as a host who played for a while before others joined
            c.send("vanilla city 30 5 warm-c%d" % c.k, "running")
            c.wait("finished", 0, 600)
            c.base = c.events_count()
            log("copy %d played 30 game seconds before the session" % c.k)
        c.send("begin", "begin")
        if args.ai == "host":
            c.send("ai %s %s" % ("host" if c.k == 1 and not args.replay else "peer", start_id), "ai")
            c.send("pc %d" % (c.k - 1), "pc")  # the host is PC 0
            if args.follow or args.humans:
                c.send("owncity", "owncity")
        else:
            c.send("ai instep", "ai")
        c.send("loadseed %d" % LOAD_SEED, "loadseed")
        if args.scenario == "field":
            c.send("loadbattle sosdet-battle", "loaded", 600)
        else:
            if args.hold_world:
                c.send("holdworld 1", "holdworld")
            c.send("load sosdet-city", "loaded", 600)
        time.sleep(5)
        if args.ai == "host":
            c.send("ailog %s %s" % ("host" if c.k == 1 and not args.replay else "peer", host_log), "ailog")
        if args.humans:
            # each copy is one human (SOS-46): its city's reports go to every other copy through files, as the mod
            # will send them over the network
            for o in copies:
                c.send("pchuman %d %d" % (o.k - 1, human_slots[o.k - 1]), "pchuman")
            c.send("reportsout %s" % (c.rig / "reports.bin"), "reportsout")
            for o in copies:
                if o.k != c.k:
                    c.send("reportsin %d %s" % (o.k - 1, o.rig / "reports.bin"), "reportsin")
                # a battle on the battle map: the battle PC's battle save, which every copy loads (plan 0006)
                c.send("battledir %d %s" % (o.k - 1, o.rig), "battledir")
            if args.battle_hold:
                c.send("battlehold", "battlehold")
            if args.dump_at and str(c.k) in args.dump_at.split(":")[1].split(","):
                c.send("dumpat %s" % args.dump_at.split(":")[0], "dumpat")
            if args.dump_reload and c.k == int(args.dump_reload.split(":")[1]):
                parts = args.dump_reload.split(":")
                c.send("dumpreload %s" % " ".join([parts[0]] + parts[2:3]), "dumpreload")
            if view:
                owner = copies[view[2] - 1]
                c.send("viewat %d %d %d %d %s" % (view[0], human_slots[view[1] - 1], human_slots[view[2] - 1], view[3],
                                                  owner.rig), "viewat")
                if args.view_battle:
                    c.send("viewbattle %s" % " ".join(args.view_battle.split(":")), "viewbattle")
                if c.k == view[1] and args.view_limits:
                    c.send("viewlimits %s" % " ".join(args.view_limits.split(":")), "viewlimits")
                if c.k == view[1] and args.view_stall:
                    c.send("viewstall %s" % " ".join(args.view_stall.split(":")), "viewstall")
        if args.follow:
            # copy 1's city reports go to the followers through a file, as the mod will send them over the network
            if c.k == 1:
                c.send("reportsout %s" % reports_file, "reportsout")
            else:
                c.send("reportsfrom 0 %s" % reports_file, "reportsfrom")
        if args.on_desync:
            c.send("ondesync %s %s %s %s" % (args.on_desync, "save" if c.k == 1 else "load", wresync_save, wresync_state),
                   "ondesync")
        if args.world_resync:
            # copy 1 saves the world at that step; every copy, copy 1 too, reloads only the world (SOS-38; SOS-52 with
            # several humans)
            c.send("worldresyncat %d %s %s %s" % (args.world_resync, "save" if c.k == 1 else "load", wresync_save,
                                                  wresync_state), "worldresyncat")
        if args.ai == "host" and not args.follow and not args.replay:
            # a peer's player acts too: its orders go to the host, which stamps them into its log (SOS-33)
            if c.k == 1:
                for o in copies[1:]:
                    c.send("ordersfrom %d %s" % (o.k - 1, o.rig / "orders.bin"), "ordersfrom")
            else:
                c.send("ordersout %s" % (c.rig / "orders.bin"), "ordersout")
        # every copy sends its engine's state hashes to the others through files, as the mod will over the network
        c.send("hashes %s" % (c.rig / "hashes.bin"), "hashes")
        for o in copies:
            if o.k != c.k:
                c.send("hashpeer %d %s" % (o.k - 1, o.rig / "hashes.bin"), "hashpeer")
        if c.k == nudged:
            c.send("nudge %d" % args.nudge, "nudge")
        if args.link_delay:
            c.send("link %d %d" % link_delay, "link")
        if c.k == 1 and len(copies) > 1 and args.pace_lead > 0:
            c.send("pace %d" % args.pace_lead, "pace")
        if args.govern:
            c.send("followspeed", "followspeed")
        if c.k in watched:
            c.send("watchonly", "watchonly")
        if c.k == 1 and args.commanders:
            c.send("commanders " + args.commanders, "commanders")
        if c.k in engage_copies and args.engage >= 0 and args.scenario == "city" and not args.play \
                and not args.replay:  # field battles on the battle map (SOS-32)
            retreat = args.engage if not args.retreat_copy or str(c.k) in args.retreat_copy.split(",") else 0
            c.send("answerprompts engage %d%s" % (retreat, " restart %d" % args.restart if args.restart else ""),
                   "answerprompts")
            if args.in_battle:
                c.send("inbattle " + args.in_battle, "inbattlearmed")
        elif c.k in answer_copies and args.scenario == "city" and not args.play and not args.replay:
            if args.unanswered:  # nobody answers: each world-battle prompt's time runs out (SOS-45)
                c.send("answerprompts wait", "answerprompts")
            else:
                c.send("answerprompts", "answerprompts")  # nobody clicks the world-battle prompt in a rig run
        if c.k == 1 and args.play:
            c.send("showmessages", "showmessages")  # the owner reads the messages in the played window
        if c.k == 1 and args.resync:
            c.send("resyncat %d %s %s" % (args.resync, resync_save, resync_state), "resyncat")
        if args.world_at and (c.k == 1 or c.k in world_loaders):
            mode = ("saveload" if c.k in world_loaders else "save") if c.k == 1 else "load"
            c.send("worldat %d %s %s %s" % (args.world_at, mode, world_save, args.world_keys),
                   "worldat")
        c.send("seed %d" % RUN_SEED, "seeded")
        # the agreed starting speed (it sets the step length): copy 1's; each copy's own speed only sets its pace
        c.send("speed %s" % (args.agreed_speed or speeds[0]), "speed")
        if args.trace_draws:
            c.send("tracedraws %d %d" % (args.trace_draws, args.trace_from or -1), "tracedraws")
        if args.stall_profile:
            c.send("stallprofile", "stallprofile")
        starts[c.k] = c.send("hash", "hash")
        if args.scenario == "city" and not args.no_invade:
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

    # --cmds "a;b" or --cmds @file (one action per line; '#' starts a comment)
    if args.cmds.startswith("@"):
        lines = pathlib.Path(args.cmds[1:]).read_text(encoding="utf-8").splitlines()
        actions = [l.split("#")[0].strip() for l in lines]
    else:
        actions = [a.strip() for a in args.cmds.split(";")]
    actions = [a for a in actions if a] if args.cmds else []
    if args.replay and actions:
        log("--cmds ignored: a replayed log already holds the host player's actions")
        actions = []
    for action in actions:
        if action.startswith("wait "):  # wait <s>: the player does nothing for that long
            time.sleep(float(action.split()[1]))
            continue
        time.sleep(3)
        k, sep, rest = action.partition(":")  # "k: action": copy k's player acts (SOS-46: each copy is a human)
        if sep and k.strip().isdigit() and 1 <= int(k) <= len(copies):
            copies[int(k) - 1].send("cmd " + rest.strip(), "cmd")
            log("copy %d's player: %s" % (int(k), rest.strip()))
            continue
        copies[0].send("cmd " + action, "cmd")
        log("copy 1 (the host's player): " + action)

    # Until the reference finishes: compare the copies as they run, and stop them all at the first difference.
    # followers from other saves have worlds of their own until the world resync: compare from the step after it
    compare_from = args.world_resync + 1 if follower_saves else 1
    watcher = Watcher({c.k: c.rig / ("%s-c%d.csv" % (args.tag, c.k)) for c in copies}, compare_from)
    finished, deadline = {}, time.time() + 6 * 3600
    resync = None  # copy 1's resyncsaved event, once it saved
    held = None  # the desync hold every copy resynced from (SOS-53), once all have
    checked = time.time()
    while True:
        if args.play and time.time() - checked > 10:
            checked = time.time()
            if not copy_running(1):
                host_end = last_logged_step(copies[0].rig / ("%s-c1.csv" % args.tag))
                log("the host's window was closed at step %d; comparing up to there" % host_end)
                finished[ref] = {"endStep": host_end, "endHash": None, "men": 0, "wallSeconds": 0, "stats": {}}
                args.steps = host_end
                break
        if time.time() > deadline:
            fail("the reference copy did not finish")
        if resync is None and args.resync_on_desync and any(c.find("desync", 0) for c in copies):
            log("an engine reported a desync; copy 1 saves a resync")
            resync = copies[0].send("resyncnow %s %s" % (resync_save, resync_state), "resyncsaved", 600)
            load_resync(copies, resync, resync_save, resync_state)
        if resync is None and args.resync:
            resync = copies[0].find("resyncsaved", 0)
            if resync:
                load_resync(copies, resync, resync_save, resync_state)
        if args.on_desync and held is None:
            holds = [c.find("desynchold", 0) for c in copies]
            if all(holds) and args.on_desync == "resync" and all(c.find("worldresynced", 0) for c in copies):
                held = holds[0]
                log("every copy held at step %d for the desync at step %d (PC %d) and resynced"
                    % (held["holdStep"], held["desyncStep"], held["pc"]))
                watcher = Watcher({c.k: c.rig / ("%s-c%d.csv" % (args.tag, c.k)) for c in copies}, held["holdStep"] + 1)
            elif all(holds) and args.on_desync == "exit" and all(c.find("desyncexit", 0) and c.find("finished", 0)
                                                                 for c in copies):
                held = holds[0]
                log("every copy held at step %d for the desync at step %d (PC %d), saved and stopped"
                    % (held["holdStep"], held["desyncStep"], held["pc"]))
                for c in copies:
                    finished[c.k] = c.find("finished", 0)
                args.steps = held["holdStep"]
                break
        battle_in = copies[0].find("battlein", 0) if remove_in_battle else None
        if remove_copy and remove_copy not in finished and (battle_in or not remove_in_battle) \
                and last_logged_step(copies[0].rig / ("%s-c1.csv" % args.tag)) \
                >= (battle_in["step"] if battle_in else 0) + remove_at:
            # a PC drops out (SOS-66): its copy stops where it is; once every copy has read its last report, copy 1
            # removes its human, as the mod's host does on Continue without them
            gone = copies[remove_copy - 1]
            mark = gone.events_count()
            gone.command("hold")
            finished[remove_copy] = gone.wait_finished(mark, 600, gone.rig / ("%s-c%d.csv" % (args.tag, gone.k)))
            watcher.drop(remove_copy)
            log("copy %d stopped at step %d" % (remove_copy, finished[remove_copy]["endStep"]))
            time.sleep(5)
            copies[0].send("removehuman %d" % human_slots[remove_copy - 1], "removehuman")
            log("copy 1 removed the human in slot %d" % human_slots[remove_copy - 1])
        done = copies[ref - 1].find("finished", 0)
        if done:
            finished[ref] = done
            break
        diverged = watcher.check()
        if diverged is not None and not args.run_on and not args.nudge and not (args.resync_on_desync and resync is None)                 and not (args.on_desync and held is None):
            log("copies differ at step %d; holding every copy" % diverged)
            break
        time.sleep(2)
    if args.ai == "host" and ref in finished and not (held is not None and args.on_desync == "exit"):
        # Peers can't outrun the host: give them the time to pass its last step before holding them.
        for c in copies[1:]:
            if c.k == remove_copy:
                continue  # stopped, its human removed
            path, deadline = c.rig / ("%s-c%d.csv" % (args.tag, c.k)), time.time() + 600
            while last_logged_step(path) <= args.steps and time.time() < deadline:
                if int(deadline - time.time()) % 30 < 2 and not c.alive():
                    break
                time.sleep(2)
    marks = {c.k: c.events_count() for c in copies if c.k not in finished}
    for c in copies:  # hold the rest now, wherever they are
        if c.k not in finished:
            c.command("hold")
    for c in copies:
        if c.k not in finished:
            finished[c.k] = c.wait_finished(marks[c.k], 600, c.rig / ("%s-c%d.csv" % (args.tag, c.k)))
    ends = {k: f["endStep"] for k, f in finished.items()}

    after = resync["step"] + 1 if resync and args.nudge else compare_from  # a forced desync is only repaired from the resync on
    if held is not None and args.on_desync == "resync":
        after = held["holdStep"] + 1  # a forced desync (wcorrupt) is only repaired from the resync on (SOS-53)
    if held is not None and args.on_desync == "exit":
        # the copies differ from the desync on by design: the check is that every copy held at the same step and saved
        exits = {c.k: c.find("desyncexit", 0) for c in copies}
        identical = len(set(ends.values())) == 1 and all(exits.values())
        lines = ["desync exit: copy %d held at step %d and saved %s (%s bytes)" % (k, ends[k], e["file"], e["bytes"])
                 for k, e in sorted(exits.items())]
    else:
        kept = [c for c in copies if c.k != remove_copy]  # a removed human's copy stopped early (SOS-66)
        identical, lines = compare(kept, run_dir, args.tag, ref, args.steps, {k: e for k, e in ends.items()
                                                                               if k != remove_copy}, after,
                                   resync["step"] if resync else 0)
    if remove_copy:
        asked = copies[0].find("removehuman", 0)
        lines.insert(0, "removal: copy %d stopped at step %s; copy 1 removed slot %s at step %s"
                     % (remove_copy, ends.get(remove_copy), asked and asked["slot"], asked and asked["step"]))
        for c in copies:
            if c.k == remove_copy:
                continue
            r = c.find("humanretired", 0)
            lines.insert(1, "removal: copy %d %s" % (c.k, "retired it by step %d: %d regions, %d armies, active %s"
                                                     % (r["step"], r["regions"], r["armies"], r["active"]) if r
                                                     else "NEVER retired it"))
            if not r or r["regions"] or r["armies"] or r["active"]:
                identical = False
    if resync:
        lines.insert(0, "resync: copy 1 saved at step %d in %d ms (save %d bytes, engine state %d bytes)"
                     % (resync["step"], resync["saveMs"], resync["saveBytes"], resync["stateBytes"]))
        for c in reversed(copies):
            loading, done_ = c.find("resyncloading", 0), c.find("resynced", 0)
            lines.insert(1, "resync: copy %d loaded it from its step %s, at step %s, in %s s"
                         % (c.k, loading and loading["fromStep"], done_ and done_["step"], done_ and done_["loadSeconds"]))
    if args.world_at:
        saved = copies[0].find("worldsaved", 0)
        lines.insert(0, "world: copy 1 saved at step %s in %s ms (%s bytes)"
                     % (saved and saved["step"], saved and saved["saveMs"], saved and saved["saveBytes"]))
        for c in copies:
            if c.k in world_loaders:
                w = c.find("worldloaded", 0)
                lines.insert(1, "world: copy %d loaded %s at step %s in %s ms: %s"
                             % (c.k, args.world_keys, w and w["step"], w and w["loadMs"], w and json.dumps(w["sections"])))
    if args.on_desync and held is None:
        identical = False
        lines.insert(0, "desync flow: the copies never all held for a desync (--on-desync %s)" % args.on_desync)
    if args.world_resync or (held is not None and args.on_desync == "resync"):
        saved = copies[0].find("worldresyncsaved", 0)
        lines.insert(0, "world resync: copy 1 saved at step %s in %s ms (%s bytes)"
                     % (saved and saved["step"], saved and saved["saveMs"], saved and saved["saveBytes"]))
        for c in reversed(copies):
            if c.k == remove_copy:
                continue  # stopped before the resync, its human removed
            w = c.find("worldresynced", 0)
            lines.insert(1, "world resync: copy %d loaded the world at step %s in %s ms"
                         % (c.k, w and w["step"], w and w["loadMs"]))
    for c in copies:
        shutil.copy2(c.events, run_dir / ("events-c%d.jsonl" % c.k))
    summary = ["SOSDET rig: %s, %s test, tag %s, %d copies; copy %d runs %d steps, the others until it stops"
               % (args.scenario, baseline, args.tag, len(copies), ref, args.steps)]
    for c in copies:
        f = finished[c.k]
        summary.append("copy %d at speed %.0f: start %s, end step %d, end %s, men %d, %.0f s wall, stats %s"
                       % (c.k, speeds[c.k - 1], starts[c.k]["battle" if args.scenario == "field" else "city"],
                          f["endStep"], f["endHash"], f["men"], f["wallSeconds"], json.dumps(f["stats"])))
    if len(copies) > 1 and args.pace_lead > 0:
        f = finished[1]
        summary.append("pacing: copy 1 ran at most %d steps past the slowest copy; held by the others in %d frames, %.1f s,"
                       " the longest hold %.2f s" % (args.pace_lead, f.get("pacedFrames", 0), f.get("pacedSeconds", 0),
                                                     f.get("longestHoldSeconds", 0)))
        summary.append("capacity at the end, steps a second: " + ", ".join(
            "copy %d %d" % (c.k, finished[c.k].get("capacity", -1)) for c in copies))
    if args.govern:
        for c in copies:
            changes = ["%.2fx at %d" % (e["agreed"], e["step"]) for e in c.all_of("agreedspeed")]
            notices = ["%d: %s" % (e["step"], e["text"]) for e in c.all_of("speednotice")]
            summary.append("copy %d agreed speed: %s; speed lines: %s" % (c.k, ", ".join(changes) or "none",
                                                                          " | ".join(notices) or "none"))
    for k in watched:
        w = copies[k - 1].all_of("watched")
        summary.append("copy %d only watched: %d clicked orders and commands dropped" % (k, w[-1]["dropped"] if w else 0))
    if args.commanders:
        for c in copies:
            n = c.all_of("notcommanded")
            summary.append("copy %d commanders %s: %d orders and AI changes skipped as not commanded by their source"
                           % (c.k, args.commanders, n[-1]["skipped"] if n else 0))
    summary += lines
    desyncs = {c.k: c.find("desync", 0) for c in copies}
    for c in copies:
        d = desyncs[c.k]
        if d:
            summary.append("copy %d: engine desync at step %d against copy %d, noticed at its step %d; sections %s, divisions %s"
                           % (c.k, d["step"], d["pc"] + 1, d["noticedAt"], ",".join(d["sections"]), d["divisions"]))
            shutil.copy2(c.rig / ("%s-c%d-desync.txt" % (args.tag, c.k)), run_dir / ("desync-c%d.txt" % c.k))
            draws = c.rig / ("%s-c%d-draws.txt" % (args.tag, c.k))
            if draws.exists() and draws.stat().st_size:
                shutil.copy2(draws, run_dir / ("draws-c%d.txt" % c.k))
        else:
            summary.append("copy %d: no engine desync (%d hashes compared, %d missed)"
                           % (c.k, finished[c.k]["stats"].get("hashesCompared", 0), finished[c.k]["stats"].get("hashesMissed", 0)))
    if actions:
        applied = [str(finished[c.k]["stats"].get("commandsApplied", "?")) for c in copies]
        summary.append("scripted actions sent: %d; commands applied per copy: %s (an action sent after the reference"
                       " copy's last step never applies)" % (len(actions), ", ".join(applied)))
        skipped = [l for l in copies[0].events.read_text(encoding="utf-8").splitlines() if '"cmdskipped"' in l]
        if skipped:
            summary.append("scripted actions skipped on copy 1 (target missing in this save): %d" % len(skipped))
            summary += ["  " + json.loads(l)["reason"] for l in skipped]
    if args.trace_draws:
        summary_draws = first_draw_difference(run_dir)
        if summary_draws:
            summary.append(summary_draws)
    if args.nudge:
        nudge = copies[nudged - 1].find("nudged", 0)
        first_hash = -(-args.nudge // HASH_EVERY) * HASH_EVERY
        found = [k for k, d in desyncs.items() if d and d["step"] == first_hash and "soldiers" in d["sections"]
                 and (k == nudged or d["pc"] + 1 == nudged)]
        ok = nudge is not None and sorted(found) == [c.k for c in copies]
        summary.append("forced change in copy %d at step %d (division %s); first hash after it: step %d"
                       % (nudged, args.nudge, nudge and nudge["division"], first_hash))
        summary.append("RESULT: " + ("every copy found it at step %d, in soldiers" % first_hash if ok
                                     else "NOT found as expected: %s" % desyncs))
    else:
        clean = not any(desyncs.values())
        if held is not None:  # the forced desync the copies held for (SOS-53)
            clean = all(d is None or d["step"] == held["desyncStep"] for d in desyncs.values())
        verdict = "all copies identical to step %d" % args.steps if identical else "copies differ"
        if held is not None and args.on_desync == "exit":
            verdict = "every copy stopped at step %d" % args.steps if identical else "the copies did not all stop and save"
        summary.append("RESULT: " + verdict
                       + ("" if held is None else "; the copies held for the desync at step %d and %s"
                          % (held["desyncStep"], "resynced" if args.on_desync == "resync" else "saved and stopped"))
                       + ("; engine hashes agree" if clean else "; ENGINE FOUND A DESYNC"))
    (run_dir / "summary.txt").write_text("\n".join(summary) + "\n", encoding="utf-8")
    print("\n".join(summary))
    if not args.keep:
        stop_copies()
        if args.remote:
            stop_remote(args.remote_root)
    if args.remote:
        shutil.copy2(INST / "relay-pc.log", run_dir / "relay-pc.log")
        subprocess.run(["scp"] + SSH[1:] + ["-q", "%s:%s/relay/relay.log" % (REMOTE, args.remote_root.replace("\\", "/")),
                                            str(run_dir / "relay-remote.log")], check=False)
    sys.exit(0 if identical else 1)


if __name__ == "__main__":
    main()
