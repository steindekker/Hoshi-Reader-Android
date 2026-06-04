#!/usr/bin/env python3
"""Minimal read-only parser for an Anki collection.anki2 (schema v18).

Registers the `unicase` collation Anki uses so the sqlite3 CLI's
"no query solution" failures don't happen. Read-only: opens immutable.
"""
import sqlite3
import sys
import re
import signal

signal.signal(signal.SIGPIPE, signal.SIG_DFL)  # clean exit when piped to head/etc.

DB = "collection.anki2"
FS = "\x1f"  # Anki field separator inside notes.flds


def connect():
    con = sqlite3.connect(f"file:{DB}?immutable=1", uri=True)
    # Anki's unicase ~= case-insensitive unicode compare; casefold is close enough.
    con.create_collation("unicase", lambda a, b: (a.casefold() > b.casefold()) - (a.casefold() < b.casefold()))
    return con


def notetypes(con):
    return con.execute(
        """SELECT nt.id, nt.name, COUNT(DISTINCT n.id)
           FROM notetypes nt LEFT JOIN notes n ON n.mid = nt.id
           GROUP BY nt.id ORDER BY 3 DESC"""
    ).fetchall()


def fields(con, ntid):
    return con.execute(
        "SELECT ord, name FROM fields WHERE ntid=? ORDER BY ord", (ntid,)
    ).fetchall()


def field_names(con, ntid):
    return [name for _, name in fields(con, ntid)]


def sample_notes(con, ntid, n=5):
    rows = con.execute(
        "SELECT id, flds FROM notes WHERE mid=? LIMIT ?", (ntid, n)
    ).fetchall()
    return [(nid, flds.split(FS)) for nid, flds in rows]


def main():
    con = connect()
    cmd = sys.argv[1] if len(sys.argv) > 1 else "types"

    if cmd == "types":
        for nid, name, cnt in notetypes(con):
            print(f"{nid}\t{cnt:>6}  {name}")

    elif cmd == "fields":
        ntid = int(sys.argv[2])
        for ord_, name in fields(con, ntid):
            print(f"{ord_}: {name}")

    elif cmd == "sample":
        ntid = int(sys.argv[2])
        n = int(sys.argv[3]) if len(sys.argv) > 3 else 3
        names = field_names(con, ntid)
        for nid, vals in sample_notes(con, ntid, n):
            print(f"--- note {nid} ---")
            for name, val in zip(names, vals):
                print(f"  [{name}] {val}")

    elif cmd == "grep":
        # grep <ntid> <regex>: show field values matching a pattern across notes
        ntid = int(sys.argv[2])
        pat = re.compile(sys.argv[3])
        names = field_names(con, ntid)
        hits = 0
        for nid, flds in con.execute("SELECT id, flds FROM notes WHERE mid=?", (ntid,)):
            for name, val in zip(names, flds.split(FS)):
                if pat.search(val):
                    print(f"{nid} [{name}] {val[:200]}")
                    hits += 1
                    break
        print(f"# {hits} matching notes")

    else:
        print(__doc__)


if __name__ == "__main__":
    main()
