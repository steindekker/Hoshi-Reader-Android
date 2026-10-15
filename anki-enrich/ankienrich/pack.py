"""Hoshi enrichment pack: every Kinomoto field this tool derives from (term, reading)
alone, precomputed per dictionary headword. The Hoshi app downloads the pack and
fills a card offline at mining time; the nightly fields pass (fill.py) fills the
collection from the same pack by the same lookup, so the two never disagree.

Format (FORMAT 1) — one SQLite file:

  meta(key TEXT PRIMARY KEY, value BLOB)
    format         "1"; the app refuses a format it does not know
    version        content hash: unchanged content keeps the version, so the app
                   only re-downloads when a value actually changed
    built_at       ISO-8601 UTC, informational (not part of the version)
    entries        row count
    zdict          zlib preset dictionary shared by every row
    defaults       JSON {key: value} for a term with no row. A value may itself be
                   a Hoshi handlebar, which the app renders as usual.
    fallback_keys  JSON [key] still valid when the term is known but not under the
                   requested reading (reading-dependent keys like the pitch graph
                   are left out)
    keys           JSON [key] every key some row carries, in display order; the app
                   offers these in its handlebar picker (not part of the version)
  entries(term TEXT, reading TEXT, data BLOB), indexed on term
    data = zlib(JSON {key: value}) with zdict; empty values are omitted.

Keys are the app's handlebar names without braces: `ae-definition` fills
`{ae-definition}`. A key added here reaches the app with no app change.

Lookup contract (implemented by `lookup` below and by the app): the
(term, reading) row gives all its values; else the term's first row gives its
fallback_keys; else defaults. A reading-less dictionary entry is keyed under its
headword, matching what Hoshi reports as the reading.

Manifest (MANIFEST_FILE, JSON): format, version, file, size, sha256, built_at,
entries describe the pack; an optional "sentence" object lists the separately
versioned sentence model for `{ae-sentence}` (see `sentence_model.py`). Clients
ignore keys they do not know, so adding one never breaks an older app."""
from __future__ import annotations

import hashlib
import json
import os
import random
import sqlite3
import zlib
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path

from . import config, paths, pitch, pitch_svg, sentence_model
from .defs import enrich as defs_enrich, index as defs_index
from .jrp_fill.accent import AccentIndex

FORMAT = 1
PACK_FILE = "hoshi-pack.sqlite"
MANIFEST_FILE = "pack.json"

DEFINITION = "ae-definition"
DEFINITION_SOURCE = "ae-definition-source"
PITCH = "ae-pitch"
EXAMPLES = "ae-examples"
FREQUENCY = "ae-frequency"
TAGS = "ae-tags"

# A term the definition chain does not know: keep Hoshi's own first glossary, as
# the nightly pass leaves the mined Meaning alone on a no-hit.
DEFAULTS = {DEFINITION: "{glossary-first}"}
FALLBACK_KEYS = [DEFINITION, DEFINITION_SOURCE, EXAMPLES, TAGS]
KEY_ORDER = [DEFINITION, DEFINITION_SOURCE, PITCH, EXAMPLES, FREQUENCY, TAGS]

_ZDICT_SIZE = 32 * 1024
_ZDICT_SAMPLE_EVERY = 50
_ZDICT_CHUNK = 64


class Sources:
    """Open read connections to the indexes the passes use. Examples and frequency
    are optional, as in the pipeline: a missing index leaves its keys out."""

    def __init__(self, defs_db=None, examples_db=None, freq_db=None, accent: AccentIndex | None = None):
        defs_db = defs_db or paths.DEFS_DB
        if not Path(defs_db).exists():
            raise FileNotFoundError(f"{defs_db} missing — build the definition index first")
        self.defs = defs_index.open_index(str(defs_db))
        examples_db = examples_db or paths.EXAMPLES_DB
        freq_db = freq_db or paths.FREQ_DB
        self.examples = _open_optional(examples_db, "ankienrich.examples.index")
        self.freq = _open_optional(freq_db, "ankienrich.freq.index")
        self.accent = accent or AccentIndex.load()

    def close(self) -> None:
        for conn in (self.defs, self.examples, self.freq):
            if conn is not None:
                conn.close()


def _open_optional(path, module):
    if not Path(path).exists():
        return None
    import importlib
    return importlib.import_module(module).open_index(str(path))


