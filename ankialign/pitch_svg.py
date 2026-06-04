"""Pure SVG pitch-graph generator (jidoujisho-style). No anki/aqt imports.

Mora-splitting + accent-contour rules are ported from the Kinomoto card
template's JRP renderer (research/pitch-pipeline/reference/kino_template.js):
split_moras / i_dan / e_comp.
"""

# い-dan kana that combine with small ゃゅょ
_I_DAN = set("キギシジチヂニヒビピミリ")
# bases that combine with small ェ
_E_COMP = set("イウキギクグシジチツニヒビピフミリヴ")


def _to_kata(s: str) -> str:
    """Hiragana -> katakana (1:1, length-preserving). Non-kana pass through."""
    return "".join(chr(ord(c) + 0x60) if "ぁ" <= c <= "ゖ" else c for c in s)


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
