"""SOS-25 spike: does the script scan's Class.forName break early class init?

ScriptLoad runs Class.forName (which initializes) on every class in a script
jar, possibly long before the game would load that class itself. This builds
a test mod whose jar holds UNCHANGED copies of every game file the SOS-11 and
SOS-23 spike patches touched, plus the two marker classes. Each unchanged
class is checked byte-identical to the shipped one, so the only difference
from vanilla is when those classes get initialized.

Output: the same mod folder as build_marker.py (it runs that first).
Usage: python scan_test.py
"""
import pathlib
import subprocess
import sys
import zipfile

import build_marker as bm

# Game files changed by the spike patches (SOSGSGMP spikes/determinism/patches),
# minus the three new classes the spike added.
FILES = """
game/GAME.java
game/audio/AmbianceChannel.java
game/audio/AmbianceUpdater.java
game/audio/Music.java
game/audio/Sound.java
game/audio/SoundFile.java
game/audio/SoundRace.java
game/battle/thread/BattleThread.java
game/battle/thread/BattleThreads.java
game/battle/thread/general/Strategos2000.java
game/battle/thread/general/offence/StepArtilleryBombard.java
game/battle/thread/general/offence/StepAttackEnemyNear.java
game/battle/thread/general/offence/StepAttackOthers.java
game/battle/thread/general/offence/StepLinesMoveTo.java
game/battle/thread/general/offence/Strategos2000UpdaterOffense.java
game/battle/thread/order/BattleOrder.java
game/battle/thread/order/BattleOrders.java
game/save/GameLoader.java
game/time/Light.java
menu/Menu.java
settlement/entity/humanoid/ai/crime/SerialKiller.java
settlement/path/thread/FinderThread.java
settlement/thing/projectiles/Updater.java
settlement/tilemap/terrain/TForest.java
settlement/tilemap/terrain/TWater.java
snake2d/CORE.java
snake2d/SlaveThread.java
util/GUTIL.java
util/data/RANMAP.java
util/updating/TileUpdater.java
view/battle/BattleView.java
view/main/VIEW.java
""".split()


def main():
    # Marker mod first (MainProcess and RND, patched).
    subprocess.run([sys.executable, str(bm.HERE / "build_marker.py")], check=True)

    import os
    game = pathlib.Path(os.environ["SOS_GAME_DIR"])
    home = pathlib.Path(os.environ["JAVA_HOME"])
    java = home / "bin" / "java.exe"
    game_jar = game / "SongsOfSyx.jar"
    sources = game / "info" / "SongsOfSyx-sources"
    shipped = zipfile.ZipFile(game_jar)

    extra = {}
    for rel in FILES:
        release = "8" if rel.startswith("snake2d/") else "16"
        out = bm.BUILD / "scan" / rel
        classes = bm.compile_file(java, bm.DEFAULT_ECJ, release, sources / rel, game_jar, out)
        for c in classes:
            data = (out / c).read_bytes()
            if data != shipped.read(c):
                bm.fail("%s does not rebuild %s byte for byte" % (rel, c))
            extra[c] = data
        print("unchanged %-62s %d classes, identical" % (rel, len(classes)))

    # A class the game never references: if its marker appears, something
    # initialized it just because it sits in the jar (the scan), and the
    # stack says who and when.
    probe_rel = "sosdet/spike/ScanProbe.java"
    probe_src = bm.BUILD / "src-probe" / probe_rel
    probe_src.parent.mkdir(parents=True, exist_ok=True)
    probe_src.write_text(
        "package sosdet.spike;\n\npublic final class ScanProbe {\n\tstatic {\n"
        + bm.marker("ScanProbe") + "\t}\n\n\tprivate ScanProbe() {\n\t}\n}\n",
        encoding="utf-8")
    out = bm.BUILD / "probe"
    for c in bm.compile_file(java, bm.DEFAULT_ECJ, "16", probe_src, game_jar, out):
        extra[c] = (out / c).read_bytes()

    jar = bm.BUILD / "mod" / bm.MOD_NAME / "V71" / "script" / (bm.MOD_NAME + ".jar")
    with zipfile.ZipFile(jar, "a", zipfile.ZIP_DEFLATED) as z:
        have = set(z.namelist())
        for name in sorted(extra):
            if name not in have:
                z.writestr(name, extra[name])
    print("scan test jar: %s (%d classes from %d files, plus the 2 markers)"
          % (jar, len(extra), len(FILES)))


if __name__ == "__main__":
    main()
