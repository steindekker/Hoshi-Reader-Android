"""Merge duplicate jidoujisho Kinomoto notes (same Term+Reading).

Within each duplicate group KEEP the card whose next review is soonest (smallest
day-based `due`; suspended/new ranked last), MERGE the others' review stats onto
it (their revlog rows are reassigned to the kept card's id, and their reps/lapses
are summed in), then DELETE the duplicate notes. The kept card's own schedule
(due / ivl / factor) is left untouched.

Destructive. Revlog reassignment is a raw edit; the tool re-uploads via
`sync.push` after dedupe.
"""
from __future__ import annotations

import collections
import time

from . import config

SEP = "\x1f"


def _due_in_days(c: dict, crt: int) -> int:
    q = c["queue"]
    if q < 0:
        return 10 ** 9                      # suspended / buried: never "soon"
    if q in (2, 3):                          # review / day-learning: due is a day
        return c["due"]
    if q == 1:                               # learning: due is epoch seconds
        return (c["due"] - crt) // 86400
    return 10 ** 9                           # new (queue 0): no scheduled review


def _sort_key(c: dict, crt: int):
    # soonest review first; tie-break toward more progress (reps, then ivl)
    return (_due_in_days(c, crt), -c["reps"], -c["ivl"], c["cid"])


def plan(col) -> list:
    """Return [(key, keep_card, [drop_cards])] for every duplicate group."""
    m = col.models.by_name(config.NOTETYPE)
    if m is None:
        return []
    crt = col.db.scalar("select crt from col")
    mid = m["id"]
    names = [f["name"] for f in m["flds"]]
    ix = {n: i for i, n in enumerate(names)}
    ti = ix[config.TERM]
    ri = ix.get(config.READING, ti)

    groups = collections.defaultdict(list)
    for nnid, flds in col.db.execute("select id, flds from notes where mid=?", mid):
        f = flds.split(SEP)
        key = (f[ti].strip(), f[ri].strip() if len(f) > ri else "")
        if key[0]:
            groups[key].append(nnid)
    dups = {k: v for k, v in groups.items() if len(v) > 1}

    cards = collections.defaultdict(list)
    for cid, n2, q, due, ivl, reps, lapses in col.db.execute(
        "select c.id,c.nid,c.queue,c.due,c.ivl,c.reps,c.lapses "
        "from cards c join notes n on c.nid=n.id where n.mid=?", mid):
        cards[n2].append(dict(cid=cid, nid=n2, queue=q, due=due,
                              ivl=ivl, reps=reps, lapses=lapses))

    merges = []
    for key, members in sorted(dups.items()):
        cs = []
        for nn in members:
            lst = cards.get(nn, [])
            # Kinomoto is a single-template notetype, so one card per note; the
            # merge picks the surviving card by scheduling, which assumes this.
            assert len(lst) == 1, f"note {nn} has {len(lst)} cards (expected 1)"
            cs.append(lst[0])
        cs.sort(key=lambda c: _sort_key(c, crt))
        merges.append((key, cs[0], cs[1:]))
    return merges


def run(col, *, apply: bool = False) -> dict:
    """Merge + delete duplicates. Returns {'groups','removed','revlog_moved','merges'}."""
    merges = plan(col)
    del_notes = [d["nid"] for _, _, drop in merges for d in drop]
    m = col.models.by_name(config.NOTETYPE)
    scanned = col.db.scalar("select count(*) from notes where mid=?", m["id"]) if m else 0
    out = {"scanned": scanned, "groups": len(merges), "removed": len(del_notes),
           "revlog_moved": 0, "merges": merges}
    if not apply or not del_notes:
        return out

    moved = 0
    for _, keep, drop in merges:
        add_reps = sum(d["reps"] for d in drop)
        add_lap = sum(d["lapses"] for d in drop)
        for d in drop:
            moved += col.db.scalar("select count(*) from revlog where cid=?", d["cid"]) or 0
            col.db.execute("update revlog set cid=?, usn=-1 where cid=?", keep["cid"], d["cid"])
        col.db.execute(
            "update cards set reps=reps+?, lapses=lapses+?, usn=-1, mod=? where id=?",
            add_reps, add_lap, int(time.time()), keep["cid"])
    col.save()
    col.remove_notes(del_notes)
    col.save()
    out["revlog_moved"] = moved
    return out
