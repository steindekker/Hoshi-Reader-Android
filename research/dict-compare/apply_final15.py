#!/usr/bin/env python3
"""Final 15 gap cards: fix the 8 with markup-dirty Terms, drop the 7 true orphans.

FIX  — clean Term/Reading/Furigana (strip <tags>, [surface|reading], trailing (reading)),
       tag ankialign::term-fixed. After cleaning the word matches the dict chain.
DROP — delete the 7 cards with no definition in any dictionary (grammar/idiom/slang).

Default DRY RUN. --apply mutates a copy; --apply --live mutates the real collection
(backup first). Run: uv run --with anki python apply_final15.py [--apply [--live]]
"""
import json, glob, re, os, sys, shutil
from anki.collection import Collection

DICTS = "dicts"
COL_LIVE = os.path.expanduser("~/.local/share/Anki2/User 1/collection.anki2")
COL_COPY = "/tmp/dc_apply15.anki2"
BACKUP_DIR = os.path.expanduser("~/.local/share/Anki2/User 1/backups")
JJ = ["sankoku8", "koujien7", "jitsuyo", "daijisen"]


def load(f):
    ex, rd = set(), set()
    for tb in glob.glob(f"{DICTS}/{f}/term_bank_*.json"):
        for e in json.load(open(tb, encoding="utf-8")):
            ex.add(e[0])
            if e[1]:
                rd.add(e[1])
    return ex, rd


_KANJI = re.compile(r"[㐀-鿿々]")

def clean_term(t):
    t = re.sub(r"<[^>]+>", "", t)
    t = re.sub(r"\[([^\]|]+)\|[^\]]+\]", r"\1", t)
    t = re.sub(r"[（(][ぁ-んァ-ヶー]+[）)]\s*$", "", t)
    return t.strip()

def clean_reading(r):
    r = re.sub(r"<[^>]+>", "", r)
    r = re.sub(r"\[[^\]|]+\|([^\]]+)\]", r"\1", r)
    r = re.sub(r"[（(][ぁ-んァ-ヶー]+[）)]\s*$", "", r)
    return r.strip()

def clean_furi(f):
    f = re.sub(r"<[^>]+>", "", f)
    f = re.sub(r"\[([^\]|]+)\|([^\]]+)\]",
               lambda m: f"{m.group(1)}[{m.group(2)}]" if _KANJI.search(m.group(1)) else m.group(1), f)
    f = re.sub(r"\s*[（(][ぁ-んァ-ヶー]+[）)]\s*$", "", f)
    return f.strip()


def main():
    apply = "--apply" in sys.argv
    live = "--live" in sys.argv
    src = COL_LIVE if live else COL_COPY
    if not live:
        shutil.copy(COL_LIVE, COL_COPY)

    idx = {f: load(f) for f in JJ + ["jmdict"]}
    def hit(f, t, r):
        ex, rd = idx[f]
        return t in ex or (r and (r in ex or r in rd)) or t in rd
    def jjhit(t, r):
        return any(hit(f, t, r) for f in JJ)
    def anyhit(t, r):
        return jjhit(t, r) or hit("jmdict", t, r)

    col = Collection(src)
    if apply and live:
        os.makedirs(BACKUP_DIR, exist_ok=True)
        print("backup:", col.create_backup(backup_folder=BACKUP_DIR, force=True, wait_for_completion=True))

    m = col.models.by_name("jidoujisho Kinomoto")
    fld = [f["name"] for f in m["flds"]]
    fix, drop = [], []
    for nid in col.find_notes('note:"jidoujisho Kinomoto" -deck:*Core2.3k* -deck:*文法*'):
        d = dict(zip(fld, col.get_note(nid).fields))
        t, r = d.get("Term", "").strip(), d.get("Reading", "").strip()
        if not t or "," in t or "、" in t or jjhit(t, r):
            continue
        tt = clean_term(t)
        if tt != t and anyhit(tt, r if not clean_reading(r) else clean_reading(r)):
            fix.append((nid, d))
        elif not anyhit(t, r):
            drop.append((nid, t, r))

    print(f"=== final-15  ({'LIVE' if (apply and live) else 'copy' if apply else 'DRY RUN'}) ===")
    print(f"FIX ({len(fix)}):")
    for nid, d in fix:
        print(f"  {d['Term']!r:28s} -> Term {clean_term(d['Term'])!r}  "
              f"Reading {clean_reading(d.get('Reading',''))!r}  Furi {clean_furi(d.get('Furigana',''))!r}")
    print(f"DROP ({len(drop)}):")
    for nid, t, r in drop:
        print(f"  {t!r} ({r!r})")

    if not apply:
        col.close()
        print("\n(dry run — nothing written)")
        return

    for nid, d in fix:
        note = col.get_note(nid)
        nd = dict(zip(fld, note.fields))
        nd["Term"] = clean_term(nd.get("Term", ""))
        nd["Reading"] = clean_reading(nd.get("Reading", ""))
        nd["Furigana"] = clean_furi(nd.get("Furigana", ""))
        note.fields = [nd[f] for f in fld]
        if "ankialign::term-fixed" not in note.tags:
            note.tags.append("ankialign::term-fixed")
        col.update_note(note)
    if drop:
        col.remove_notes([nid for nid, _, _ in drop])
    col.close()
    print(f"\nAPPLIED: fixed {len(fix)} Terms, dropped {len(drop)} cards "
          f"({'LIVE' if live else COL_COPY})")


if __name__ == "__main__":
    main()
