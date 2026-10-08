from ankienrich.jrp_fill.unit import Unit

def test_unit_is_frozen_with_defaults():
    u = Unit(surface="立て", lemma="立てる", reading="たて",
             lemma_reading="たてる", is_content=True, is_yougen=True,
             accents=(2,))
    assert u.surface == "立て"
    assert u.accents == (2,)
    assert u.ambiguous is False  # default
    import dataclasses
    with __import__("pytest").raises(dataclasses.FrozenInstanceError):
        u.surface = "x"  # type: ignore[misc]

def test_bare_unit_has_no_accents():
    u = Unit(surface="は", lemma="は", reading="は",
             lemma_reading="は", is_content=False, is_yougen=False)
    assert u.accents == ()
    assert u.is_content is False
