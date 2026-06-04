"""Optional marine neural-accent fallback for dict misses.

marine predicts per-mora accent_status in binary mode; each mora is tagged 1
at the accent nucleus (downstep position) or 0 otherwise.  We convert the
first nucleus mora index to a downstep position (0 = heiban).

Node format marine expects:
  - surface: str (use literal surface; any value not in the vocab table falls
    back to [UNK], which avoids the uint8 overflow bug in marine 0.0.6 when
    surface table ids exceed 255)
  - pron: katakana reading (NOT hiragana)
  - pos: colon-separated MeCab pos fields, e.g. "名詞:一般:*:*"
  - c_type, c_form: MeCab inflection type/form or "*"
  - accent_type: int
  - accent_con_type: str
  - chain_flag: int (-1 = sentence start)

Heavy: lazy import + lru_cache so the model loads only on first call.
"""
from __future__ import annotations
from functools import lru_cache
from .unit import Unit


def _hira_to_kata(s: str) -> str:
    return "".join(chr(ord(c) + 0x60) if "ぁ" <= c <= "ん" else c for c in s)


@lru_cache(maxsize=1)
def _predictor():
    from marine.predict import Predictor
    return Predictor()


def marine_fill(u: Unit) -> "tuple[int, ...] | None":
    """Return downstep mora positions predicted by marine, or None on failure.

    For single-morpheme units we return a one-element tuple: (0,) for heiban
    or (n,) where n is the 1-based downstep mora index.
    """
    try:
        pred = _predictor()
        kata_reading = _hira_to_kata(u.lemma_reading) if u.lemma_reading else u.surface
        node = {
            "surface": u.surface,   # may map to [UNK] in vocab; that's fine
            "pron": kata_reading,
            "pos": "名詞:一般:*:*",
            "c_type": "*",
            "c_form": "*",
            "accent_type": 0,
            "accent_con_type": "*",
            "chain_flag": -1,
        }
        result = pred.predict([[node]])
        statuses = result["accent_status"][0]  # list[int] per mora
        # binary mode: 1 marks the accent nucleus (downstep after that mora)
        if 1 in statuses:
            downstep = statuses.index(1) + 1
        else:
            downstep = 0
        return (downstep,)
    except Exception:
        return None
