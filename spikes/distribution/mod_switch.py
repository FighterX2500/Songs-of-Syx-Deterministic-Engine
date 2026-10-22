"""SOS-25 spike: install, enable, disable or remove the SOSDET-spike test mod.

Works on the user's real data folder (%APPDATA%\\songsofsyx), as the Steam
launch uses it. The first change backs up LauncherSettings.txt to
LauncherSettings.txt.sosdet-backup beside it; `restore` puts that copy back.

Usage: python mod_switch.py install|enable|disable|remove|restore|status
"""
import os
import pathlib
import shutil
import sys

HERE = pathlib.Path(__file__).resolve().parent
MOD_NAME = "SOSDET-spike"
DATA = pathlib.Path(os.environ["APPDATA"]) / "songsofsyx"
SETTINGS = DATA / "settings" / "LauncherSettings.txt"
# Next to the settings, not in build/: build_marker.py wipes build/.
BACKUP = SETTINGS.with_name("LauncherSettings.txt.sosdet-backup")
MOD_SRC = HERE / "build" / "mod" / MOD_NAME
MOD_DST = DATA / "mods" / MOD_NAME
ENTRY = '\t"%s",\n' % MOD_NAME


def fail(msg):
    print("mod_switch: " + msg, file=sys.stderr)
    sys.exit(1)


def backup():
    if not BACKUP.exists():
        BACKUP.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(SETTINGS, BACKUP)
        print("backed up %s" % SETTINGS)


def mods_block(text):
    start = text.find("MODS: [\n")
    if start < 0:
        fail("no 'MODS: [' line in %s" % SETTINGS)
    start += len("MODS: [\n")
    end = text.find("],", start)
    if end < 0:
        fail("MODS list not closed in %s" % SETTINGS)
    return start, end


def set_enabled(on):
    backup()
    text = SETTINGS.read_text(encoding="utf-8")
    start, end = mods_block(text)
    block = text[start:end].replace(ENTRY, "")
    if on:
        block = block + ENTRY
    # The game writes this file with CRLF; read_text turned it into \n.
    SETTINGS.write_text(text[:start] + block + text[end:], encoding="utf-8", newline="\r\n")


def status():
    text = SETTINGS.read_text(encoding="utf-8")
    start, end = mods_block(text)
    print("installed: %s" % MOD_DST.exists())
    print("MODS list: %r" % text[start:end])


def main():
    cmd = sys.argv[1] if len(sys.argv) > 1 else "status"
    if cmd == "install":
        if not MOD_SRC.exists():
            fail("build the mod first: python build_marker.py")
        if MOD_DST.exists():
            shutil.rmtree(MOD_DST)
        shutil.copytree(MOD_SRC, MOD_DST)
        set_enabled(True)
    elif cmd == "enable":
        set_enabled(True)
    elif cmd == "disable":
        set_enabled(False)
    elif cmd == "remove":
        set_enabled(False)
        if MOD_DST.exists():
            shutil.rmtree(MOD_DST)
    elif cmd == "restore":
        if not BACKUP.exists():
            fail("no backup at %s" % BACKUP)
        shutil.copy2(BACKUP, SETTINGS)
    elif cmd != "status":
        fail("unknown command %s" % cmd)
    status()


if __name__ == "__main__":
    main()
