import pytest

from ankienrich import pipeline


def test_step_failure_is_tagged_with_pass_name(monkeypatch):
    monkeypatch.setattr(pipeline.def_build, "ensure", lambda: "no-source")

    class FakeCol:
        def find_notes(self, q): return []

    def boom(col, apply, overwrite, note_ids=None):
        raise RuntimeError("kaboom")

    monkeypatch.setattr(pipeline, "_step_audio", boom)

    with pytest.raises(pipeline.PipelineError) as ei:
        pipeline.run(FakeCol(), apply=False)
    assert ei.value.step == "audio"
    assert isinstance(ei.value.original, RuntimeError)
