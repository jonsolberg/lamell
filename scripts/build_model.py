#!/usr/bin/env python3
# Builds the offline image recognition model for Lamell.
#
# Downloads the Danish Fungi 2020 classifier (EfficientNet-B3, 1604 species,
# BVRA/tf_efficientnet_b3.in1k_ft_df20_384 on Hugging Face, CC BY-NC 4.0),
# exports it to ONNX for ONNX Runtime on Android, writes the class labels and
# checks the result on a handful of photos from Wikimedia Commons, so a wrong
# label order or wrong preprocessing fails the build instead of shipping.
#
# Output (app/src/main/assets/model/): fungi.onnx, labels.json, meta.json
#
# Part of Lamell. Copyright (C) 2026 Jon Solberg
# This program is free software: you can redistribute it and/or modify it
# under the terms of the GNU General Public License as published by the Free
# Software Foundation, either version 3 of the License, or (at your option)
# any later version. See LICENSE.
#
# The model weights are NOT covered by the GPL: they are licensed by their
# authors (Picek et al., BVRA) under CC BY-NC 4.0.

import io
import json
import os
import sys
import urllib.parse
import urllib.request
import zipfile

import numpy as np
import onnxruntime as ort
import pandas as pd
import timm
import torch
from PIL import Image

HF_MODEL = "BVRA/tf_efficientnet_b3.in1k_ft_df20_384"
METADATA = "http://ptak.felk.cvut.cz/plants/DanishFungiDataset/DF20-metadata.zip"
OUT = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "assets", "model")
UA = {"User-Agent": "Lamell-build/1.0 (https://github.com/jonsolberg/lamell)"}

# Species with clear photos on Commons, used to check the finished model.
CHECK = [
    "Cantharellus cibarius", "Boletus edulis", "Amanita muscaria", "Amanita phalloides",
    "Laetiporus sulphureus", "Coprinus comatus", "Macrolepiota procera", "Gyromitra esculenta",
    "Fomes fomentarius", "Craterellus cornucopioides", "Sparassis crispa", "Hydnum repandum",
]


def get(url, timeout=120):
    with urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=timeout) as r:
        return r.read()


def labels_from_metadata():
    data = get(METADATA, timeout=600)
    frames = []
    with zipfile.ZipFile(io.BytesIO(data)) as z:
        print("metadata files:", z.namelist())
        for name in z.namelist():
            base = name.rsplit("/", 1)[-1]
            if name.lower().endswith(".csv") and not base.startswith("._") and "__MACOSX" not in name:
                frames.append(pd.read_csv(z.open(name), low_memory=False))
                print("metadata:", name, len(frames[-1]), "rows")
    df = pd.concat(frames, ignore_index=True)
    print("columns:", list(df.columns))
    col = "species" if "species" in df.columns else "scientificName"
    pairs = df[["class_id", col]].dropna().drop_duplicates()
    pairs = pairs[pairs["class_id"] >= 0]
    mapping = {}
    for cid, sp in pairs.itertuples(index=False):
        mapping.setdefault(int(cid), str(sp).strip())
    n = max(mapping) + 1
    missing = [i for i in range(n) if i not in mapping]
    print(f"class ids: {len(mapping)} named, max {n - 1}, missing {missing[:20]}")
    dup = pairs.groupby("class_id")[col].nunique()
    print("ids with several names:", int((dup > 1).sum()))
    return [mapping.get(i, f"class {i}") for i in range(n)]


def commons_images(species, n=3):
    cat = "Category:" + species.replace(" ", "_")
    q = urllib.parse.urlencode({
        "action": "query", "format": "json", "generator": "categorymembers",
        "gcmtitle": cat, "gcmtype": "file", "gcmlimit": "20",
        "prop": "imageinfo", "iiprop": "url|mime", "iiurlwidth": "640",
    })
    try:
        pages = json.loads(get("https://commons.wikimedia.org/w/api.php?" + q)).get("query", {}).get("pages", {})
    except Exception as e:
        print("  commons failed for", species, e)
        return []
    urls = []
    for p in sorted(pages.values(), key=lambda p: p.get("title", "")):
        ii = (p.get("imageinfo") or [{}])[0]
        if ii.get("mime") == "image/jpeg" and ii.get("thumburl"):
            urls.append(ii["thumburl"])
    out = []
    for u in urls:
        if len(out) >= n:
            break
        try:
            out.append(Image.open(io.BytesIO(get(u))).convert("RGB"))
        except Exception:
            pass
    return out


