#!/usr/bin/env python3
"""Read-only coverage spike for the term-audio backfill (goal #3).

Measures, over in-scope jidoujisho Kinomoto cards with empty Term Audio, how the
decided lookup chain (nhk16 -> shinmeikai8 -> forvo[strawberrybrown] -> jpod)
covers them. Opens collection.anki2 in SQLite immutable mode so it is safe to run
while Anki is open (no writes, ignores the lock).

Run: python3 coverage_spike.py   (writes COVERAGE.md next to this file)
"""
import re
import sqlite3
from collections import Counter
from pathlib import Path

COL = Path.home() / ".local/share/Anki2/User 1/collection.anki2"
DB = Path.home() / ".local/share/Anki2/addons21/1045800357/user_files/entries.db"
OUT = Path(__file__).parent / "COVERAGE.md"

EXCLUDE_DECK_SUBSTRINGS = ["Core2.3k", "文法"]  # mirrors ankialign/config.py
FS = "\x1f"  # Anki field separator
TAG_RE = re.compile(r"<[^>]+>")
KATA_TO_HIRA = {cp: cp - 0x60 for cp in range(0x30A1, 0x30F7)}  # katakana -> hiragana


def ro(path):
    uri = f"file:{path}?immutable=1"
    return sqlite3.connect(uri, uri=True)


def clean(s):
    s = TAG_RE.sub("", s or "")
    s = s.replace("&nbsp;", "").replace("​", "")
    return s.strip()


def to_hira(s):
    return s.translate(KATA_TO_HIRA)


