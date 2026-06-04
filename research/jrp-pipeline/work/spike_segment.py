#!/usr/bin/env python3
"""Spike: does SudachiPy Mode C give good compound units, and do those units
hit our accent sources (user gold + アクセント辞典v2 + NHK2016)?

Run: uv run --with sudachipy --with sudachidict-full python spike_segment.py
Reuses the JRP gold parser + dict loader from ../work/validate.py.
"""
import os, sys, glob, json, collections, random

ROOT = os.path.expanduser("~/projects/anki-align")
sys.path.insert(0, os.path.join(ROOT, "research/pitch-pipeline/work"))
import validate as V  # parse_jrp, extract_gold, load_dict, DICTD, COL, SEP

import sqlite3

KATA = {chr(c): chr(c - 0x60) for c in range(0x30A1, 0x30F7)}
def hira(s): return "".join(KATA.get(c, c) for c in s)

# ---- accent sources --------------------------------------------------------
print("Loading gold + dicts ...", file=sys.stderr)
gold, token, _ = V.extract_gold()           # (expr,reading)->(yg,accents)
gold_keys = {(e, hira(r)) for (e, r) in gold}
gold_terms = {e for (e, r) in gold}

def load_norm(name):
    m = V.load_dict(os.path.join(V.DICTD, name))
    keys = {(e, hira(r)) for (e, r) in m}
    terms = {e for (e, r) in m}
    return keys, terms

v2_keys, v2_terms = load_norm("アクセント辞典v2")
nhk_keys, nhk_terms = load_norm("NHK 2016 (Recommended)")

dict_keys = v2_keys | nhk_keys
dict_terms = v2_terms | nhk_terms
any_keys = gold_keys | dict_keys
any_terms = gold_terms | dict_terms
print(f"gold keys={len(gold_keys)} terms={len(gold_terms)} | "
      f"dict keys={len(dict_keys)} terms={len(dict_terms)}", file=sys.stderr)

# ---- plain sentences -------------------------------------------------------
db = sqlite3.connect(f"file:{V.COL}?immutable=1", uri=True)
db.create_collation("unicase", lambda a, b: (a.casefold() > b.casefold()) - (a.casefold() < b.casefold()))
mid = db.execute("SELECT id FROM notetypes WHERE name='jidoujisho Kinomoto'").fetchone()[0]
names = [r[0] for r in db.execute("SELECT name FROM fields WHERE ntid=? ORDER BY ord", (mid,))]
S = names.index("Sentence")
plain = []
for (flds,) in db.execute("SELECT flds FROM notes WHERE mid=?", (mid,)):
    s = flds.split(V.SEP)[S]
    if s.strip() and not ("{" in s and ";" in s):
        # strip simple HTML breaks for tokenization
        plain.append(s.replace("<br>", " ").replace("<br/>", " "))
random.seed(7)
sample = random.sample(plain, 120)
print(f"plain sentences total={len(plain)}, sampling {len(sample)}", file=sys.stderr)

# ---- sudachi ---------------------------------------------------------------
from sudachipy import dictionary, tokenizer as T
tok = dictionary.Dictionary(dict="full").create()
A, C = T.Tokenizer.SplitMode.A, T.Tokenizer.SplitMode.C

CONTENT = {"名詞", "動詞", "形容詞", "副詞", "形状詞", "連体詞", "感動詞"}
DROP = {"助詞", "助動詞", "補助記号", "記号", "空白", "接続詞"}

def is_content(m):
    return m.part_of_speech()[0] in CONTENT

def acc_source(dictform, reading):
    k = (dictform, hira(reading))
    if k in gold_keys: return "gold"
    if k in dict_keys: return "dict"
    if dictform in gold_terms: return "gold~"   # term match, reading differs
    if dictform in dict_terms: return "dict~"
    return "MISS"

# ---- measure ---------------------------------------------------------------
cnt = collections.Counter()
compound_examples = []   # (sentence, A-units, C-units) where C merged >1 A unit
miss_examples = collections.Counter()
per_sentence_render = []

for s in sample:
    a_units = [m.surface() for m in tok.tokenize(s, A)]
    c_morphs = list(tok.tokenize(s, C))
    rendered = []
    merged_here = []
    for m in c_morphs:
        surf, df = m.surface(), m.dictionary_form()
        rd = m.reading_form() or surf
        # how many Mode A units does this C unit cover?
        sub = m.split(A)
        n_sub = len(sub)
        if is_content(m):
            src = acc_source(df, rd)
            cnt[f"content:{src}"] += 1
            cnt["content_total"] += 1
            if src == "MISS":
                miss_examples[(surf, df, hira(rd))] += 1
            if n_sub > 1:
                cnt["compound_units"] += 1
                cnt[f"compound:{src}"] += 1
                merged_here.append((surf, df, hira(rd), src, n_sub))
            tag = {"gold":"✓g","dict":"✓d","gold~":"~g","dict~":"~d","MISS":"✗"}[src]
            rendered.append(f"{surf}[{hira(rd)}]{tag}")
        elif m.part_of_speech()[0] in DROP:
            rendered.append(surf)
        else:
            rendered.append(f"{surf}?")
    if merged_here:
        compound_examples.append((s, merged_here))
    per_sentence_render.append(" ".join(rendered))

# ---- report ----------------------------------------------------------------
print("\n================= SEGMENTATION + ACCENT-SOURCE COVERAGE =================")
ct = cnt["content_total"]
def pct(x): return f"{100*x/ct:.1f}%" if ct else "-"
print(f"content units: {ct}")
for src in ("gold", "dict", "gold~", "dict~", "MISS"):
    print(f"  {src:6s}: {cnt['content:'+src]:5d}  {pct(cnt['content:'+src])}")
hit = ct - cnt["content:MISS"]
print(f"  --> any accent source (incl ~term): {hit} ({pct(hit)})")
strict = cnt["content:gold"] + cnt["content:dict"]
print(f"  --> strict (expr,reading) hit:      {strict} ({pct(strict)})")

cu = cnt["compound_units"]
print(f"\ncompound units (Mode C merged >1 Mode A unit): {cu}")
if cu:
    for src in ("gold", "dict", "gold~", "dict~", "MISS"):
        print(f"  {src:6s}: {cnt['compound:'+src]:5d}  {100*cnt['compound:'+src]/cu:.1f}%")

print("\n================= 12 SAMPLE COMPOUND SEGMENTATIONS =================")
for s, merged in compound_examples[:12]:
    print(f"\n  {s[:70]}")
    for surf, df, rd, src, n in merged:
        print(f"     COMPOUND {surf}  dict_form={df}  reading={rd}  [{src}]  (={n} Mode-A units)")

print("\n================= 15 RENDERED SENTENCES (✓g gold ✓d dict ~ term-only ✗ miss) =================")
for r in per_sentence_render[:15]:
    print("  " + r)

print("\n================= TOP 25 MISSES =================")
for (surf, df, rd), n in miss_examples.most_common(25):
    print(f"  {n:3d}  surface={surf:12s} dict_form={df:12s} reading={rd}")