def fields_for(src: Sources, term: str, reading: str) -> dict[str, str]:
    """Every pack value for one (term, reading) headword."""
    meaning, source, kind = defs_enrich.definition_for(src.defs, term, reading)
    if kind == "nohit":
        return {}
    out = {DEFINITION: meaning, DEFINITION_SOURCE: source}
    tags = [config.ENRICHED_TAG]
    accents = pitch.accents_for(src.accent, term, reading) if reading else None
    if accents:
        out[PITCH] = pitch_svg.render_multi(reading, accents)
    if src.examples is not None:
        from .examples import enrich as ex_enrich
        examples = ex_enrich.examples_for(src.examples, term, reading)
        if examples:
            out[EXAMPLES] = examples
        tags.append(config.EXAMPLES_TAG if examples else config.NO_EXAMPLE_TAG)
    if src.freq is not None:
        from .freq import index as freq_index
        hit = freq_index.lookup(src.freq, term, reading)
        if hit:
            out[FREQUENCY] = str(hit[0])
    out[TAGS] = " ".join(tags)
    return {k: v for k, v in out.items() if v}


def _pairs(src: Sources) -> list[tuple[str, str]]:
    """Distinct (term, reading) headwords in definition-chain order. A reading-less
    entry is keyed under its headword (what Hoshi reports), merged with any explicit
    entry of the same reading."""
    seen, out = set(), []
    for term, reading in src.defs.execute("SELECT term, reading FROM defs ORDER BY rowid"):
        key = (term, reading or term)
        if key not in seen:
            seen.add(key)
            out.append(key)
    return out


def _encode(values: dict[str, str]) -> bytes:
    return json.dumps(values, ensure_ascii=False, separators=(",", ":")).encode("utf-8")


def _compress(raw: bytes, zdict: bytes) -> bytes:
    c = zlib.compressobj(9, zlib.DEFLATED, 15, 9, zlib.Z_DEFAULT_STRATEGY, zdict)
    return c.compress(raw) + c.flush()


def decompress(blob: bytes, zdict: bytes) -> dict[str, str]:
    d = zlib.decompressobj(15, zdict)
    return json.loads(d.decompress(blob) + d.flush())


