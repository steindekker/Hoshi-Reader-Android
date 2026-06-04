#!/usr/bin/env python3
"""Investigate the cards that get NO Japanese definition from 三国/広辞苑/実用.

For each gap card: deck, tags, whether it already has a Meaning, inferred origin
(from the Meaning markup shape), and which recovery path (if any) would fix it:
  - comma-split   : Term is several words joined; do the parts match?
  - deinflect     : Term is a conjugated form; does the dict-form match?
  - kanji-variant : old/variant kanji; does an NFKC/旧字 match?
  - reading-only  : reading matches some homophone entry (weak)
Run: uv run --with anki python gap.py
"""
import json, glob, re, os, unicodedata
from collections import Counter
from pathlib import Path
from anki.collection import Collection

ROOT = Path(__file__).parent
DICTS = ROOT / "dicts"
COL_SRC = os.path.expanduser("~/.local/share/Anki2/User 1/collection.anki2")
COL_COPY = "/tmp/dc_gap.anki2"


def load_index(folder):
    exprs, reads = set(), set()
    for tb in glob.glob(str(DICTS / folder / "term_bank_*.json")):
        for e in json.load(open(tb, encoding="utf-8")):
            exprs.add(e[0])
            if e[1]:
                reads.add(e[1])
    return exprs, reads


_KANJI = re.compile(r"[㐀-鿿々]")
_KANA = re.compile(r"^[ぁ-んァ-ヶー]+$")

def classify_origin(meaning):
    m = meaning
    if not m.strip():
        return "empty"
    # Kanji Study Word Model: bracket-with-semicolon furigana markup + ★ ratings
    if re.search(r"\[[^\]]*;[^\]]*\]", m) and "★" in m:
        return "KanjiStudy"
    if re.search(r"\[[^\]]*;[^\]]*\]", m):
        return "KanjiStudy?"
    # yomitan/Kinomoto wall: data-sc / many dict headers 【…】 with <br>
    if "data-sc" in m or m.count("【") >= 2:
        return "Kinomoto/yomitan"
    # Yuuna: 【…】 plain-ish
    if "【" in m and "<" not in m:
        return "Yuuna"
    # MyNote: HTML/JMdict gloss, often Dutch/English
    if "<" in m:
        return "MyNote/HTML"
    return "plain"


