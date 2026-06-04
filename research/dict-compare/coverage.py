#!/usr/bin/env python3
"""Coverage check for the chosen dictionary set.

For every in-scope Kinomoto note, does a usable entry exist in:
  - 三省堂国語辞典 第八版 (Sankoku 8)   J-J primary
  - 広辞苑 第七版        (Koujien 7)    J-J fallback
  - JMdict                              J-E
  - 実用日本語表現辞典  (Jitsuyo)        expressions

Reports written-form matches (confident), reading-recovery, the J-J union,
and what each fallback adds. Run: uv run --with anki python coverage.py
"""
import json, glob, re, os
from pathlib import Path
from anki.collection import Collection

ROOT = Path(__file__).parent
DICTS = ROOT / "dicts"
COL_SRC = os.path.expanduser("~/.local/share/Anki2/User 1/collection.anki2")
COL_COPY = "/tmp/dc_cov.anki2"

CHECK = [
    ("sankoku8", "三省堂国語辞典"),
    ("koujien7", "広辞苑"),
    ("jmdict",   "JMdict"),
    ("jitsuyo",  "実用日本語表現辞典"),
]


def load_index(folder):
    exprs, reads = set(), set()
    for tb in glob.glob(str(DICTS / folder / "term_bank_*.json")):
        for e in json.load(open(tb, encoding="utf-8")):
            exprs.add(e[0])
            if e[1]:
                reads.add(e[1])
    return exprs, reads


def main():
    import shutil
    idx = {}
    for fid, _ in CHECK:
        idx[fid] = load_index(fid)
        print(f"loaded {fid}: {len(idx[fid][0])} exprs, {len(idx[fid][1])} readings")

    shutil.copy(COL_SRC, COL_COPY)
    col = Collection(COL_COPY)
    m = col.models.by_name("jidoujisho Kinomoto")
    fld = [f["name"] for f in m["flds"]]
    nids = col.find_notes('note:"jidoujisho Kinomoto" -deck:*Core2.3k* -deck:*文法*')
    notes = []
    for nid in nids:
        d = dict(zip(fld, col.get_note(nid).fields))
        t, r = d.get("Term", "").strip(), d.get("Reading", "").strip()
        if t:
            notes.append((t, r))
    col.close()
    N = len(notes)
    print(f"\nin-scope Kinomoto notes: {N}\n")

    def has_written(fid, t):
        return t in idx[fid][0]

    def has_reading(fid, t, r):
        ex, rd = idx[fid]
        return (r and (r in ex or r in rd)) or t in rd

    # per-dict tallies
    def pct(n): return f"{n:5d}  {100*n/N:5.1f}%"
    print("per dictionary (written-form match  /  + reading recovers):")
    tiers = {}
    for fid, name in CHECK:
        w = [(t, r) for (t, r) in notes if has_written(fid, t)]
        wr = [(t, r) for (t, r) in notes if has_written(fid, t) or has_reading(fid, t, r)]
        tiers[fid] = (set(w), set(wr))
        print(f"  {name:20s} written {pct(len(w))}   +reading {pct(len(wr))}")

    # J-J union (written) and fallback contribution
    print("\nJ-J (三省堂 primary → 広辞苑 fallback), written-form:")
    s_w = tiers["sankoku8"][0]
    k_w = tiers["koujien7"][0]
    union_w = s_w | k_w
    print(f"  三省堂 alone            {pct(len(s_w))}")
    print(f"  広辞苑 adds (not in 三国) {pct(len(k_w - s_w))}")
    print(f"  union 三国∪広辞苑        {pct(len(union_w))}")

    # with reading recovery
    s_wr = tiers["sankoku8"][1]
    k_wr = tiers["koujien7"][1]
    union_wr = s_wr | k_wr
    print(f"  union (+reading)        {pct(len(union_wr))}")

    # 実用 adds (for expressions not covered by the J-J pair)
    j_wr = tiers["jitsuyo"][1]
    print(f"\n実用日本語表現辞典 adds over J-J union (+reading):")
    print(f"  実用 covers              {pct(len(j_wr))}")
    print(f"  実用 adds (not in J-J)   {pct(len(j_wr - union_wr))}")
    union_all = union_wr | j_wr
    print(f"  union 三国∪広辞苑∪実用   {pct(len(union_all))}")

    # JMdict (J-E) coverage
    jm_w = tiers["jmdict"][0]
    jm_wr = tiers["jmdict"][1]
    print(f"\nJMdict (J-E): written {pct(len(jm_w))}   +reading {pct(len(jm_wr))}")

    # the gap: notes with NO J-J entry at all (even +reading, incl 実用)
    covered = union_all
    gap = [(t, r) for (t, r) in notes if (t, r) not in covered]
    print(f"\nNO Japanese definition anywhere (三国/広辞苑/実用, +reading): {len(gap)}  ({100*len(gap)/N:.1f}%)")
    # show a sample, sorted to surface inflected/odd spellings
    sample = gap[:40]
    print("  examples:", "  ".join(f"{t}({r})" if r else t for t, r in sample))

    # also: gap that JMdict DOES cover (so J-E base would still help there)
    gap_jm = [(t, r) for (t, r) in gap if (t, r) in jm_wr]
    print(f"  …of those, JMdict has {len(gap_jm)} (J-E could still fill)")
    only_gap = [(t, r) for (t, r) in gap if (t, r) not in jm_wr]
    print(f"  …truly nothing (no dict at all): {len(only_gap)}")
    print("  truly-nothing examples:", "  ".join(f"{t}({r})" if r else t for t, r in only_gap[:40]))


if __name__ == "__main__":
    main()
