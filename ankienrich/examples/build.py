"""Build the example-sentence index (examples.sqlite) from owned Yomitan dicts
(jitendex / saito / yourei) plus the Tatoeba dump (dicts/tatoeba/)."""
import json
import re
import sqlite3
from pathlib import Path

from ankienrich import config, paths
from ankienrich.dbutil import term_banks as _banks
from ankienrich.examples import parse


def _schema(conn):
    conn.executescript("""
        DROP TABLE IF EXISTS examples;
        CREATE TABLE examples (
            term TEXT NOT NULL, reading TEXT NOT NULL, dict_id TEXT NOT NULL,
            ord INTEGER NOT NULL, jp TEXT NOT NULL, en TEXT NOT NULL, plain TEXT NOT NULL);
    """)


_EXTRACT = {
    "jitendex": lambda e: parse.jitendex(e[5]),
    "saito": lambda e: parse.saito(e[5], e[0]),
    "yourei": lambda e: parse.yourei(e[5]),
}


def _index_dict(conn, dicts_dir, dict_id) -> int:
    extract = _EXTRACT[dict_id]
    n = 0
    for bank in _banks(dicts_dir, dict_id):
        rows = []
        for e in json.loads(bank.read_text(encoding="utf-8")):
            term, reading = e[0], (e[1] or "")
            for i, ex in enumerate(extract(e)[:config.EXAMPLE_BUILD_CAP]):
                rows.append((term, reading, dict_id, i, ex["jp"], ex["en"], ex["plain"]))
        conn.executemany("INSERT INTO examples VALUES (?,?,?,?,?,?,?)", rows)
        n += len(rows)
    return n


# Tatoeba jpn_indices token: headword(reading)[sense]{surface}~  (~ = verified)
_TOK = re.compile(r"^([^\s(\[{~]+)(?:\(([^)]*)\))?(?:\[[^\]]*\])?(?:\{([^}]*)\})?~?$")
_KANA = re.compile(r"^[぀-ヿー]+$")


def _load_tsv(path: Path, keep: set | None = None) -> dict:
    """id -> text from a `id<TAB>lang<TAB>text` file; optionally only `keep` ids."""
    out = {}
    with path.open(encoding="utf-8") as fh:
        for line in fh:
            parts = line.rstrip("\n").split("\t")
            if len(parts) >= 3 and (keep is None or parts[0] in keep):
                out[parts[0]] = parts[2]
    return out


def _index_tatoeba(conn, tatoeba_dir) -> int:
    d = Path(tatoeba_dir)
    idx_path = d / "jpn_indices.csv"
    if not idx_path.exists():
        return 0
    # Pass 1: parse the index, collecting referenced sentence ids.
    index_rows, jp_ids, eng_ids = [], set(), set()
    with idx_path.open(encoding="utf-8") as fh:
        for line in fh:
            parts = line.rstrip("\n").split("\t")
            if len(parts) < 3:
                continue
            jp_id, eng_id, tokens = parts[0], parts[1], parts[2]
            index_rows.append((jp_id, eng_id, tokens))
            jp_ids.add(jp_id)
            if eng_id.isdigit():
                eng_ids.add(eng_id)
    jp_text = _load_tsv(d / "jpn_sentences.tsv", jp_ids)
    en_text = _load_tsv(d / "eng_sentences.tsv", eng_ids)

    buckets: dict[tuple, list] = {}
    for jp_id, eng_id, tokens in index_rows:
        jp = jp_text.get(jp_id)
        if not jp:
            continue
        en = en_text.get(eng_id, "")
        seen_here = set()
        for tok in tokens.split(" "):
            m = _TOK.match(tok)
            if not m:
                continue
            hw, reading, surface = m.group(1), m.group(2) or "", m.group(3) or ""
            if reading == "" and _KANA.match(hw):
                reading = hw
            key = (hw, reading)
            if key in seen_here:
                continue  # one headword may repeat in a sentence; index it once
            seen_here.add(key)
            buf = buckets.setdefault(key, [])
            if len(buf) >= config.EXAMPLE_BUILD_CAP:
                continue
            kw = surface or hw
            buf.append((parse.highlight(jp, kw), en, jp))

    rows = []
    for (hw, reading), exs in buckets.items():
        for i, (jp_html, en, plain) in enumerate(exs):
            rows.append((hw, reading, "tatoeba", i, jp_html, en, plain))
    conn.executemany("INSERT INTO examples VALUES (?,?,?,?,?,?,?)", rows)
    return len(rows)


def build(dicts_dir, db_path, tatoeba_dir) -> dict:
    paths.ensure_var()
    Path(db_path).parent.mkdir(parents=True, exist_ok=True)
    conn = sqlite3.connect(db_path)
    _schema(conn)
    counts = {}
    for dict_id in ("jitendex", "saito", "yourei"):
        if _banks(dicts_dir, dict_id):
            counts[dict_id] = _index_dict(conn, dicts_dir, dict_id)
    counts["tatoeba"] = _index_tatoeba(conn, tatoeba_dir)
    conn.execute("CREATE INDEX examples_key ON examples (term, reading)")
    conn.commit()
    conn.close()
    return counts


def ensure(dicts_dir=None, db_path=None, tatoeba_dir=None) -> str:
    """'present' if the index exists, 'built' after building, 'no-source' if no dicts."""
    dicts_dir = Path(dicts_dir or paths.DICTS_DIR)
    db_path = db_path or paths.EXAMPLES_DB
    tatoeba_dir = tatoeba_dir or paths.TATOEBA_DIR
    if Path(db_path).exists():
        return "present"
    has_dict = any(_banks(dicts_dir, d) for d in ("jitendex", "saito", "yourei"))
    has_tat = (Path(tatoeba_dir) / "jpn_indices.csv").exists()
    if not has_dict and not has_tat:
        return "no-source"
    build(dicts_dir, db_path, tatoeba_dir)
    return "built"
