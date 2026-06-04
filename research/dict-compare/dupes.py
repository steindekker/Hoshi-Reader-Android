#!/usr/bin/env python3
"""For each of the 195 gap cards, is the same word already on another card?

Builds an index of every note in the collection (all decks/notetypes) keyed by
its cleaned head-word, then checks each gap card — including splitting the
comma-joined Terms into parts — against it. Writes dupes.json (nid -> dup info)
so triage.py can badge duplicates.

Run: uv run --with anki python dupes.py
"""
import json, glob, re, os
from collections import defaultdict
from pathlib import Path
from anki.collection import Collection

ROOT = Path(__file__).parent
DICTS = ROOT / "dicts"
COL_SRC = os.path.expanduser("~/.local/share/Anki2/User 1/collection.anki2")
COL_COPY = "/tmp/dc_dupes.anki2"

WORD_FIELDS = ["Term", "Target Word", "Expression", "Word", "Vocab", "Kanji",
               "EntryFurigana", "Front", "見出し"]


def clean_term(t):
    t = re.sub(r"<[^>]+>", "", t)
    t = re.sub(r"\[([^\]|]+)\|[^\]]+\]", r"\1", t)
    t = re.sub(r"[（(][ぁ-んァ-ヶー]+[）)]\s*$", "", t)
    return t.strip()


def head_word(notetype_fields, fvals):
    """Pick the most word-like field value for a note."""
    fmap = dict(zip(notetype_fields, fvals))
    for k in WORD_FIELDS:
        if fmap.get(k, "").strip():
            return clean_term(fmap[k])
    return clean_term(fvals[0]) if fvals else ""


# load J-J / JMdict just to recompute the 195 gap set the same way triage does
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
    idx = {f: load_index(f) for f in ("sankoku8", "koujien7", "jitsuyo")}
    JJ_ex = set().union(*(idx[f][0] for f in idx))
    JJ_rd = set().union(*(idx[f][1] for f in idx))

    def jj_hit(t, r):
        return t in JJ_ex or (r and (r in JJ_ex or r in JJ_rd)) or t in JJ_rd

    shutil.copy(COL_SRC, COL_COPY)
    col = Collection(COL_COPY)
    nt_fields = {m["id"]: [f["name"] for f in m["flds"]] for m in col.models.all()}
    nt_name = {m["id"]: m["name"] for m in col.models.all()}

    # 1) index EVERY note by cleaned head-word
    index = defaultdict(list)
    all_notes = {}
    for nid in col.find_notes(""):
        note = col.get_note(nid)
        flds = nt_fields[note.mid]
        hw = head_word(flds, note.fields)
        deck = col.decks.name(note.cards()[0].did) if note.cards() else "?"
        rec = {"nid": nid, "deck": deck, "nt": nt_name[note.mid], "hw": hw}
        all_notes[nid] = rec
        if hw:
            index[hw].append(rec)

    # 2) the 195 gap cards
    kino = col.models.by_name("jidoujisho Kinomoto")
    kfld = [f["name"] for f in kino["flds"]]
    gap_nids = []
    for nid in col.find_notes('note:"jidoujisho Kinomoto" -deck:*Core2.3k* -deck:*文法*'):
        d = dict(zip(kfld, col.get_note(nid).fields))
        t, r = d.get("Term", "").strip(), d.get("Reading", "").strip()
        if t and not jj_hit(t, r):
            gap_nids.append((nid, t, r))

    # 3) duplicate check
    out = {}
    cat = defaultdict(int)
    for nid, t, r in gap_nids:
        tt = clean_term(t)
        parts = [p.strip() for p in re.split(r"[,、，]", tt) if p.strip()] or [tt]
        part_dups = {}
        for p in parts:
            others = [x for x in index.get(p, []) if x["nid"] != nid]
            part_dups[p] = others
        n_parts = len(parts)
        n_covered = sum(1 for p in parts if part_dups[p])
        if n_parts > 1:
            kind = "multi-all-dup" if n_covered == n_parts else (
                   "multi-some-dup" if n_covered else "multi-unique")
        else:
            kind = "dup" if n_covered else "unique"
        cat[kind] += 1
        out[str(nid)] = {
            "term": t, "kind": kind,
            "parts": {p: [{"deck": x["deck"], "nt": x["nt"], "hw": x["hw"]} for x in part_dups[p]]
                      for p in parts},
        }
    col.close()

    (ROOT / "dupes.json").write_text(json.dumps(out, ensure_ascii=False, indent=1), encoding="utf-8")

    print(f"=== duplicate check over {len(all_notes)} total notes; {len(gap_nids)} gap cards ===\n")
    for k, n in sorted(cat.items(), key=lambda x: -x[1]):
        print(f"  {k:16s} {n}")
    fulldup = sum(cat[k] for k in ("dup", "multi-all-dup"))
    print(f"\nfully covered by existing card(s) → safe drop candidates: {fulldup}")

    # show examples of each kind with where the dup lives
    print("\nexamples:")
    shown = defaultdict(int)
    for nid, t, r in gap_nids:
        info = out[str(nid)]
        k = info["kind"]
        if shown[k] >= 5:
            continue
        shown[k] += 1
        locs = []
        for p, dl in info["parts"].items():
            if dl:
                locs.append(f'{p}=[{", ".join(sorted(set(x["deck"].split("::")[0]+"/"+x["nt"] for x in dl)))}]')
            else:
                locs.append(f"{p}=∅")
        print(f"  [{k:14s}] {t:18s} → {'  '.join(locs)}")


if __name__ == "__main__":
    main()
