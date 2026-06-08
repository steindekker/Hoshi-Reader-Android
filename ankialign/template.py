"""Apply the repo-owned Kinomoto card design to the note type.

The canonical front/back/style live in card/kinomoto/ (first-class repo files).
apply() writes them onto the note type verbatim, then appends the generated
scoped dictionary styles (var/dict_styles.css) after a marker so a def-index
rebuild can refresh them without disturbing the authored design."""
import os

from . import config, paths

_STYLE_MARKER = "/* ---- AnkiAlign dict styles (generated) ---- */"


def _read(name: str) -> str:
    with open(os.path.join(paths.CARD_DIR, name), encoding="utf-8") as fh:
        return fh.read()


def apply(col) -> None:
    m = col.models.by_name(config.NOTETYPE)
    if m is None:
        raise RuntimeError("target notetype missing: " + config.NOTETYPE)
    base_css = _read("style.css").rstrip()
    dict_css = ""
    if os.path.exists(paths.DICT_STYLES):
        with open(paths.DICT_STYLES, encoding="utf-8") as fh:
            dict_css = "\n\n" + _STYLE_MARKER + "\n" + fh.read()
    m["css"] = base_css + dict_css
    tmpl = m["tmpls"][0]
    tmpl["qfmt"] = _read("front.html")
    tmpl["afmt"] = _read("back.html")
    col.models.update_dict(m)
