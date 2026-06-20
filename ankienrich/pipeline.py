"""Headless enrichment suite. Order: audio -> pitch -> sentences -> furigana
-> definitions -> frequency -> examples -> cloze. Dedupe is destructive and lives
in its own command, not here.

Dry-run unless apply=True; a verified backup is taken before any write."""
from . import audio, cloze, config, collection, furigana, pitch
from .audio import build as audio_build, index as audio_index
from .defs import build as def_build, enrich
from .examples import build as ex_build
from .freq import build as freq_build


class PipelineError(Exception):
    """A named enrichment pass raised. Carries the pass name for reporting."""

    def __init__(self, step: str, original: BaseException):
        super().__init__(f"{step}: {original}")
        self.step = step
        self.original = original


def _step_audio(col, apply: bool) -> dict:
    state = audio_build.ensure()
    if state == "no-source":
        return {"skipped": "audio.sqlite missing — place the android.db store at var/audio.sqlite"}
    conn = audio_index.open_index()
    try:
        if apply:
            res = audio.run_backfill(col, conn)
            return {"filled": dict(res["filled"]), "errors": len(res.get("errors", []))}
        plan = audio.plan_backfill(col, conn)
        return {"will_fill": plan["filled"], "by_source": dict(plan["by_source"]),
                "none": plan["nothing"], "missing_file": plan["missing_file"]}
    finally:
        conn.close()


def _step_sentences(col, apply: bool, overwrite: bool) -> dict:
    from .jrp_fill import engine, make_generator
    gen = make_generator()
    if apply:
        n = engine.apply_to_fixpoint(col, config.NOTETYPE, config.SENTENCE, gen,
                                     regenerate=overwrite)
        return {"filled": n}
    plan = engine.build_plan(col, config.NOTETYPE, config.SENTENCE, gen,
                             regenerate=overwrite)
    return {"filled": len(plan)}


def _step_furigana(col, apply: bool) -> dict:
    plan = furigana.build_plan(col)
    if apply:
        return {"fixed": furigana.apply_plan(col, plan)}
    return {"fixed": len(plan)}


def _step_enrich(col, apply: bool, def_index: str, overwrite: bool) -> dict:
    if def_index == "no-source":
        return {"skipped": "defs.sqlite missing and no dict source to build it"}
    from .defs import index
    conn = index.open_index()
    try:
        if apply:
            return enrich.enrich_notes(col, conn, overwrite=overwrite)
        plan = enrich.build_plan(col, conn, overwrite=overwrite)
        return {"to_write": plan["to_write"], "terms": plan["terms"], **plan["counts"]}
    finally:
        conn.close()


def _step_frequency(col, apply: bool) -> dict:
    state = freq_build.ensure()
    if state == "no-source":
        return {"skipped": "freq.sqlite missing and no JPDB source (dicts/jpdb) to build it"}
    from .freq import index, enrich as freq_enrich
    conn = index.open_index()
    try:
        if apply:
            return freq_enrich.enrich_notes(col, conn)
        return freq_enrich.build_plan(col, conn)
    finally:
        conn.close()


def _step_examples(col, apply: bool, overwrite: bool) -> dict:
    ex_index = ex_build.ensure()
    if ex_index == "no-source":
        return {"skipped": "examples.sqlite missing and no dict/Tatoeba source to build it"}
    from .examples import index, enrich as ex_enrich
    conn = index.open_index()
    try:
        if apply:
            return ex_enrich.enrich_notes(col, conn, overwrite=overwrite)
        plan = ex_enrich.build_plan(col, conn, overwrite=overwrite)
        return {"to_write": plan["to_write"], **plan["counts"]}
    finally:
        conn.close()


def run(col, *, apply=False, overwrite_pitch=False, overwrite_cloze=False,
        overwrite_sentences=False, overwrite_defs=False, overwrite_examples=False) -> dict:
    def_index = def_build.ensure()
    scanned = len(col.find_notes(collection.notetype_search()))
    out = {"def_index": def_index, "scanned": scanned, "backup": None, "steps": {}}

    if apply:
        out["backup"] = collection.backup(col)

    steps = [
        ("audio", "audio", lambda: _step_audio(col, apply)),
        ("pitch", "pitch", lambda: (
            pitch.backfill_pitch(col, overwrite=overwrite_pitch) if apply
            else pitch.build_pitch_plan(col, overwrite=overwrite_pitch)["counts"])),
        ("sentences", "sentence", lambda: _step_sentences(col, apply, overwrite_sentences)),
        ("furigana", "furigana", lambda: _step_furigana(col, apply)),
        ("enrich", "defs", lambda: _step_enrich(col, apply, def_index, overwrite_defs)),
        ("frequency", "frequency", lambda: _step_frequency(col, apply)),
        ("examples", "examples", lambda: _step_examples(col, apply, overwrite_examples)),
        ("cloze", "cloze", lambda: cloze.run(col, apply=apply, overwrite=overwrite_cloze)),
    ]
    for key, name, fn in steps:
        try:
            out["steps"][key] = fn()
        except Exception as exc:
            raise PipelineError(name, exc) from exc
    return out
