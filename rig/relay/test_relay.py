"""Tests the relay through a proxy that keeps dropping the link: growing files arrive byte-identical, a .done marker
never arrives before the file it marks, every command arrives exactly once and in order with its paths mapped, and a
file that shrinks is sent again.

  python rig/relay/test_relay.py      (compile first: javac --release 21 -d build/rig/relay rig/relay/Relay.java)
"""
import os
import pathlib
import random
import shutil
import socket
import subprocess
import sys
import tempfile
import threading
import time

ROOT = pathlib.Path(__file__).resolve().parent.parent.parent
CLASSES = ROOT / "build" / "rig" / "relay"
PORT, PROXY = 47791, 47792


class DroppingProxy:
    """Forwards PROXY to PORT; drop() cuts every open connection."""

    def __init__(self):
        self.socks = []
        self.lock = threading.Lock()
        self.server = socket.socket()
        self.server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.server.bind(("127.0.0.1", PROXY))
        self.server.listen()
        threading.Thread(target=self.accept, daemon=True).start()

    def accept(self):
        while True:
            a, _ = self.server.accept()
            try:
                b = socket.create_connection(("127.0.0.1", PORT))
            except OSError:
                a.close()
                continue
            with self.lock:
                self.socks += [a, b]
            for x, y in ((a, b), (b, a)):
                threading.Thread(target=self.pipe, args=(x, y), daemon=True).start()

    @staticmethod
    def pipe(x, y):
        try:
            while True:
                d = x.recv(65536)
                if not d:
                    break
                y.sendall(d)
        except OSError:
            pass
        for s in (x, y):
            try:
                s.close()
            except OSError:
                pass

    def drop(self):
        with self.lock:
            for s in self.socks:
                try:
                    s.shutdown(socket.SHUT_RDWR)
                    s.close()
                except OSError:
                    pass
            self.socks = []


def relay(args, log):
    java = shutil.which("java") or sys.exit("no java")
    return subprocess.Popen([java, "-cp", str(CLASSES), "Relay"] + args + ["--log", str(log)],
                            stdout=subprocess.DEVNULL, stderr=subprocess.STDOUT)


def wait_for(cond, what, timeout=60):
    end = time.time() + timeout
    while time.time() < end:
        if cond():
            return
        time.sleep(0.05)
    sys.exit("FAILED: %s" % what)


def main():
    t = pathlib.Path(tempfile.mkdtemp(prefix="relaytest-"))
    c_out, c_cmd, s_in, s_cmd, s_out, c_in = (t / n for n in ("c_out", "c_cmd", "s_in", "s_cmd", "s_out", "c_in"))
    for d in (c_out, c_cmd, s_out):
        d.mkdir()
    proxy = DroppingProxy()
    s = relay(["serve", "127.0.0.1", str(PORT), "--out", str(s_out), str(c_in), "-",
               "--map", "C:\\PC\\INST", str(t / "laptop")], t / "s.log")
    c = relay(["connect", "127.0.0.1", str(PROXY), "--out", str(c_out), str(s_in), "-",
               "--cmd", str(c_cmd), str(s_cmd)], t / "c.log")
    stop = threading.Event()

    def dropper():
        while not stop.is_set():
            time.sleep(random.uniform(0.2, 0.8))
            proxy.drop()
    threading.Thread(target=dropper, daemon=True).start()

    # the rig on the receiving PC: takes each command and deletes it
    got = []

    def consumer():
        f = s_cmd / "command.txt"
        while not stop.is_set():
            if f.exists():
                try:
                    got.append(f.read_text(encoding="utf-8"))
                    f.unlink()
                except OSError:
                    pass
            time.sleep(0.01)
    threading.Thread(target=consumer, daemon=True).start()

    rnd = random.Random(7)
    sent_cmds = []

    def commander():  # the runner: one command at a time, waits until it is taken
        for i in range(25):
            text = "hashpeer %d C:\\PC\\INST\\%d\\songsofsyx\\sosdet-rig\\hashes.bin" % (i, i)
            while (c_cmd / "command.txt").exists():
                time.sleep(0.01)
            (c_cmd / "command.tmp").write_text(text, encoding="utf-8")
            os.replace(c_cmd / "command.tmp", c_cmd / "command.txt")
            sent_cmds.append(text)
    cmd_thread = threading.Thread(target=commander)
    cmd_thread.start()

    # growing files, written in random pieces; the PC to PC direction too
    data = {"x.bin": bytearray(), "events.jsonl": bytearray()}
    back = bytearray()
    for _ in range(400):
        for name, buf in data.items():
            piece = rnd.randbytes(rnd.randint(0, 30000))
            with open(c_out / name, "ab") as f:
                f.write(piece)
            buf += piece
        piece = os.urandom(rnd.randint(0, 2000))
        with open(s_out / "ai-host.log", "ab") as f:
            f.write(piece)
        back += piece
        time.sleep(0.005)

    # a save and its marker, written as the rig writes them: the marker last
    save = os.urandom(3_500_000)
    (c_out / "wresync.save").write_bytes(save)
    (c_out / "wresync.state").write_bytes(b"state")
    (c_out / "wresync.state.done").write_bytes(b"")
    early = []

    def watch_marker():
        while not stop.is_set():
            if (s_in / "wresync.state.done").exists():
                if (s_in / "wresync.save").read_bytes() != save:
                    early.append("marker before the save was whole")
                return
            time.sleep(0.001)
    threading.Thread(target=watch_marker, daemon=True).start()

    # a file that shrinks: sent again from the start
    (c_out / "y.bin").write_bytes(b"a" * 50000)
    wait_for(lambda: (s_in / "y.bin").exists() and (s_in / "y.bin").stat().st_size == 50000, "y.bin first version")
    (c_out / "y.bin").write_bytes(b"b" * 100)

    cmd_thread.join(120)
    wait_for(lambda: all((s_in / n).exists() and (s_in / n).read_bytes() == bytes(b) for n, b in data.items()),
             "growing files identical")
    wait_for(lambda: (c_in / "ai-host.log").exists() and (c_in / "ai-host.log").read_bytes() == bytes(back),
             "the other direction identical")
    wait_for(lambda: (s_in / "y.bin").read_bytes() == b"b" * 100, "shrunk file sent again")
    wait_for(lambda: (s_in / "wresync.state.done").exists(), "marker")
    wait_for(lambda: len(got) == 25, "all 25 commands (got %d)" % len(got))
    time.sleep(2)  # anything repeated would show up now
    stop.set()
    expected = [x.replace("C:\\PC\\INST", str(t / "laptop")) for x in sent_cmds]
    problems = list(early)
    if got != expected:
        problems.append("commands: %d got, %s" % (len(got), [g for g in got if g not in expected][:3]))
    for p in (s, c):
        p.kill()
    drops = sum(1 for l in (t / "c.log").read_text(encoding="utf-8").splitlines() if "link down" in l)
    print("link dropped %d times; %d MB mirrored" % (drops, (sum(map(len, data.values())) + len(save)) // 1000000))
    if problems:
        sys.exit("FAILED: " + "; ".join(problems) + " (logs in %s)" % t)
    if drops < 3:
        sys.exit("FAILED: the proxy dropped the link only %d times" % drops)
    shutil.rmtree(t, ignore_errors=True)
    print("PASSED")


if __name__ == "__main__":
    main()
