"""The conjugated-pitch rule engine, validated against OJAD reference cases frozen
in tests/data/conj_accent_cases.tsv (built by research/jrp-pipeline/ojad_oracle.py)."""
import os
from ankienrich.jrp_fill.conjugate import verb_downstep, adj_downstep, count_moras

TSV = os.path.join(os.path.dirname(__file__), "data", "conj_accent_cases.tsv")
_DERIVED = {"causative", "passive", "potential"}
# adjectives don't take these (OJAD repeats the dict form in those columns)
_ADJ_SKIP = _DERIVED | {"imperative", "volitional"}


def _load():
    by: dict[str, dict] = {}
    for line in open(TSV, encoding="utf-8"):
        if line.startswith("#") or not line.strip():
            continue
        lemma, pos, form, reading, acc = line.rstrip("\n").split("\t")
        by.setdefault(lemma, {})[form] = (pos, reading, int(acc))
    return by


def test_engine_matches_ojad_reference_cases():
    by = _load()
    assert by, "fixture missing"
    checked = 0
    for lemma, forms in by.items():
        pos, _, dict_acc = forms["dict"]
        heiban, atamadaka = dict_acc == 0, dict_acc == 1
        for form, (_, reading, acc) in forms.items():
            m = count_moras(reading)
            if pos == "v":
                if form in _DERIVED:          # derived verb -> 中高/heiban present
                    pred = verb_downstep(heiban, False, "dict", m)
                else:
                    pred = verb_downstep(heiban, atamadaka, form, m)
            else:
                if form in _ADJ_SKIP:
                    continue
                stem = count_moras(forms["dict"][1]) - 1
                pred = adj_downstep(heiban, form, stem)
            assert pred == acc, f"{lemma} {form} {reading}: got {pred}, want {acc}"
            checked += 1
    assert checked >= 150


def test_passive_past_is_the_motivating_case():
    # 断られた: 断る[3] -> passive (中高) -> past = antepenult of ことわられた(6) = 4
    assert verb_downstep(False, False, "ta", count_moras("ことわられた")) == 4


def test_count_moras_handles_youon_and_special():
    assert count_moras("しゃ") == 1            # 拗音 fuses
    assert count_moras("がっこう") == 4         # っ and long vowel each count
    assert count_moras("ことわられた") == 6
