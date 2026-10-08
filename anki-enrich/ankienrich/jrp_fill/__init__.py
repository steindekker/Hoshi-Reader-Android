"""Offline JRP sentence-markup generator (run headless).

Pure transforms: kana, unit, align, emit, splice (stdlib only).
Heavy deps:      accent (shipped index), tokenize (Sudachi).
Collection I/O:  engine (the `anki` library).
"""


def make_generator(conj_accent: bool = False):
    """Return generate(plain_text) -> JRP markup, the function engine.build_plan
    expects. Loads the accent index + Sudachi lazily (heavy) so importing this
    package stays cheap."""
    from .accent import AccentIndex
    from .tokenize import units
    from .emit import emit_sentence
    acc = AccentIndex.load()
    return lambda text: emit_sentence(units(text, acc, conj_accent=conj_accent))
