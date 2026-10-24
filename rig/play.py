"""A player's hands on one copy of a running rig session (an LLM agent playing, SOS-38): reads what the player sees
and gives the player's actions, the calls the game's UI makes. A peer's actions go to the host as orders and apply at
the step the host stamps, so every copy plays them; the runner's summary says whether they stayed identical.

  python rig/play.py K state            what copy K's player sees (step, day, people, soldiers, credits, ...)
  python rig/play.py K do ACTION ...    one action, as in rig/cmds/*.txt; prints "played at step N" or why refused
  python rig/play.py K actions          the actions there are, with examples (rig/cmds/*.txt)

Every action is appended to build/rig/inst/players.log with copy, time and outcome, to replay a desync.
"""
import json
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import rig  # noqa: E402  (the runner's paths and command hand-over)

TIMEOUT = 60  # a copy on the other PC answers through the relay, about a second


def ask(k, command, answers):
    """Hands copy k one command and returns its answer event (one of `answers`)."""
    c = rig.Copy(k, None)
    if not c.events.exists():
        sys.exit("copy %d is not running" % k)
    since = c.events_count()
    cmd, tmp = c.rig / "command.txt", c.rig / "command.play.tmp"
    end = time.time() + TIMEOUT
    while cmd.exists():  # the runner's or another command still waiting
        if time.time() > end:
            sys.exit("copy %d is not taking commands" % k)
        time.sleep(0.2)
    tmp.write_text(command, encoding="utf-8")
    os.replace(tmp, cmd)
    while time.time() < end:
        for line in c.events.read_text(encoding="utf-8").splitlines()[since:]:
            e = json.loads(line)
            if e["event"] in answers or e["event"] == "error":
                return e
        time.sleep(0.2)
    sys.exit("copy %d: no answer within %d s" % (k, TIMEOUT))


def main():
    if len(sys.argv) < 3:
        sys.exit(__doc__)
    k, what = int(sys.argv[1]), sys.argv[2]
    if what == "actions":
        for f in sorted((rig.ROOT / "rig" / "cmds").glob("*.txt")):
            print("== %s" % f.stem)
            print(f.read_text(encoding="utf-8").strip())
        return
    if what == "state":
        e = ask(k, "state", ("state",))
        e.pop("event")
        print(json.dumps(e))
        return
    if what != "do" or len(sys.argv) < 4:
        sys.exit(__doc__)
    action = " ".join(sys.argv[3:])
    e = ask(k, "play " + action, ("played", "refused"))
    outcome = "played at step %d" % e["step"] if e["event"] == "played" else "refused: %s" % e.get("message", e)
    with open(rig.INST / "players.log", "a", encoding="utf-8") as f:
        f.write("%s copy %d: %s -> %s\n" % (time.strftime("%H:%M:%S"), k, action, outcome))
    print(outcome)


if __name__ == "__main__":
    main()