def train_zdict(samples: list[bytes], size: int = _ZDICT_SIZE) -> bytes:
    """Concatenate the most repeated fixed-size chunks (card HTML boilerplate,
    dictionary markup) with the most common last, where zlib reaches it cheapest."""
    counts = Counter()
    for raw in samples:
        for i in range(0, max(len(raw) - _ZDICT_CHUNK, 0), _ZDICT_CHUNK // 4):
            counts[raw[i:i + _ZDICT_CHUNK]] += 1
    common = [chunk for chunk, n in counts.most_common() if n > 3]
    picked, total = [], 0
    for chunk in common:
        if total + len(chunk) > size:
            break
        picked.append(chunk)
        total += len(chunk)
    return b"".join(reversed(picked))


def build(out_dir=None, src: Sources | None = None, progress=None, dictionary=None) -> dict:
    """Write PACK_FILE + MANIFEST_FILE into out_dir (default var/pack), plus the
    sentence model (`sentence_model.py`; `dictionary` overrides the installed
    sudachidict_full). Returns the manifest. Files are written beside the old ones
    and swapped in atomically."""
    out = Path(out_dir or Path(paths.VAR_DIR, "pack"))
    out.mkdir(parents=True, exist_ok=True)
    own = src is None
    src = src or Sources()
    try:
        pairs = _pairs(src)
        rng = random.Random(1)
        sample = [p for p in pairs if rng.randrange(_ZDICT_SAMPLE_EVERY) == 0] or pairs[:200]
        zdict = train_zdict([_encode(fields_for(src, t, r)) for t, r in sample])

        tmp = out / (PACK_FILE + ".tmp")
        tmp.unlink(missing_ok=True)
        conn = sqlite3.connect(tmp)
        conn.executescript("""
            PRAGMA journal_mode = OFF;
            PRAGMA page_size = 4096;
            CREATE TABLE meta (key TEXT PRIMARY KEY, value BLOB NOT NULL);
            CREATE TABLE entries (term TEXT NOT NULL, reading TEXT NOT NULL, data BLOB NOT NULL);
        """)
        digest = hashlib.sha256()
        meta = {
            "format": str(FORMAT),
            "zdict": zdict,
            "defaults": json.dumps(DEFAULTS, ensure_ascii=False),
            "fallback_keys": json.dumps(FALLBACK_KEYS),
        }
        for key in ("format", "zdict", "defaults", "fallback_keys"):
            value = meta[key]
            digest.update(value if isinstance(value, bytes) else value.encode("utf-8"))
        n, batch, seen_keys = 0, [], set()
        for i, (term, reading) in enumerate(pairs):
            values = fields_for(src, term, reading)
            if not values:
                continue
            seen_keys.update(values)
            blob = _compress(_encode(values), zdict)
            for part in (term, reading):
                digest.update(part.encode("utf-8") + b"\0")
            digest.update(blob)
            batch.append((term, reading, blob))
            n += 1
            if len(batch) >= 5000:
                conn.executemany("INSERT INTO entries VALUES (?,?,?)", batch)
                batch.clear()
                if progress:
                    progress(i + 1, len(pairs))
        conn.executemany("INSERT INTO entries VALUES (?,?,?)", batch)
        conn.execute("CREATE INDEX entries_term ON entries (term)")
        version = digest.hexdigest()[:16]
        built_at = datetime.now(timezone.utc).replace(microsecond=0).isoformat()
        keys = [k for k in KEY_ORDER if k in seen_keys] + sorted(seen_keys - set(KEY_ORDER))
        meta.update(version=version, built_at=built_at, entries=str(n), keys=json.dumps(keys))
        conn.executemany("INSERT INTO meta VALUES (?,?)", list(meta.items()))
        conn.commit()
        conn.close()
        os.replace(tmp, out / PACK_FILE)

        manifest = {
            "format": FORMAT,
            "version": version,
            "file": PACK_FILE,
            "size": (out / PACK_FILE).stat().st_size,
            "sha256": sentence_model.file_sha256(out / PACK_FILE),
            "built_at": built_at,
            "entries": n,
        }
        return _write_manifest(out, manifest, sentence_model.build(out, src.accent, dictionary))
    finally:
        if own:
            src.close()


def _write_manifest(out: Path, manifest: dict, sentence: dict | None) -> dict:
    """The pack's own keys stay format 1 as old apps read them; the sentence model
    rides under "sentence", which they ignore."""
    manifest = {k: v for k, v in manifest.items() if k != "sentence"}
    if sentence is not None:
        manifest["sentence"] = sentence
    man_tmp = out / (MANIFEST_FILE + ".tmp")
    man_tmp.write_text(json.dumps(manifest, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    os.replace(man_tmp, out / MANIFEST_FILE)
    return manifest


def find(conn: sqlite3.Connection, term: str, reading: str) -> dict[str, str] | None:
    """The lookup contract without the defaults: None when the pack does not know
    the term at all (a definition no-hit)."""
    meta = dict(conn.execute("SELECT key, value FROM meta WHERE key IN ('zdict', 'fallback_keys')"))
    row = conn.execute(
        "SELECT reading, data FROM entries WHERE term=? ORDER BY reading=? DESC, rowid LIMIT 1",
        (term, reading)).fetchone()
    if row is None:
        return None
    values = decompress(row[1], meta["zdict"])
    if row[0] == reading:
        return values
    keep = set(json.loads(meta["fallback_keys"]))
    return {k: v for k, v in values.items() if k in keep}


def lookup(conn: sqlite3.Connection, term: str, reading: str) -> dict[str, str]:
    """The app's lookup contract, for tests and inspection."""
    values = find(conn, term, reading)
    if values is None:
        return json.loads(conn.execute("SELECT value FROM meta WHERE key='defaults'").fetchone()[0])
    return values


def pack_path(out_dir=None) -> Path:
    return Path(out_dir or Path(paths.VAR_DIR, "pack")) / PACK_FILE


def ensure(out_dir=None) -> str:
    """'present' when the pack is newer than every source index, 'built' after
    (re)building it, 'no-source' without a definition index. A rebuilt index (a
    new dictionary, say) therefore reaches the next run and the app by itself.
    A present pack still gets its sentence model refreshed (a no-op unless the
    accent index or sudachidict changed, or the model is missing)."""
    if not Path(paths.DEFS_DB).exists():
        return "no-source"
    target = pack_path(out_dir)
    sources = [paths.DEFS_DB, paths.EXAMPLES_DB, paths.FREQ_DB, paths.ACCENT_INDEX]
    newest = max(os.path.getmtime(s) for s in sources if os.path.exists(s))
    manifest_path = target.parent / MANIFEST_FILE
    if target.exists() and target.stat().st_mtime >= newest and manifest_path.exists():
        refresh_sentence_model(target.parent)
        return "present"
    build(out_dir)
    return "built"


def refresh_sentence_model(out_dir, accent: AccentIndex | None = None, dictionary=None) -> dict:
    """Rebuild only the sentence model and re-list it in the existing manifest,
    leaving the pack and its version untouched."""
    out = Path(out_dir)
    manifest = json.loads((out / MANIFEST_FILE).read_text(encoding="utf-8"))
    sentence = sentence_model.build(out, accent, dictionary)
    if manifest.get("sentence") == sentence:
        return manifest
    return _write_manifest(out, manifest, sentence)
