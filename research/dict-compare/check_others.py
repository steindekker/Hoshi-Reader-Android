#!/usr/bin/env python3
"""For the gap cards with no J-J def from 三省堂/広辞苑/実用, check whether any
OTHER dictionary (大辞泉 / 大辞林 / 新明解 / Jitendex) has them.

Splits the gap into 'jmdict-only' (JMdict has it) and 'no-dict' (JMdict doesn't),
then reports per-card coverage across the other dicts + incremental coverage.

Run: uv run --with anki python check_others.py
"""
import json, glob, os, re
from pathlib import Path
from anki.collection import Collection

ROOT = Path(__file__).parent
DICTS = ROOT / "dicts"
COL = os.path.expanduser("~/.local/share/Anki2/User 1/collection.anki2")

CHOSEN_JJ = ["sankoku8", "koujien7", "jitsuyo"]
OTHERS = [("daijisen", "大辞泉"), ("daijirin4", "大辞林"), ("smk8", "新明解"), ("jitendex", "Jitendex")]


def load_index(folder):
    ex, rd = set(), set()
    for tb in glob.glob(str(DICTS / folder / "term_bank_*.json")):
        for e in json.load(open(tb, encoding="utf-8")):
            ex.add(e[0])
            if e[1]:
                rd.add(e[1])
    return ex, rd


def main():
    import shutil
    idx = {f: load_index(f) for f in
           CHOSEN_JJ + ["jmdict"] + [o[0] for o in OTHERS]}

    def hit(fid, t, r):
        ex, rd = idx[fid]
        return t in ex or (r and (r in ex or r in rd)) or t in rd

    JJ = lambda t, r: any(hit(f, t, r) for f in CHOSEN_JJ)

    shutil.copy(COL, "/tmp/dc_other.anki2")
    col = Collection("/tmp/dc_other.anki2")
    m = col.models.by_name("jidoujisho Kinomoto")
    fld = [f["name"] for f in m["flds"]]
    gap = []
    for nid in col.find_notes('note:"jidoujisho Kinomoto" -deck:*Core2.3k* -deck:*文法*'):
        d = dict(zip(fld, col.get_note(nid).fields))
        t, r = d.get("Term", "").strip(), d.get("Reading", "").strip()
        if t and "," not in t and "、" not in t and not JJ(t, r):
            gap.append((t, r))
    col.close()

    jmonly = [(t, r) for (t, r) in gap if hit("jmdict", t, r)]
    nodict = [(t, r) for (t, r) in gap if not hit("jmdict", t, r)]
    print(f"gap (no 三省堂/広辞苑/実用): {len(gap)}   jmdict-only={len(jmonly)}  no-dict={len(nodict)}\n")

    def report(group, label):
        print(f"=== {label} ({len(group)}) — coverage in other dicts ===")
        hdr = "  " + "term".ljust(14) + "".join(n.ljust(10) for _, n in OTHERS)
        print(hdr)
        covered_by_any = 0
        per_dict = {o[0]: 0 for o in OTHERS}
        for t, r in group:
            marks = ""
            any_hit = False
            for fid, _ in OTHERS:
                h = hit(fid, t, r)
                marks += ("✓" if h else "·").ljust(10)
                if h:
                    per_dict[fid] += 1
                    any_hit = True
            covered_by_any += any_hit
            # only print rows for the smaller group or uncovered-elsewhere ones
            if len(group) <= 12 or not any_hit:
                print("  " + (t[:13]).ljust(14) + marks)
        print(f"  → covered by ≥1 other dict: {covered_by_any}/{len(group)}")
        for fid, name in OTHERS:
            print(f"     {name:10s} alone covers {per_dict[fid]}/{len(group)}")
        # incremental: if we ADDED 大辞泉, then 大辞林, then 新明解 to the J-J chain
        chain = set()
        print("  incremental J-J gain (adding to 三省堂/広辞苑/実用):")
        for fid, name in OTHERS[:3]:  # the J-J ones
            new = sum(1 for t, r in group if hit(fid, t, r) and (t, r) not in chain_seen(group, chain, idx, fid))
            # simpler: recompute union
        print()

    # simpler incremental computation
    def incr(group, label):
        print(f"--- {label}: incremental J-J coverage as we add dicts ---")
        union = set()
        order = [("daijirin4", "大辞林"), ("daijisen", "大辞泉"), ("smk8", "新明解")]
        for fid, name in order:
            newly = [(t, r) for (t, r) in group if (t, r) not in union and hit(fid, t, r)]
            union |= set(newly)
            print(f"   + {name:8s} adds {len(newly):3d}  → union {len(union)}/{len(group)}")
        left = [(t, r) for (t, r) in group if (t, r) not in union]
        print(f"   still uncovered by any J-J: {len(left)}")
        if left and len(left) <= 30:
            print("     ", "  ".join(f"{t}({r})" if r else t for t, r in left))
        print()

    report_simple(jmonly, "jmdict-only", idx, hit)
    incr(jmonly, "jmdict-only")
    report_simple(nodict, "no-dict", idx, hit)
    incr(nodict, "no-dict")


def chain_seen(*a):  # unused stub
    return set()


def report_simple(group, label, idx, hit):
    print(f"=== {label} ({len(group)}) — per-dict coverage ===")
    per = {o[0]: 0 for o in OTHERS}
    none_anywhere = []
    for t, r in group:
        anyh = False
        for fid, _ in OTHERS:
            if hit(fid, t, r):
                per[fid] += 1
                anyh = True
        if not anyh:
            none_anywhere.append((t, r))
    for fid, name in OTHERS:
        print(f"   {name:10s} {per[fid]:3d}/{len(group)}")
    print(f"   covered by NONE of the others: {len(none_anywhere)}")
    if none_anywhere and len(none_anywhere) <= 40:
        print("     ", "  ".join(f"{t}({r})" if r else t for t, r in none_anywhere))
    print()


if __name__ == "__main__":
    main()
