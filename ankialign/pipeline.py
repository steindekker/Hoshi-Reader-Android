"""Headless post-sync enrichment pipeline (no aqt).

Runs the full non-destructive maintenance suite against a collection, in order:
word audio -> pitch graphs -> definitions -> query-link fix -> cloze backfill.
Definitions run before the query-link fix because the fix cleans up the anchors
that enrichment writes. Dedupe is intentionally NOT here — it is destructive and
lives in its own command (`ankialign.dedupe`).

Dry-run unless apply=True; a verified .colpkg backup is taken before any write.
"""
import os
import sqlite3

from . import audio, cloze, config, convert, enrich, pitch, query_links

_ROOT = os.path.dirname(os.path.dirname(__file__))
LIVE = os.path.expanduser("~/.local/share/Anki2/User 1/collection.anki2")
ADDONS = os.path.expanduser("~/.local/share/Anki2/addons21")
DICTS_DIR = os.path.join(_ROOT, "research", "dict-compare", "dicts")


def _open_ro(db_path: str):
    return sqlite3.connect(f"file:{db_path}?mode=ro", uri=True)


def ensure_def_index() -> str:
    """Build defs.sqlite if missing. Returns 'present' | 'built' | 'no-source'."""
    if os.path.exists(config.DEF_INDEX_PATH):
        return "present"
    if not os.path.isdir(DICTS_DIR):
        return "no-source"
    import sys
    sys.path.insert(0, os.path.join(_ROOT, "tools"))
    import build_def_index  # noqa: E402  (tools/ script, not a package module)
    build_def_index.build(DICTS_DIR, config.DEF_INDEX_PATH, config.DICT_STYLES_PATH)
    return "built"


def _step_audio(col, apply):
    located = audio.locate(ADDONS)
    if located is None:
        return {"skipped": "local-audio-yomichan entries.db not found"}
    db_path, ufd = located
    conn = _open_ro(db_path)
    try:
        if apply:
            res = audio.run_backfill(col, conn, ufd)
            return {"filled": dict(res["filled"]), "errors": len(res.get("errors", []))}
        plan = audio.plan_backfill(col, conn, ufd)
        return {"will_fill": plan["filled"], "by_source": dict(plan["by_source"]),
                "none": plan["nothing"], "missing_file": plan["missing_file"]}
    finally:
        conn.close()


def _step_sentences(col, apply, overwrite):
    """JRP sentence-markup fill. Splice (fill plain runs) by default; overwrite
    rebuilds every Sentence from its plain surface (regenerate). Apply runs to a
    fixpoint: one splice pass isn't stable (generate can emit a bare kanji that
    splice re-fills next pass), so a single pass would leave residue behind."""
    from .jrp_fill import engine, make_generator
    gen = make_generator()
    if apply:
        n = engine.apply_to_fixpoint(col, config.TARGET_NOTETYPE, "Sentence", gen,
                                     regenerate=overwrite)
        return {"filled": n}
    plan = engine.build_plan(col, config.TARGET_NOTETYPE, "Sentence", gen,
                             regenerate=overwrite)
    return {"filled": len(plan)}


def _step_enrich(col, apply, def_index, overwrite):
    if def_index == "no-source":
        return {"skipped": "defs.sqlite missing and no dict source to build it"}
    conn = enrich.open_index()
    try:
        if apply:
            return enrich.enrich_notes(col, conn, overwrite=overwrite)
        plan = enrich.build_plan(col, conn, overwrite=overwrite)
        return {"to_write": plan["to_write"], **plan["counts"]}
    finally:
        conn.close()


def run(col, *, apply: bool = False, overwrite_defs: bool = False,
        overwrite_pitch: bool = False, overwrite_cloze: bool = False,
        overwrite_sentences: bool = False) -> dict:
    """Run the full suite on an open collection. Returns an ordered results dict.

    The overwrite_* flags re-assert the computed value (definitions / pitch graphs
    / JRP sentence markup / sentence-cloze splits) on every in-scope note,
    clobbering manual edits; by default only empty/plain fields are written.
    """
    def_index = ensure_def_index()
    scanned = len(col.find_notes(f'note:"{config.TARGET_NOTETYPE}"'))
    sources = {
        "dicts": " → ".join(config.DICT_LABELS[d]
                            for d in (*config.JJ_CHAIN, config.JE_DICT)),
        "audio": " → ".join(audio.SOURCE_LABELS[s] for s in audio.FILL_ORDER),
        "pitch": " → ".join(pitch.SOURCES),
    }
    out = {"def_index": def_index, "overwrite_defs": overwrite_defs,
           "overwrite_pitch": overwrite_pitch, "overwrite_cloze": overwrite_cloze,
           "overwrite_sentences": overwrite_sentences,
           "sources": sources, "scanned": scanned, "backup": None, "steps": {}}

    if apply:
        backup_dir = os.path.join(os.path.dirname(col.path), "backups")
        convert.backup_collection(col, backup_dir)   # verified .colpkg or raises
        out["backup"] = backup_dir

    out["steps"]["audio"] = _step_audio(col, apply)
    out["steps"]["pitch"] = (pitch.backfill_pitch(col, overwrite=overwrite_pitch) if apply
                             else pitch.build_pitch_plan(col, overwrite=overwrite_pitch)["counts"])
    out["steps"]["sentences"] = _step_sentences(col, apply, overwrite_sentences)
    out["steps"]["enrich"] = _step_enrich(col, apply, def_index, overwrite_defs)
    out["steps"]["query_links"] = query_links.run(col, apply=apply)
    out["steps"]["cloze"] = cloze.run(col, apply=apply, overwrite=overwrite_cloze)
    return out