def main():
    import shutil
    idx = {f: load_index(f) for f in ("sankoku8", "koujien7", "jitsuyo", "jmdict")}

    JJ_ex = idx["sankoku8"][0] | idx["koujien7"][0] | idx["jitsuyo"][0]
    JJ_rd = idx["sankoku8"][1] | idx["koujien7"][1] | idx["jitsuyo"][1]
    JM_ex, JM_rd = idx["jmdict"]
    ALL_ex = JJ_ex | JM_ex
    ALL_rd = JJ_rd | JM_rd

    def jj_hit(t, r):
        return t in JJ_ex or (r and (r in JJ_ex or r in JJ_rd)) or t in JJ_rd

    def any_hit(t, r):
        return t in ALL_ex or (r and (r in ALL_ex or r in ALL_rd)) or t in ALL_rd

    shutil.copy(COL_SRC, COL_COPY)
    col = Collection(COL_COPY)
    m = col.models.by_name("jidoujisho Kinomoto")
    fld = [f["name"] for f in m["flds"]]
    nids = col.find_notes('note:"jidoujisho Kinomoto" -deck:*Core2.3k* -deck:*文法*')
    rows = []
    for nid in nids:
        note = col.get_note(nid)
        d = dict(zip(fld, note.fields))
        t, r = d.get("Term", "").strip(), d.get("Reading", "").strip()
        if not t:
            continue
        if jj_hit(t, r):
            continue  # has a J-J def -> not in the gap
        deck = col.decks.name(note.cards()[0].did)
        rows.append({"t": t, "r": r, "deck": deck.split("::")[0],
                     "tags": " ".join(note.tags),
                     "meaning": d.get("Meaning", ""),
                     "origin": classify_origin(d.get("Meaning", ""))})
    col.close()

    N = len(rows)
    print(f"=== {N} cards with NO J-J definition (三国/広辞苑/実用, +reading) ===\n")

    # ---- recovery classification ----
    def split_parts(t):
        return [p.strip() for p in re.split(r"[,、，]", t) if p.strip()]

    def clean_term(t):
        """Strip jidoujisho/KanjiStudy markup baked into the Term field."""
        t = re.sub(r"<[^>]+>", "", t)
        t = re.sub(r"\[([^\]|]+)\|[^\]]+\]", r"\1", t)  # [surface|reading] -> surface
        t = re.sub(r"[（(][ぁ-んァ-ヶー]+[）)]\s*$", "", t)  # trailing (reading)
        return t.strip()

    cats = Counter()
    examples = {}
    recover_comma = []
    recover_variant = []
    drop_list = []
    for row in rows:
        t, r = row["t"], row["r"]
        tt = clean_term(t)
        parts = split_parts(tt)
        cat = None
        if len(parts) > 1:
            hits = sum(1 for p in parts if any_hit(p, ""))
            cat = "comma-multi"
            row["recover"] = f"split→{hits}/{len(parts)} parts match"
            recover_comma.append(row)
        elif tt != t and (any_hit(tt, r) or jj_hit(tt, r)):
            cat = "term-markup"  # [x|y] / (reading) / <tag> baked into Term
            row["recover"] = f"clean→{tt} matches"
            recover_variant.append(row)
        else:
            # kanji-variant: NFKC normalize, retry
            norm = unicodedata.normalize("NFKC", tt)
            if norm != tt and any_hit(norm, r):
                cat = "kanji-variant"
                row["recover"] = f"NFKC→{norm} matches"
                recover_variant.append(row)
            elif r and (r in JM_rd or r in JM_ex):
                cat = "jmdict-reading-only"  # J-E can fill via reading
                row["recover"] = "JMdict via reading"
            elif tt in JM_ex:
                cat = "jmdict-written"
                row["recover"] = "JMdict written"
            else:
                cat = "no-dict-anywhere"
                row["recover"] = "—"
                drop_list.append(row)
        row["cat"] = cat
        cats[cat] += 1
        examples.setdefault(cat, []).append(row)

    print("recovery categories:")
    for cat, n in cats.most_common():
        ex = examples[cat][:8]
        exs = "  ".join(f'{e["t"]}' + (f'({e["r"]})' if e["r"] else "") for e in ex)
        print(f"  {cat:22s} {n:4d}   {exs}")

    print("\norigin of the gap cards (from Meaning markup):")
    for o, n in Counter(row["origin"] for row in rows).most_common():
        print(f"  {o:18s} {n}")

    print("\ndeck of the gap cards:")
    for dk, n in Counter(row["deck"] for row in rows).most_common(12):
        print(f"  {dk:28s} {n}")

    print("\nhave an existing (non-empty) Meaning already:",
          sum(1 for row in rows if row["meaning"].strip()), "/", N)

    # the true drop pile (nothing in any dict, single token)
    print(f"\n=== TRUE DROP pile: {len(drop_list)} cards, nothing in any dict ===")
    # split drop pile: arcane reading (kana-only term) vs odd kanji
    for row in drop_list:
        kind = "kana-term" if _KANA.match(row["t"]) else "kanji-term"
        row["dropkind"] = kind
    for k, n in Counter(row["dropkind"] for row in drop_list).most_common():
        print(f"  {k}: {n}")
    print("  full drop list:")
    for row in sorted(drop_list, key=lambda x: x["origin"]):
        snip = re.sub(r"\s+", " ", re.sub(r"<[^>]+>", " ", row["meaning"]))[:50]
        print(f"    {row['t']:14s} {row['r']:12s} [{row['origin']:14s}] {row['deck'][:14]:14s} {snip}")


if __name__ == "__main__":
    main()
