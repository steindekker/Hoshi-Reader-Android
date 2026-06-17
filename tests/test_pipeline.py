import os

from anki.collection import Collection

from ankienrich import config, pipeline


def _kino_col(tmp):
    col = Collection(os.path.join(tmp, "c.anki2"))
    m = col.models.new(config.NOTETYPE)
    for fname in (config.TERM, config.READING, config.SENTENCE, config.PITCH,
                  config.CLOZE_BEFORE, config.CLOZE_INSIDE, config.CLOZE_AFTER,
                  config.TERM_AUDIO, config.DEFINITION):
        col.models.add_field(m, col.models.new_field(fname))
    t = col.models.new_template("jidoujisho Kinomoto")
    t["qfmt"], t["afmt"] = "{{Term}}", "{{Reading}}"
    col.models.add_template(m, t)
    col.models.add(m)
    return col


def test_dry_run_reports_steps(tmp_path, monkeypatch):
    # Force audio + defs + examples to the skip path so the test needs no external assets.
    from ankienrich.audio import build as audio_build
    from ankienrich.defs import build
    from ankienrich.examples import build as ex_build
    monkeypatch.setattr(audio_build, "ensure", lambda: "no-source")
    monkeypatch.setattr(build, "ensure", lambda: "no-source")
    monkeypatch.setattr(ex_build, "ensure", lambda: "no-source")
    col = _kino_col(str(tmp_path))
    try:
        res = pipeline.run(col, apply=False)
        assert set(res["steps"]) == {"audio", "pitch", "sentences", "enrich", "examples", "cloze"}
        assert "skipped" in res["steps"]["audio"]
        assert "skipped" in res["steps"]["enrich"]
        assert "skipped" in res["steps"]["examples"]
        assert res["backup"] is None
    finally:
        col.close()
