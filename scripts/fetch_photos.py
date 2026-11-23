#!/usr/bin/env python3
# Picks one openly licensed photo per species for Lamell from Wikimedia Commons.
#
# 1. The species' image on Wikidata (P18, found via the scientific name, P225),
#    also trying the older names in data/synonyms.json.
# 2. Otherwise the first suitable photo in the Commons category of the species.
#
# Only CC0, public domain, CC BY and CC BY-SA photos are used. Each photo is
# stored as photos/<id>.webp (600 px) and photos/<id>_s.webp (160 px), and its
# author, licence and source page go into data/photos.json, which the app shows
# under every photo. Species without a suitable photo are listed at the end.
#
# Part of Lamell. Copyright (C) 2026 Jon Solberg
# Licensed under the GNU General Public License v3 or later. See LICENSE.
# (The photos themselves keep their own licences, see data/photos.json.)

import html
import io
import json
import os
import re
import sys
import time
import urllib.parse
import urllib.request

from PIL import Image, ImageOps

ROOT = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "assets")
OUT = os.path.join(ROOT, "photos")
UA = {"User-Agent": "Lamell/1.0 (https://github.com/jonsolberg/lamell; github@jonsolberg.se)"}
OK_LICENSE = re.compile(r"^(cc0|cc[- ]by(-sa)?([- ][0-9.]+)?([- ][a-z]{2})?|public domain|pd.*)$", re.I)


def get_json(url, data=None):
    for attempt in range(4):
        try:
            req = urllib.request.Request(url, data=data, headers=dict(UA, Accept="application/json"))
            with urllib.request.urlopen(req, timeout=60) as r:
                return json.load(r)
        except Exception as e:
            print("  retry", url[:80], e)
            time.sleep(3 * (attempt + 1))
    raise RuntimeError("failed: " + url)


def get_bytes(url):
    for attempt in range(4):
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=60) as r:
                return r.read()
        except Exception as e:
            print("  retry", url[:80], e)
            time.sleep(3 * (attempt + 1))
    raise RuntimeError("failed: " + url)


def short_author(a):
    m = re.search(r"created by user (.+?)(?: \(| at |$)", a)
    if m:
        a = m.group(1)
    return re.sub(r"\s*\(talk\)", "", a)[:80]


def strip(s):
    s = re.sub(r"<[^>]+>", "", s or "")
    return re.sub(r"\s+", " ", html.unescape(s)).strip()


def two(name):
    w = name.split()
    return (w[0] + " " + w[1].split("/")[0]) if len(w) > 1 else name


def wikidata_images(names):
    """scientific name -> Commons file name (P18)"""
    out = {}
    names = sorted(set(names))
    for i in range(0, len(names), 80):
        values = " ".join('"%s"' % n.replace('"', "") for n in names[i:i + 80])
        q = "SELECT ?name ?img WHERE { VALUES ?name { %s } ?item wdt:P225 ?name . ?item wdt:P18 ?img . }" % values
        url = "https://query.wikidata.org/sparql?" + urllib.parse.urlencode({"query": q, "format": "json"})
        for b in get_json(url)["results"]["bindings"]:
            f = urllib.parse.unquote(b["img"]["value"].rsplit("/", 1)[-1])
            out.setdefault(b["name"]["value"], f)
    return out


def file_info(titles):
    """Commons file title -> imageinfo with licence metadata"""
    q = urllib.parse.urlencode({
        "action": "query", "format": "json", "titles": "|".join(titles),
        "prop": "imageinfo", "iiprop": "url|mime|size|extmetadata", "iiurlwidth": "720",
    })
    pages = get_json("https://commons.wikimedia.org/w/api.php?" + q).get("query", {}).get("pages", {})
    return {p["title"]: p["imageinfo"][0] for p in pages.values() if p.get("imageinfo")}


def category_files(name):
    q = urllib.parse.urlencode({
        "action": "query", "format": "json", "list": "categorymembers",
        "cmtitle": "Category:" + name, "cmtype": "file", "cmlimit": "30",
    })
    try:
        members = get_json("https://commons.wikimedia.org/w/api.php?" + q)["query"]["categorymembers"]
    except Exception:
        return []
    return [m["title"] for m in members]


def usable(info):
    if not info or info.get("mime") != "image/jpeg" or not info.get("thumburl"):
        return None
    meta = info.get("extmetadata", {})
    lic = strip(meta.get("LicenseShortName", {}).get("value", ""))
    if not OK_LICENSE.match(lic):
        return None
    w, h = info.get("width", 0), info.get("height", 0)
    if min(w, h) < 400:
        return None
    return {
        "author": short_author(strip(meta.get("Artist", {}).get("value", ""))) or "okänd",
        "license": lic,
        "licenseUrl": strip(meta.get("LicenseUrl", {}).get("value", "")),
        "source": info.get("descriptionurl", ""),
        "thumb": info["thumburl"],
    }


def main():
    species = json.load(open(os.path.join(ROOT, "data", "species.json")))
    syn = json.load(open(os.path.join(ROOT, "data", "synonyms.json")))
    old_names = {}
    for old, new in syn.items():
        old_names.setdefault(two(new).lower(), []).append(old)

    names_for = {}
    for s in species:
        n = [two(s["la"])] + old_names.get(two(s["la"]).lower(), [])
        names_for[s["id"]] = n
    wd = wikidata_images([n for ns in names_for.values() for n in ns])
    print("Wikidata images:", len(wd))

    os.makedirs(OUT, exist_ok=True)
    result, missing = {}, []
    for s in species:
        chosen = None
        cands = ["File:" + wd[n] for n in names_for[s["id"]] if n in wd]
        if cands:
            infos = file_info(cands)
            for c in cands:
                chosen = usable(infos.get(c.replace("_", " ")))
                if chosen:
                    break
        if not chosen:
            for n in names_for[s["id"]]:
                files = [f for f in category_files(n) if f.lower().endswith((".jpg", ".jpeg"))][:20]
                if not files:
                    continue
                infos = file_info(files)
                for f in files:
                    chosen = usable(infos.get(f))
                    if chosen:
                        break
                if chosen:
                    break
        if not chosen:
            missing.append(f"{s['sv']} ({s['la']})")
            print("  no photo:", s["sv"])
            continue
        img = Image.open(io.BytesIO(get_bytes(chosen.pop("thumb"))))
        img = ImageOps.exif_transpose(img).convert("RGB")
        small = ImageOps.fit(img, (160, 160))
        small.save(os.path.join(OUT, f"{s['id']}_s.webp"), "WEBP", quality=72, method=6)
        img.thumbnail((600, 600))
        img.save(os.path.join(OUT, f"{s['id']}.webp"), "WEBP", quality=66, method=6)
        result[str(s["id"])] = chosen
        print(f"  {s['sv']}: {chosen['license']} – {chosen['author'][:60]}")
        time.sleep(0.3)

    with open(os.path.join(ROOT, "data", "photos.json"), "w") as f:
        json.dump(result, f, ensure_ascii=False, indent=0, sort_keys=True)
    size = sum(os.path.getsize(os.path.join(OUT, x)) for x in os.listdir(OUT))
    print(f"Photos: {len(result)} of {len(species)}, {size // 1024} KB")
    print("No open photo:", "; ".join(missing))


if __name__ == "__main__":
    main()
