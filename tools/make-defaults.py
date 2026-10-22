#!/usr/bin/env python3
"""Writes launcher/app/src/main/assets/defaults.json, the starting choices of a TV that has never been set up
(Backup.applyDefaultsIfFresh), from a settings backup (backups/xgimi.json by default; tools/backup-settings.sh
makes one). Use counts are left out, so every TV learns its own. release.sh runs this before each build."""
import json
import os
import sys

root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
src = sys.argv[1] if len(sys.argv) > 1 else os.path.join(root, "backups", "xgimi.json")
dst = os.path.join(root, "launcher", "app", "src", "main", "assets", "defaults.json")
with open(src, encoding="utf-8") as f:
    backup = json.load(f)
prefs = backup.get("prefs", {})
out = {
    "_": "Starting choices for a TV that has never been set up (Backup.applyDefaultsIfFresh), made by "
         "tools/make-defaults.py from " + os.path.relpath(src, root) + ". Use counts are left out.",
    "files": backup.get("files", {}),
    # A new TV starts with the shelf modes (channel mode, automatic order) but no channels yet: it fills its row
    # from what is opened on it. Choices, removals and counts are this TV's own.
    "prefs": {name: {k: v for k, v in values.items() if not k.startswith(("use_", "chosen_", "hidden_"))}
              for name, values in prefs.items()},
}
# KIK (the owner's film app) is third in a new TV's dock, in place of the older HDRezka build.
KIK, KIK_ACTIVITY, OLD = "rezkatv.web.app.uk", "rezkatv.web.app.uk/kik.i18n.HomeActivity", "rezkatv.web.app"
# The order file's "dock" line must equal config.xml's dock_apps exactly, or the launcher ignores the saved order.
import re
config = open(os.path.join(root, "launcher", "app", "src", "main", "res", "values", "config.xml"), encoding="utf-8").read()
dock_block = re.search(r'<string-array name="dock_apps">(.*?)</string-array>', config, re.S).group(1)
DOCK_APPS = re.findall(r"<item>([^<]+)</item>", dock_block)
order = out["files"].get("order.txt")
if order:
    lines = order.split("\n")
    for i, line in enumerate(lines):
        if line.startswith("dock "):
            lines[i] = "dock " + ",".join(DOCK_APPS)
    comps = [l for l in lines if "/" in l and not l.startswith(KIK + "/") and not l.startswith(OLD + "/")]
    head = [l for l in lines if "/" not in l and l]
    comps.insert(min(2, len(comps)), KIK_ACTIVITY)
    out["files"]["order.txt"] = "\n".join(head + comps) + "\n"

# Every app in channel mode starts with the automatic order on, whatever the source TV has.
for values in out["prefs"].values():
    for k in [k for k in values if k.startswith("mode_")]:
        values["auto_" + k[len("mode_"):]] = True
with open(dst, "w", encoding="utf-8") as f:
    json.dump(out, f, ensure_ascii=False, indent=1)
    f.write("\n")
print("defaults from", os.path.relpath(src, root))
