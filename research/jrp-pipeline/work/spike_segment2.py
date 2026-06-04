#!/usr/bin/env python3
"""Spike v2: add (a) dictionary-form reading lookup for inflected words and
(b) Mode C -> Mode A split-and-retry cascade for misses. Measure the lift.

Run: uv run --with sudachipy --with sudachidict-full python spike_segment2.py
"""
import os, sys, collections, random, functools

ROOT = os.path.expanduser("~/projects/anki-align")
sys.path.insert(0, os.path.join(ROOT, "research/pitch-pipeline/work"))
import validate as V
import sqlite3

KATA = {chr(c): chr(c - 0x60) for c in range(0x30A1, 0x30F7)}
def hira(s): return "".join(KATA.get(c, c) for c in s)

gold, token, _ = V.extract_gold()
gold_keys = {(e, hira(r)) for (e, r) in gold}
gold_terms = {e for (e, r) in gold}
def load_norm(name):
    m = V.load_dict(os.path.join(V.DICTD, name))
    return {(e, hira(r)) for (e, r) in m}, {e for (e, r) in m}
v2k, v2t = load_norm("アクセント辞典v2")
nhkk, nhkt = load_norm("NHK 2016 (Recommended)")
dict_keys, dict_terms = v2k | nhkk, v2t | nhkt
key_hit = gold_keys | dict_keys           # strict (term,reading)
term_hit = gold_terms | dict_terms        # term-only

db = sqlite3.connect(f"file:{V.COL}?immutable=1", uri=True)
db.create_collation("unicase", lambda a, b: (a.casefold() > b.casefold()) - (a.casefold() < b.casefold()))
mid = db.execute("SELECT id FROM notetypes WHERE name='jidoujisho Kinomoto'").fetchone()[0]
names = [r[0] for r in db.execute("SELECT name FROM fields WHERE ntid=? ORDER BY ord", (mid,))]
S = names.index("Sentence")
plain = [flds.split(V.SEP)[S].replace("<br>", " ").replace("<br/>", " ")
         for (flds,) in db.execute("SELECT flds FROM notes WHERE mid=?", (mid,))
         if (lambda s: s.strip() and not ("{" in s and ";" in s))(flds.split(V.SEP)[S])]
random.seed(7)
sample = random.sample(plain, 200)

from sudachipy import dictionary, tokenizer as T
tok = dictionary.Dictionary(dict="full").create()
A, C = T.Tokenizer.SplitMode.A, T.Tokenizer.SplitMode.C
CONTENT = {"名詞", "動詞", "形容詞", "副詞", "形状詞", "連体詞", "感動詞"}

@functools.lru_cache(maxsize=None)
def lemma_reading(dictform):
    """Reading of the dictionary form: tokenize it, concat token readings."""
    ms = tok.tokenize(dictform, C)
    return hira("".join(m.reading_form() or m.surface() for m in ms))

def strict_lookup(dictform, surface_reading):
    """Try (term, lemma_reading) then (term, surface_reading)."""
    for rd in (lemma_reading(dictform), hira(surface_reading)):
        if (dictform, rd) in key_hit:
            return True
    return False

def best_for(m):
    """Return ('hit'|'termonly'|'miss', via) for one content morpheme."""
    df, rd = m.dictionary_form(), (m.reading_form() or m.surface())
    if strict_lookup(df, rd):
        return "hit"
    if df in term_hit:
        return "termonly"
    return "miss"

cnt = collections.Counter()
recovered_examples = []
final_miss = collections.Counter()

for s in sample:
    for m in tok.tokenize(s, C):
        if m.part_of_speech()[0] not in CONTENT:
            continue
        cnt["total"] += 1
        r = best_for(m)
        if r == "hit":
            cnt["hit_C"] += 1
            continue
        # cascade: split this C unit into A sub-units, see if they all resolve
        subs = [x for x in m.split(A) if x.part_of_speech()[0] in CONTENT]
        if len(subs) > 1:
            sub_res = [best_for(x) for x in subs]
            if all(x != "miss" for x in sub_res):
                cnt["recovered_by_split"] += 1
                if len(recovered_examples) < 15:
                    recovered_examples.append(
                        (m.surface(), [(x.surface(), x.dictionary_form(),
                                        lemma_reading(x.dictionary_form()), br)
                                       for x, br in zip(subs, sub_res)]))
                continue
        # still unresolved
        if r == "termonly":
            cnt["termonly"] += 1
        else:
            cnt["miss"] += 1
            final_miss[(m.surface(), m.dictionary_form(), lemma_reading(m.dictionary_form()))] += 1

tot = cnt["total"]
def p(x): return f"{100*x/tot:.1f}%"
print("\n=============== Mode C + lemma-reading + split cascade ===============")
print(f"content units: {tot}")
print(f"  strict hit at Mode C (term,lemma-reading): {cnt['hit_C']:5d}  {p(cnt['hit_C'])}")
print(f"  recovered by Mode C->A split:              {cnt['recovered_by_split']:5d}  {p(cnt['recovered_by_split'])}")
strict_total = cnt['hit_C'] + cnt['recovered_by_split']
print(f"  ===> STRICT (term,reading) resolved:       {strict_total:5d}  {p(strict_total)}")
print(f"  term-only (reading still off):             {cnt['termonly']:5d}  {p(cnt['termonly'])}")
print(f"  ===> ANY source (strict+termonly):         {strict_total+cnt['termonly']:5d}  {p(strict_total+cnt['termonly'])}")
print(f"  true MISS (-> marine fallback):            {cnt['miss']:5d}  {p(cnt['miss'])}")

print("\n--- examples recovered by Mode C->A split (fixes over-merge) ---")
for surf, parts in recovered_examples:
    ps = "  +  ".join(f"{s}[{rd}]({br})" for s, df, rd, br in parts)
    print(f"  {surf}  ->  {ps}")

print("\n--- residual true misses (top 30) ---")
for (surf, df, rd), n in final_miss.most_common(30):
    print(f"  {n:3d}  {surf:14s} lemma={df:14s} reading={rd}")
