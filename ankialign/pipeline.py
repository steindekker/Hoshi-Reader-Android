"""Headless enrichment suite. Order: audio -> pitch -> sentences -> definitions
-> cloze. Dedupe is destructive and lives in its own command, not here.

Dry-run unless apply=True; a verified backup is taken before any write."""
import os

from . import audio, cloze, config, collection, pitch
from .defs import build as def_build, enrich


def _step_audio(col, apply):
    from . import paths
    addons = os.environ.get("ANKI_ADDONS_DIR", os.path.expanduser(
        "~/.local/share/Anki2/addons21"))
    located = audio.locate(addons)
    if located is None:
        return {"skipped": "local-audio-yomichan entries.db not found"}
    db_path, ufd = located
    import sqlite3
    conn = sqlite3.connect(f"file:{db_path}?mode=ro", uri=True)
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
    from .jrp_fill import engine, make_generator
    gen = make_generator()
    if apply:
        n = engine.apply_to_fixpoint(col, config.NOTETYPE, config.SENTENCE, gen,
                                     regenerate=overwrite)
        return {"filled": n}
    plan = engine.build_plan(col, config.NOTETYPE, config.SENTENCE, gen,
                             regenerate=overwrite)
    return {"filled": len(plan)}


def _step_enrich(col, apply, def_index, overwrite):
    if def_index == "no-source":
        return {"skipped": "defs.sqlite missing and no dict source to build it"}
    from .defs import index
    conn = index.open_index()
    try:
        if apply:
            return enrich.enrich_notes(col, conn, overwrite=overwrite)
        plan = enrich.build_plan(col, conn, overwrite=overwrite)
        return {"to_write": plan["to_write"], **plan["counts"]}
    finally:
        conn.close()


def run(col, *, apply=False, overwrite_pitch=False, overwrite_cloze=False,
        overwrite_sentences=False, overwrite_defs=False) -> dict:
    def_index = def_build.ensure()
    scanned = len(col.find_notes(f'note:"{config.NOTETYPE}"'))
    out = {"def_index": def_index, "scanned": scanned, "backup": None, "steps": {}}

    if apply:
        out["backup"] = collection.backup(col)

    out["steps"]["audio"] = _step_audio(col, apply)
    out["steps"]["pitch"] = (pitch.backfill_pitch(col, overwrite=overwrite_pitch) if apply
                             else pitch.build_pitch_plan(col, overwrite=overwrite_pitch)["counts"])
    out["steps"]["sentences"] = _step_sentences(col, apply, overwrite_sentences)
    out["steps"]["enrich"] = _step_enrich(col, apply, def_index, overwrite_defs)
    out["steps"]["cloze"] = cloze.run(col, apply=apply, overwrite=overwrite_cloze)
    return out
