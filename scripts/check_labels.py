#!/usr/bin/env python3
# Reports how the image model's species map onto Lamell's species list
# (same matching as the app: first two words of the scientific name, plus
# app/src/main/assets/data/synonyms.json). Informational only.
import json, os
A = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "assets")
canon = lambda s: (s.split()[0] + " " + (s.split()[1] if len(s.split()) > 1 else "").split("/")[0]).lower()
species = json.load(open(os.path.join(A, "data", "species.json")))
syn = {canon(k): canon(v) for k, v in json.load(open(os.path.join(A, "data", "synonyms.json"))).items()}
labels = json.load(open(os.path.join(A, "model", "labels.json")))
known = {canon(l) for l in labels} | {syn[canon(l)] for l in labels if canon(l) in syn}
missing = [f"{s['sv']} ({s['la']})" for s in species if canon(s["la"]) not in known]
print(f"Lamell species the model can recognise: {len(species) - len(missing)} of {len(species)}")
print("Not among the model's classes:", "; ".join(missing))

genus = {}
for l in labels:
    genus.setdefault(l.split()[0].lower(), []).append(l)
for s in species:
    if canon(s["la"]) not in known:
        g = s["la"].split()[0].lower()
        print(f"  {s['la']}: model has {', '.join(genus.get(g, [])[:12]) or 'no species in the genus'}")
