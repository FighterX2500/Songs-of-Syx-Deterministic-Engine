"""SOS-108 spike: where a citizen's update goes, from a JFR recording's execution samples.

Usage: python jfrcit.py <jfr.exe> <recording.jfr>
"""
import collections, re, subprocess, sys

jfr, rec = sys.argv[1], sys.argv[2]
out = subprocess.run([jfr, "print", "--stack-depth", "96", "--events", "jdk.ExecutionSample", rec],
                     capture_output=True, text=True, encoding="utf-8", errors="replace").stdout

FRAME = re.compile(r"^\s+([\w$.]+)\.([\w$<>]+)\(")
samples = []  # (thread, [frames top first])
thread, frames, in_stack = None, [], False
for line in out.splitlines():
    s = line.strip()
    if s.startswith("sampledThread"):
        thread = s.split('"')[1] if '"' in s else s
    elif s.startswith("stackTrace = ["):
        frames, in_stack = [], True
    elif in_stack and s.startswith("]"):
        in_stack = False
        samples.append((thread, frames))
    elif in_stack:
        m = FRAME.match(line)
        if m:
            frames.append(m.group(1).rsplit(".", 1)[-1] + "." + m.group(2))
        elif s == "...":
            frames.append("...")

threads = collections.Counter(t for t, _ in samples)
print("samples by thread (top 6):", threads.most_common(6))
game = [f for t, f in samples if any(x == "ENTETIES.update" for x in f)]
main_thread = collections.Counter(t for t, f in samples if "ENTETIES.update" in f).most_common(1)
print("thread running ENTETIES.update:", main_thread)
mt = main_thread[0][0] if main_thread else None
allmain = [f for t, f in samples if t == mt]
print("game thread samples:", len(allmain), " inside ENTETIES.update:", len(game),
      "(%.0f%%)" % (100.0 * len(game) / max(1, len(allmain))))

def child_of(frames, parent):
    # frames are top first: the child is the frame just above the deepest parent occurrence
    for i in range(len(frames) - 1, 0, -1):
        if frames[i] == parent:
            return frames[i - 1]
    return None

def table(title, rows, total):
    print("\n" + title)
    for k, n in rows.most_common(14):
        print("  %5.1f%%  %s" % (100.0 * n / total, k))

hum = [f for f in game if "Humanoid.update" in f]
print("\ninside Humanoid.update:", len(hum), "(%.0f%% of ENTETIES)" % (100.0 * len(hum) / max(1, len(game))))
table("what Humanoid.update calls (share of citizen samples):", collections.Counter(child_of(f, "Humanoid.update") for f in hum), len(hum))
ai = [f for f in hum if "AIManager.update" in f]
table("what AIManager.update calls:", collections.Counter(child_of(f, "AIManager.update") for f in ai), len(hum))
mods = [f for f in hum if "AIModules.update" in f]
table("what AIModules.update calls:", collections.Counter(child_of(f, "AIModules.update") for f in mods), len(hum))

def has(f, pred):
    return any(pred(x) for x in f)

search = [f for f in hum if has(f, lambda x: x.startswith(("SCompFinder.", "SFinder", "SPathFinder", "PathUtilOnline$Flooder", "SFINDERS", "FinderArround", "Rnd.")))]
print("\ncitizen samples with a path/finder search on the stack: %.1f%%" % (100.0 * len(search) / max(1, len(hum))))
table("searches by finder (outermost finder frame):",
      collections.Counter(next((x for x in reversed(f) if x.startswith(("SFinder", "SPathFinder", "SCompFinder", "FinderArround"))), "?") for f in search), len(hum))
rnd = [f for f in hum if has(f, lambda x: x.startswith("RND.") or x.startswith("Random."))]
print("\ncitizen samples inside a random draw: %.1f%%" % (100.0 * len(rnd) / max(1, len(hum))))
table("hottest leaf methods inside citizens:", collections.Counter(f[0] for f in hum if f), len(hum))
