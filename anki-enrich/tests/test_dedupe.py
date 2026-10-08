import pytest
anki = pytest.importorskip("anki")
from anki.collection import Collection
from ankienrich import config, dedupe

KINOMOTO_FIELDS = [
    config.TERM, config.READING, config.TERM_FURIGANA, config.SENTENCE,
    config.SENTENCE_FURIGANA, config.CLOZE_BEFORE, config.CLOZE_INSIDE,
    config.CLOZE_AFTER, config.DEFINITION, "Expanded Meaning",
    "Collapsed Meaning", "Notes", "Context", "Frequency", config.PITCH,
    "Image", config.TERM_AUDIO, "Sentence Audio",
]


def _kinomoto(col):
    m = col.models.new(config.NOTETYPE)
    for fn in KINOMOTO_FIELDS:
        col.models.add_field(m, col.models.new_field(fn))
    t = col.models.new_template("Card 1")
    t["qfmt"] = f"{{{{{config.TERM}}}}}"; t["afmt"] = f"{{{{{config.READING}}}}}"
    col.models.add_template(m, t)
    col.models.add(m)
    return col.models.by_name(config.NOTETYPE)


@pytest.fixture
def col(tmp_path):
    c = Collection(str(tmp_path / "t.anki2"))
    m = _kinomoto(c); c.models.set_current(m)
    deck = c.decks.id("言葉文章")

    def add(term, reading):
        n = c.new_note(m); n[config.TERM] = term; n[config.READING] = reading
        c.add_note(n, deck); return n.id

    dup_a = add("投降", "とうこう")        # duplicate pair
    dup_b = add("投降", "とうこう")
    add("単独", "たんどく")                # unique -> not a group
    # make dup_a the soonest-due review card; dup_b a fresh (queue 0) card
    c.db.execute("update cards set queue=2, due=5, reps=3 where nid=?", dup_a)
    c.db.execute("update cards set queue=0, due=900, reps=0 where nid=?", dup_b)
    c.save()
    yield c
    c.close()


def test_plan_finds_one_group_keeps_soonest(col):
    merges = dedupe.plan(col)
    assert len(merges) == 1
    key, keep, drop = merges[0]
    assert key == ("投降", "とうこう")
    assert keep["due"] == 5 and len(drop) == 1 and drop[0]["due"] == 900


def test_run_dry_does_not_delete(col):
    res = dedupe.run(col, apply=False)
    assert res["groups"] == 1 and res["removed"] == 1
    assert col.note_count() == 3   # nothing removed


def test_run_apply_removes_duplicate(col):
    res = dedupe.run(col, apply=True)
    assert res["removed"] == 1
    assert col.note_count() == 2
    # the surviving 投降 note is the soonest-due one
    survivors = [col.get_note(i)[config.TERM] for i in col.find_notes("")]
    assert survivors.count("投降") == 1
