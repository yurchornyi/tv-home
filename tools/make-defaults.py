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
# Every app in channel mode starts with the automatic order on, whatever the source TV has.
for values in out["prefs"].values():
    for k in [k for k in values if k.startswith("mode_")]:
        values["auto_" + k[len("mode_"):]] = True
with open(dst, "w", encoding="utf-8") as f:
    json.dump(out, f, ensure_ascii=False, indent=1)
    f.write("\n")
print("defaults from", os.path.relpath(src, root))