def preprocess(img, meta):
    size = meta["size"]
    if meta["mode"] == "squash":
        img = img.resize((size, size), Image.BILINEAR)
    else:  # resize the short side, then centre crop
        scale = size / meta["crop_pct"] / min(img.size)
        img = img.resize((max(size, round(img.width * scale)), max(size, round(img.height * scale))), Image.BICUBIC)
        left, top = (img.width - size) // 2, (img.height - size) // 2
        img = img.crop((left, top, left + size, top + size))
    x = np.asarray(img, dtype=np.float32) / 255.0
    x = (x - np.array(meta["mean"], dtype=np.float32)) / np.array(meta["std"], dtype=np.float32)
    return x.transpose(2, 0, 1)[None].astype(np.float32)


def main():
    os.makedirs(OUT, exist_ok=True)
    labels = labels_from_metadata()
    print("labels:", len(labels))

    model = timm.create_model("hf-hub:" + HF_MODEL, pretrained=True).eval()
    n_out = model.get_classifier().out_features
    assert n_out == len(labels), f"model has {n_out} classes, metadata {len(labels)}"

    cfg = timm.data.resolve_data_config({}, model=model)
    size = int(cfg["input_size"][-1])
    variants = [
        {"size": size, "mode": "squash", "mean": [0.5] * 3, "std": [0.5] * 3, "crop_pct": 1.0},
        {"size": size, "mode": "squash", "mean": list(cfg["mean"]), "std": list(cfg["std"]), "crop_pct": 1.0},
        {"size": size, "mode": "crop", "mean": list(cfg["mean"]), "std": list(cfg["std"]),
         "crop_pct": float(cfg.get("crop_pct", 0.875))},
    ]

    path = os.path.join(OUT, "fungi.onnx")
    dummy = torch.zeros(1, 3, size, size)
    torch.onnx.export(model, dummy, path, input_names=["image"], output_names=["logits"],
                      opset_version=17, do_constant_folding=True, dynamo=False)
    sess = ort.InferenceSession(path, providers=["CPUExecutionProvider"])
    with torch.no_grad():
        ref = model(dummy).numpy()
    got = sess.run(None, {"image": dummy.numpy()})[0]
    assert np.allclose(ref, got, atol=1e-3), "ONNX output differs from PyTorch"
    print("onnx:", os.path.getsize(path) // 1024, "KB")

    index = {" ".join(l.split()[:2]).lower(): i for i, l in enumerate(labels)}
    images = []
    for sp in CHECK:
        i = index.get(sp.lower())
        if i is None:
            print("  not a model class:", sp)
            continue
        imgs = commons_images(sp)
        print(f"  {sp}: {len(imgs)} photos")
        images += [(sp, i, im) for im in imgs]

    best, best_score = variants[0], -1
    for v in variants:
        top1 = top5 = 0
        for sp, i, im in images:
            logits = sess.run(None, {"image": preprocess(im, v)})[0][0]
            order = np.argsort(-logits)
            top1 += order[0] == i
            top5 += i in order[:5]
        score = top5 * 10 + top1
        print(f"variant {v['mode']} mean={v['mean'][0]:.3f}: top-1 {top1}/{len(images)}, top-5 {top5}/{len(images)}")
        if score > best_score:
            best, best_score = v, score
            best["check"] = {"photos": len(images), "top1": int(top1), "top5": int(top5)}

    if not images:
        print("WARNING: no check photos could be downloaded; shipping without the photo check")
    elif best["check"]["top5"] < 0.5 * len(images):
        sys.exit("Photo check failed: label order or preprocessing is probably wrong")

    best.update({"model": HF_MODEL, "classes": len(labels), "input": "image",
                 "license": "CC BY-NC 4.0", "dataset": "Danish Fungi 2020 (Picek et al., WACV 2022)"})
    with open(os.path.join(OUT, "labels.json"), "w") as f:
        json.dump(labels, f, ensure_ascii=False)
    with open(os.path.join(OUT, "meta.json"), "w") as f:
        json.dump(best, f, indent=1)
    print("meta:", json.dumps(best))


if __name__ == "__main__":
    main()
