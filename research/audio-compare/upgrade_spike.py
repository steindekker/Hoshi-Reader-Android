#!/usr/bin/env python3
"""Read-only spike for the SEPARATE 're-pitch existing audio' pass (not goal #3 fill).

Motivation: the user's existing Term Audio is almost all jidoujisho-mined
JapanesePod101, which has frequent wrong pitch accent. We cannot identify jpod
files forensically (opaque timestamp filenames; clips ~0.9s are too short for
Chromaprint; durations inconclusive). But it doesn't matter: wherever an accent
dictionary (nhk16/shinmeikai8) has a reading-correct recording, that recording is
authoritative and fixes the pitch regardless of the current file's origin.

Policy (decided): replace existing Term Audio ONLY when nhk16 or shinmeikai8 has an
exact (term, reading) hit (nhk16 preferred). Never replace with forvo/jpod. Leave
forvo-only and no-source cards untouched. Non-destructive (keep old media, rewrite
only the [sound:] field ref; rely on backup-before-op). Reversible.

This measures the upgrade population. Read-only, immutable mode (safe with Anki open).
Run: python3 upgrade_spike.py
"""
import re
import sqlite3
from pathlib import Path

COL = Path.home() / ".local/share/Anki2/User 1/collection.anki2"
DB = Path.home() / ".local/share/Anki2/addons21/1045800357/user_files/entries.db"
EXCLUDE_DECK_SUBSTRINGS = ["Core2.3k", "文法"]
FS = "\x1f"
SND = re.compile(r"\[sound:([^\]]+)\]")
TAG = re.compile(r"<[^>]+>")
KATA = {cp: cp - 0x60 for cp in range(0x30A1, 0x30F7)}


def ro(p):
    return sqlite3.connect(f"file:{p}?immutable=1", uri=True)


def clean(s):
    return TAG.sub("", s or "").strip()


def main():
    col = ro(COL)
    ntid = col.execute("SELECT id FROM notetypes WHERE name LIKE '%Kinomoto%'").fetchone()[0]
    o = {n: i for i, n in col.execute("SELECT ord, name FROM fields WHERE ntid=?", (ntid,))}
    it, ir, ia = o["Term"], o["Reading"], o["Term Audio"]
    deck_name = dict(col.execute("SELECT id, name FROM decks"))
    excluded = {nid for nid, did in col.execute("SELECT nid, did FROM cards")
                if any(s in deck_name.get(did, "") for s in EXCLUDE_DECK_SUBSTRINGS)}
    rows = col.execute("SELECT id, flds FROM notes WHERE mid=?", (ntid,)).fetchall()
    col.close()

    db = ro(DB)

    def hit(term, reading, src):
        return bool(db.execute(
            "SELECT 1 FROM entries WHERE expression=? AND reading=? AND source=? LIMIT 1",
            (term, reading, src)).fetchone())

    have = nhk = shin = dict_any = forvo_only = none_ = no_read = 0
    for nid, flds in rows:
        if nid in excluded:
            continue
        f = flds.split(FS)
        if not SND.findall(f[ia]):
            continue
        have += 1
        term = clean(f[it])
        reading = clean(f[ir]).translate(KATA)
        if not reading:
            no_read += 1
        h_n = hit(term, reading, "nhk16") if reading else False
        h_s = hit(term, reading, "shinmeikai8") if reading else False
        nhk += h_n
        shin += h_s
        if h_n or h_s:
            dict_any += 1
        elif db.execute("SELECT 1 FROM entries WHERE expression=? AND source='forvo' LIMIT 1", (term,)).fetchone():
            forvo_only += 1
        else:
            none_ += 1
    db.close()

    p = lambda n: f"{100*n/have:.1f}%" if have else "—"
    print(f"in-scope Kinomoto cards WITH Term Audio: {have}")
    print(f"  REPLACE (nhk16/shinmeikai8 reading-hit): {dict_any} ({p(dict_any)})  [nhk16 {nhk}, shinmeikai8 {shin}]")
    print(f"  leave: forvo-only {forvo_only} ({p(forvo_only)}), no-source {none_} ({p(none_)})")
    print(f"  (empty Reading among audio'd: {no_read})")


if __name__ == "__main__":
    main()
