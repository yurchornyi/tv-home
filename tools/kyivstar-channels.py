#!/usr/bin/env python3
"""Builds the launcher's channel list for Kyivstar TV (launcher/app/src/main/assets/channels/com.kyivstar.tv.tvapp.json).

Where the data comes from: the public catalog behind tv.kyivstar.ua (Vidmind's clients API), the same calls the site
makes for a visitor who is not signed in. It gives every live channel's id (what the app's deep link
com.kyivstar.tv.tvapp://live<id>?liveId=<id> opens), its name and number as Kyivstar shows them, its logo, and the
site's genre groups, which become the picker's categories.

The API wants the site's session: open https://tv.kyivstar.ua/ua/live-channels in a browser, then in its console
  JSON.parse(localStorage.vmd_user).sessionUuid
and run:  python3 tools/kyivstar-channels.py <that session id>
Then rebuild the launcher.
"""
import json
import sys
import urllib.request
from pathlib import Path

API = "https://clients.production.vidmind.com/vidmind-stb-ws"
OUT = Path(__file__).resolve().parent.parent / "launcher/app/src/main/assets/channels/com.kyivstar.tv.tvapp.json"

# The site's groups, the first that holds a channel names its category (LiveChannels reads these keys). "КС ТБ" and
# "Безкоштовне ТБ" are packages, not genres; "Національні" are the big channels, which the popular list covers.
GROUPS = [
    ("Дорослі", "adult"), ("Радіо", "radio"), ("Дитячі", "kids"), ("Спорт", "sport"), ("Новини", "news"),
    ("Регіональні", "regional"), ("Музика", "music"), ("Пізнавальні", "knowledge"), ("Кіно та серіали", "movies"),
    ("Розважальні", "entertainment"), ("Національні", "entertainment"), ("Інші", "other"),
]

# What people here watch most, most watched first. Kyivstar publishes no viewing numbers, so this is chosen by hand:
# the big channel groups by TV ratings and the joint news marathon, mixed with the pay channels that lead their genre
# (sport, documentaries, films, kids), then the news channels.
POPULAR = [
    "63a1cdb84c3452c24a804dfd",  # 1+1 Україна HD
    "55efdb73e4b0781039bc055f",  # СТБ HD
    "63a1cebb4c3452c24a84352f",  # ICTV 2 HD
    "55efdb73e4b0781039bc0544",  # Новий канал HD
    "55efdb73e4b0781039bc0487",  # ТЕТ HD
    "55efdb73e4b0781039bc046c",  # 2+2 HD
    "55f2bcefe4b0781039beb055",  # 1+1 МАРАФОН HD
    "5f7482451774d4de8a615dd5",  # Setanta Sports HD
    "676d3b72b7140a1feeaf0efb",  # Setanta Sports Premium HD
    "55efdb77e4b0781039bc0e59",  # Discovery Channel
    "5c9fe402e4b0b702df2a2e37",  # National Geographic
    "55efdb73e4b0781039bc057a",  # ICTV HD
    "55efdb73e4b0781039bc03e5",  # НТН HD
    "5646025ee4b030cb88f2ca9e",  # Viasat History HD
    "5b4c567960b25704b6b0639c",  # Animal Planet
    "58a15a5430d48b494bcc67c2",  # Еспресо
    "55efdb73e4b0781039bc0601",  # Прямий
    "55efdb73e4b0781039bc04a2",  # ПЛЮСПЛЮС HD
    "55efdb73e4b0781039bc0400",  # Піксель HD
    "5f0452a7f0f4201b22763474",  # Bolt HD
    "5c01323de4b0f16ffce5824a",  # FILMUADRAMA HD
    "5fc4dcf31d544ddbce8c2d0c",  # Cine + HIT HD
    "55efdb77e4b0781039bc0ec5",  # Eurosport HD
    "55efdb73e4b0781039bc03ca",  # Мега HD
    "5ab9e7e760b20c61c1efec00",  # Інтер HD
]


def get(session, path):
    req = urllib.request.Request(API + path.replace("{s}", ";jsessionid=" + session),
                                 headers={"x-vidmind-device-type": "WEB", "User-Agent": "Mozilla/5.0"})
    with urllib.request.urlopen(req, timeout=60) as r:
        return json.load(r)


def pages(session, group, everything):
    """Every channel of one of the site's groups, in the site's order."""
    seen = []
    for offset in range(0, 5000, 100):
        if everything:
            items = get(session, f"/api/v1/content-groups/{group}{{s}}?limit=100&offset={offset}")["assets"]["assets"]
        else:
            items = get(session, f"/api/v1/gallery/contentgroups/{group}{{s}}?limit=100&offset={offset}")
        new = [a for a in items if a["assetId"] not in {s["assetId"] for s in seen}]
        seen += new
        if len(items) < 100 or not new:
            return seen
    return seen


def logo(asset):
    for source in asset.get("imagesSource") or []:
        for f in source.get("imgFiles") or []:
            if f.get("type") == "STBCHLOGO":
                return source["url"].rstrip("/") + "/" + f["fileName"]
    return None


def main():
    if len(sys.argv) != 2:
        print(__doc__)
        return 2
    session = sys.argv[1]
    areas = get(session, "/api/v2/contentareas/LIVE_CHANNELS{s}?includeRestricted=true&limit=100")
    every = next(a for a in areas if a["type"] == "ALL_CHANNELS")
    channels = pages(session, every["assetId"], True)
    groups = {a["name"]: {c["assetId"] for c in pages(session, a["assetId"], False)} for a in areas if a is not every}
    out = []
    for c in channels:
        cid = c["assetId"]
        category = next((key for name, key in GROUPS if cid in groups.get(name, ())), "other")
        entry = {"id": cid, "name": c["name"].strip(), "number": c.get("number"), "logo": logo(c),
                 "category": category,
                 # Kyivstar TV 1.30 takes the id right after "live"; the liveId query is what older builds read.
                 "open": "com.kyivstar.tv.tvapp://live" + cid + "?liveId=" + cid}
        if cid in POPULAR:
            entry["popular"] = POPULAR.index(cid) + 1
        out.append(entry)
    missing = [p for p in POPULAR if p not in {c["id"] for c in out}]
    if missing:
        print("popular channels no longer listed:", missing)
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(out, ensure_ascii=False, indent=0) + "\n", encoding="utf-8")
    print(f"{len(out)} channels, {sum(1 for c in out if c['logo'])} with a logo, written to {OUT}")


if __name__ == "__main__":
    sys.exit(main())