def main():
    col = ro(COL)
    # field ords for the Kinomoto notetype
    ntid = col.execute(
        "SELECT id FROM notetypes WHERE name LIKE '%Kinomoto%'"
    ).fetchone()[0]
    ords = {name: ord_ for ord_, name in col.execute(
        "SELECT ord, name FROM fields WHERE ntid=?", (ntid,))}
    i_term, i_read, i_audio = ords["Term"], ords["Reading"], ords["Term Audio"]

    # decks containing each note's cards -> exclusion set
    deck_name = dict(col.execute("SELECT id, name FROM decks"))
    excluded_nids = set()
    for nid, did in col.execute("SELECT nid, did FROM cards"):
        nm = deck_name.get(did, "")
        if any(sub in nm for sub in EXCLUDE_DECK_SUBSTRINGS):
            excluded_nids.add(nid)

    rows = col.execute("SELECT id, flds FROM notes WHERE mid=?", (ntid,)).fetchall()
    col.close()

    db = ro(DB)

    def q(sql, *a):
        return db.execute(sql, a).fetchall()

    total_kino = len(rows)
    in_scope = empty_audio = empty_reading = 0
    win = Counter()                 # winning source per card
    forvo_speaker = Counter()       # strawberrybrown vs other, among forvo wins
    # counterfactuals to sanity-check the priority decision:
    forvo_win_had_jpod = 0          # forvo won but jpod also had an exact reading hit
    nothing_samples, forvo_samples = [], []
    nothing_bucket = Counter()

    for nid, flds in rows:
        if nid in excluded_nids:
            continue
        in_scope += 1
        f = flds.split(FS)
        if clean(f[i_audio]):
            continue
        empty_audio += 1
        term = clean(f[i_term])
        reading = to_hira(clean(f[i_read]))
        if not reading:
            empty_reading += 1

        nhk = q("SELECT 1 FROM entries WHERE expression=? AND reading=? AND source='nhk16' LIMIT 1", term, reading) if reading else []
        shin = q("SELECT 1 FROM entries WHERE expression=? AND reading=? AND source='shinmeikai8' LIMIT 1", term, reading) if reading else []
        forvo = q("SELECT speaker FROM entries WHERE expression=? AND source='forvo'", term)
        jpod = q("SELECT 1 FROM entries WHERE expression=? AND reading=? AND source='jpod' LIMIT 1", term, reading) if reading else []

        if nhk:
            win["nhk16"] += 1
        elif shin:
            win["shinmeikai8"] += 1
        elif forvo:
            win["forvo"] += 1
            spks = {s for (s,) in forvo}
            forvo_speaker["strawberrybrown" if "strawberrybrown" in spks else "other"] += 1
            if jpod:
                forvo_win_had_jpod += 1
            if len(forvo_samples) < 15:
                forvo_samples.append((term, reading or "—", "strawberrybrown" if "strawberrybrown" in spks else sorted(spks)[0]))
        elif jpod:
            win["jpod"] += 1
        else:
            win["nothing"] += 1
            # bucket the misses
            if not reading:
                nothing_bucket["empty reading"] += 1
            elif q("SELECT 1 FROM entries WHERE expression=? LIMIT 1", term):
                nothing_bucket["term exists, reading mismatch"] += 1
            else:
                nothing_bucket["term absent entirely"] += 1
            if len(nothing_samples) < 25:
                nothing_samples.append((term, reading or "—"))

    db.close()

    filled = empty_audio - win["nothing"]
    def pct(n, d):
        return f"{100*n/d:.1f}%" if d else "—"

    L = []
    L.append("# Coverage spike: term-audio backfill (goal #3)\n")
    L.append(f"Date: 2026-06-04. Read-only over `collection.anki2` (schema 18) + `entries.db` (590,410 rows).")
    L.append(f"Lookup chain: **nhk16 → shinmeikai8 → forvo[strawberrybrown] → jpod**, stop at first hit.\n")
    L.append("## Population\n")
    L.append(f"- jidoujisho Kinomoto notes (total): **{total_kino}**")
    L.append(f"- in scope (excl. {'/'.join(EXCLUDE_DECK_SUBSTRINGS)}): **{in_scope}**")
    L.append(f"- of those, **empty `Term Audio`** (= backfill target N): **{empty_audio}** ({pct(empty_audio,in_scope)} of in-scope)")
    L.append(f"- target cards with **empty `Reading`**: **{empty_reading}** ({pct(empty_reading,empty_audio)} of target)\n")
    L.append("## Coverage of the target N\n")
    L.append("| Outcome (winning source) | Cards | % of target |")
    L.append("|---|---:|---:|")
    for k in ["nhk16", "shinmeikai8", "forvo", "jpod", "nothing"]:
        L.append(f"| {k} | {win[k]} | {pct(win[k], empty_audio)} |")
    L.append(f"| **filled (any source)** | **{filled}** | **{pct(filled, empty_audio)}** |\n")
    L.append("## Forvo tail detail\n")
    L.append(f"- forvo wins with a **strawberrybrown** clip: **{forvo_speaker['strawberrybrown']}** "
             f"(other speakers only: {forvo_speaker['other']})")
    L.append(f"- forvo wins where **jpod also had an exact (term,reading) hit**: **{forvo_win_had_jpod}** "
             f"— these are the cards where the forvo>jpod decision actually bites (homograph risk vs jpod's reading-safety).")
    if forvo_samples:
        L.append("\nSample forvo-tail picks (term / reading / speaker):\n")
        for t, r, s in forvo_samples:
            L.append(f"- {t} ／ {r} ／ {s}")
    L.append("\n## Residual misses ({} cards)\n".format(win["nothing"]))
    L.append("| Bucket | Cards |")
    L.append("|---|---:|")
    for k, v in nothing_bucket.most_common():
        L.append(f"| {k} | {v} |")
    if nothing_samples:
        L.append("\nSample misses (term / reading):\n")
        for t, r in nothing_samples:
            L.append(f"- {t} ／ {r}")
    L.append("")
    OUT.write_text("\n".join(L), encoding="utf-8")
    # console summary
    print(f"target N (empty Term Audio, in-scope) = {empty_audio}")
    print(f"filled = {filled} ({pct(filled, empty_audio)}) | nothing = {win['nothing']}")
    print("by source:", dict(win))
    print("forvo speaker split:", dict(forvo_speaker), "| forvo-over-jpod conflicts:", forvo_win_had_jpod)
    print("empty reading:", empty_reading)
    print(f"wrote {OUT}")


if __name__ == "__main__":
    main()
