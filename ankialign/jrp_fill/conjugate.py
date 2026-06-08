"""Pitch-accent rules for conjugated 用言 (pure, no Sudachi/Anki).

Given the accent CLASS (heiban vs accented) and the outermost conjugation form,
compute the downstep mora on the conjugated reading. Rules derived from OJAD;
reference cases in tests/data/conj_accent_cases.tsv.

Accent number convention matches the rest of jrp_fill: N = downstep AFTER the
Nth mora (1-based); 0 = heiban (no downstep). The caller emits it on the
conjugated reading with `;Y<N>` and NO base, so the graph shows the real form.

`None` => not covered; caller falls back to the dict-form `;Y<dictacc>|base`.
"""
from __future__ import annotations

# small kana that fuse onto the previous mora (拗音 + small vowels); っ ん ー count
_SMALL = set("ゃゅょゎぁぃぅぇぉャュョヮァィゥェォ")

def count_moras(reading_hira: str) -> int:
    return sum(1 for c in reading_hira if c not in _SMALL)

# outer forms the engine understands; anything else -> None (fallback)
_VERB_FORMS = {"dict", "ta", "te", "nai", "nakatta", "masu", "ba",
               "volitional", "imperative"}
_ADJ_FORMS = {"dict", "ta", "te", "nai", "nakatta", "masu", "ba"}

def verb_downstep(heiban: bool, atamadaka: bool, form: str, n_moras: int) -> int | None:
    """Downstep for a verb in `form` with `n_moras` moras.

    `heiban`: dict accent 0. `atamadaka`: dict accent 1 AND not class-changed —
    these keep the accent on mora 1 in te/ta/ba/imperative instead of shifting
    back like 中高 verbs. Passive/causative/potential are class-preserving: the
    caller passes the extended reading with atamadaka=False (the derived verb is
    中高), and heiban stays heiban."""
    if form not in _VERB_FORMS:
        return None
    if heiban:
        if form in ("masu", "ba", "volitional"):
            return n_moras - 1
        if form == "nakatta":
            return n_moras - 3        # nucleus on the な of なかった
        return 0  # dict, te, ta, nai, imperative stay heiban
    # accented
    if form in ("masu", "volitional"):
        return n_moras - 1            # nucleus on the auxiliary (ま / よ)
    if form == "nai":
        return max(1, n_moras - 2)    # last mora of 未然形
    if form == "nakatta":
        return max(1, n_moras - 4)    # verb stem's last 自立拍
    if form in ("te", "ta", "ba", "imperative", "dict"):
        if atamadaka:
            return 1                  # 頭高 stays 頭高
        return max(1, n_moras - 1) if form in ("imperative", "dict") \
            else max(1, n_moras - 2)  # 中高: dict/imperative penult, te/ta/ba antepenult
    return None

def adj_downstep(heiban: bool, form: str, stem_moras: int) -> int | None:
    """Downstep for an i-adjective in `form`. `stem_moras` = moras of the
    dictionary reading minus the final い (the nucleus anchor)."""
    if form not in _ADJ_FORMS:
        return None
    if heiban:
        if form == "dict":
            return 0
        if form in ("te", "ta", "ba", "masu"):
            return stem_moras            # accent lands on the stem-final mora
        return stem_moras + 2            # nai/nakatta: on the な
    # accented
    if form in ("dict", "masu"):
        return stem_moras                # on the mora before い
    return max(1, stem_moras - 1)        # te/ta/ba/nai/nakatta shift back one
