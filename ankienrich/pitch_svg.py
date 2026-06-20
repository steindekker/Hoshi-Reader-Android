"""Pure SVG pitch-graph generator (jidoujisho-style). No anki/aqt imports.

Mora-splitting + accent-contour rules are ported from the Kinomoto card
template's JRP renderer (research/pitch-pipeline/reference/kino_template.js):
split_moras / i_dan / e_comp.
"""
from jaconv import hira2kata as _to_kata

# い-dan kana that combine with small ゃゅょ
_I_DAN = set("キギシジチヂニヒビピミリ")
# bases that combine with small ェ
_E_COMP = set("イウキギクグシジチツニヒビピフミリヴ")


def _combines(ck: str, nk: str) -> bool:
    """True if katakana base `ck` and following small kana `nk` form one mora."""
    return (
        (ck in _I_DAN and nk in "ャュョ")
        or (nk == "ヮ" and ck in "クグ")
        or (nk == "ァ" and ck in "ツフヴ")
        or (nk == "ィ" and ck in "クグスズテツデフイウヴ")
        or (nk == "ゥ" and ck in "トドホウ")
        or (nk == "ェ" and ck in _E_COMP)
        or (nk == "ォ" and ck in "クグツフウヴ")
        or (nk == "ャ" and ck in "フヴ")
        or (nk == "ュ" and ck in "テデフウヴ")
        or (nk == "ョ" and ck in "フヴ")
    )


def split_moras(reading: str) -> list[str]:
    """Segment a kana reading into moras, preserving the original script.

    Matching uses a katakana-normalized copy (same length), but the emitted
    substrings are sliced from the original `reading`.
    """
    kata = _to_kata(reading)
    moras: list[str] = []
    i, n = 0, len(reading)
    while i < n:
        ck = kata[i]
        nk = kata[i + 1] if i + 1 < n else None
        if nk is not None and _combines(ck, nk):
            moras.append(reading[i:i + 2])
            i += 2
        else:
            moras.append(reading[i])
            i += 1
    return moras


def _is_high(i: int, accent: int, n: int) -> bool:
    """Pitch height of point `i` (0..n; n = particle slot) for `n`-mora word."""
    if accent == 0:          # heiban
        return i != 0
    if accent == 1:          # atamadaka
        return i == 0
    return 1 <= i < accent   # nakadaka/odaka: high on moras 1..accent-1


def _f(x: float) -> str:
    """Compact float -> str (61.2 not 61.199999…)."""
    return f"{x:g}"


def render_svg(reading: str, accent: int) -> str:
    """Self-contained jidoujisho-style SVG pitch graph for `reading` + `accent`."""
    moras = split_moras(reading)
    n = len(moras)
    if n == 0:
        return ""
    pts = [(16 + 35 * i, 5 if _is_high(i, accent, n) else 30) for i in range(n + 1)]
    vb_w = 35 * n + 32
    out = [
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{_f(vb_w * 0.6)}px" '
        f'height="45px" viewBox="0 0 {vb_w} 75">'
    ]
    for i, m in enumerate(moras):
        out.append(
            f'<text x="{5 + 35 * i}" y="67.5" '
            f'style="font-size:20px;font-family:sans-serif;fill:currentColor;">{m}</text>'
        )
    for i in range(n):
        (x0, y0), (_, y1) = pts[i], pts[i + 1]
        out.append(
            f'<path d="m {x0},{y0} 35,{y1 - y0}" '
            f'style="fill:none;stroke:currentColor;stroke-width:1.5;" />'
        )
    for i in range(n):
        x, y = pts[i]
        out.append(f'<circle r="5" cx="{x}" cy="{y}" style="opacity:1;fill:currentColor;"></circle>')
    px, py = pts[n]
    out.append(
        f'<circle r="4" cx="{px}" cy="{py}" stroke="currentColor" '
        f'style="opacity:1;fill:none;"></circle>'
    )
    out.append("</svg>")
    return "".join(out)


def render_multi(reading: str, accents: list[int]) -> str:
    """Stack one graph per accepted accent, `<br>`-separated — matching how
    jidoujisho lays out multi-accent words."""
    return "<br>".join(render_svg(reading, a) for a in accents)
