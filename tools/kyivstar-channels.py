#!/usr/bin/env python3
"""Builds the launcher's channel list for Kyivstar TV (launcher/app/src/main/assets/channels/com.kyivstar.tv.tvapp.json).

Where the data comes from, all public, no login:
- Kyivstar TV's own sitemap lists every live channel page as <id>-<slug>; the id is what the app's deep link
  com.kyivstar.tv.tvapp://live?liveId=<id> opens, the Ukrainian and English slugs give a name.
- iptv-org's open channel database gives proper names and logos for Ukrainian channels; matched by name.
Kyivstar's own "KS TV ..." channels are not in that database and keep a name made from the English slug.

Run it now and then to refresh the list, then rebuild the launcher:  python3 tools/kyivstar-channels.py
"""
import json
import re
import sys
import urllib.request
from pathlib import Path

SITEMAP = "https://tv.kyivstar.ua/sitemap_{lang}_live-channels.xml"
IPTV_CHANNELS = "https://iptv-org.github.io/api/channels.json"
IPTV_LOGOS = "https://iptv-org.github.io/api/logos.json"
OUT = Path(__file__).resolve().parent.parent / "launcher/app/src/main/assets/channels/com.kyivstar.tv.tvapp.json"


def fetch(url):
    req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
    with urllib.request.urlopen(req, timeout=60) as r:
        return r.read()


def slugs(lang):
    xml = fetch(SITEMAP.format(lang=lang)).decode("utf-8", "replace")
    found = {}
    for cid, slug in re.findall(r"live-channels/([0-9a-f]{24})-([a-z0-9-]+)", xml):
        found.setdefault(cid, slug)
    return found


def norm(s):
    s = s.lower()
    s = s.replace("+", "plus").replace("&", "and")
    s = re.sub(r"\b(hd|uhd|4k|tv|tb|channel|kanal|ukraine|ukraina|ua)\b", " ", s.replace("-", " "))
    return re.sub(r"[^a-z0-9]", "", s)


# Names a slug cannot say: the well-known broadcasters, in Ukrainian, keyed by the English slug's start.
ALIASES = {
    "1-1": "1+1", "2-2": "2+2", "36-6": "36.6", "stb": "СТБ", "ictv": "ICTV", "inter": "Інтер",
    "novyj-kanal": "Новий канал", "novyi-kanal": "Новий канал", "ukraina": "Україна", "5-kanal": "5 канал",
    "24-kanal": "24 Канал", "channel-24": "24 Канал", "channel-5": "5 канал", "espreso": "Еспресо",
    "pryamyj": "Прямий", "pryamyi": "Прямий", "kyiv": "Київ", "ua-pershyj": "UA:Перший", "pershyj": "Перший",
    "suspilne": "Суспільне", "megogo": "MEGOGO", "mega": "Мега", "tet": "ТЕТ", "k1": "К1", "k2": "К2",
    "nlo-tv": "НЛО TV", "pixel": "Піксель", "plus-plus": "ПлюсПлюс", "plusplus": "ПлюсПлюс", "indigo": "Індиго",
    "eko-tv": "Еко TV", "dim": "Дім", "svit": "Світ", "rada": "Рада", "apostrof": "Апостроф", "apostrophe": "Апостроф",
}


def alias(slug):
    """A Ukrainian name for a known broadcaster's slug, with its HD/4K suffix kept; None for the rest."""
    for key in sorted(ALIASES, key=len, reverse=True):
        if slug == key or slug.startswith(key + "-"):
            rest = [w for w in slug[len(key):].strip("-").split("-") if w and w not in ("ukraina", "ukraine", "ua")]
            return ALIASES[key] + (" " + pretty("-".join(rest)) if rest else "")
    return None


def cyrillic(c):
    """iptv-org's Ukrainian alternative name when it has one, else its name."""
    for n in c.get("alt_names") or []:
        if re.search("[\u0400-\u04FF]", n):
            return n
    return c["name"]


def pretty(slug):
    words = slug.split("-")
    small = {"and", "of", "the", "fm"}
    out = []
    for w in words:
        if w in ("hd", "uhd", "4k", "tv", "fm"):
            out.append(w.upper())
        elif w in small:
            out.append(w)
        else:
            out.append(w.capitalize())
    return " ".join(out)


def main():
    ua, en = slugs("ua"), slugs("en")
    channels = json.loads(fetch(IPTV_CHANNELS))
    logos = {}
    for l in json.loads(fetch(IPTV_LOGOS)):
        logos.setdefault(l["channel"], l["url"])
    by_name = {}
    for c in channels:
        if c.get("country") != "UA":
            continue
        for n in [c["name"]] + list(c.get("alt_names") or []):
            by_name.setdefault(norm(n), c)
    out = []
    matched = 0
    for cid, slug in ua.items():
        en_slug = en.get(cid, slug)
        own = en_slug.startswith("ks-tv-") or slug.startswith("ks-tb-")
        base = re.sub(r"^ks-(tv|tb)-", "", en_slug)
        hit = None if own else (by_name.get(norm(en_slug)) or by_name.get(norm(slug)))
        if hit:
            matched += 1
            name, logo = alias(en_slug) or cyrillic(hit), logos.get(hit["id"])
        else:
            name, logo = (None if own else alias(en_slug)) or pretty(base) + (" · Київстар" if own else ""), None
        out.append({"id": cid, "name": name, "logo": logo, "open": "com.kyivstar.tv.tvapp://live?liveId=" + cid,
                    "own": own})
    # Real broadcasters first, alphabetically; Kyivstar's own themed channels after them.
    out.sort(key=lambda c: (c["own"], c["name"].lower()))
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(out, ensure_ascii=False, indent=0) + "\n", encoding="utf-8")
    print(f"{len(out)} channels, {matched} with a name and logo from iptv-org, written to {OUT}")


if __name__ == "__main__":
    sys.exit(main())
