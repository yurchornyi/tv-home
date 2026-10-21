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
    "prefs": {name: {k: v for k, v in values.items() if not k.startswith("use_")} for name, values in prefs.items()},
}
with open(dst, "w", encoding="utf-8") as f:
    json.dump(out, f, ensure_ascii=False, indent=1)
    f.write("\n")
print("defaults from", os.path.relpath(src, root))
