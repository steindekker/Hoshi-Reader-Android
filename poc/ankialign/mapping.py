"""Per-source field mappers: source-field dict -> Kinomoto-field dict.

Pure (only depends on markup). Keys present in the result are written to the
converted note; everything else on Kinomoto stays blank. Source fields not
referenced here are dropped.
"""
from . import markup


def _first_nonempty(*vals: str) -> str:
    for v in vals:
        if v and v.strip():
            return v
    return ""


def map_mynote(s: dict) -> dict:
    tw = s.get("Target Word", "")
    return {
        "Term": markup.jrp_surface(tw),
        "Reading": markup.jrp_reading(tw),
        "Furigana": markup.jrp_to_furigana(tw),
        "Sentence": s.get("Sentence", ""),
        "Meaning": s.get("Definitions", ""),
        "Pitch Accent": s.get("Pitch", ""),
        "Image": markup.as_image(_first_nonempty(s.get("Screenshot", ""), s.get("Images", ""))),
        "Term Audio": markup.as_sound(s.get("Word Audio", "")),
        "Sentence Audio": markup.as_sound(s.get("Sentence Audio", "")),
        "Frequency": s.get("Frequency", ""),
    }


def map_yuuna(s: dict) -> dict:
    return {
        "Term": markup.jrp_surface(s.get("Term", "")),
        "Reading": s.get("Reading", ""),
        "Furigana": s.get("Furigana", ""),
        "Sentence": s.get("Sentence", ""),
        "Meaning": s.get("Meaning", ""),
        "Notes": s.get("Notes", ""),
        "Image": markup.as_image(s.get("Image", "")),
        "Term Audio": markup.as_sound(s.get("Term Audio", "")),
        "Pitch Accent": s.get("Pitch Accent", ""),
        "Expanded Meaning": s.get("Expanded Meaning", ""),
        "Collapsed Meaning": s.get("Collapsed Meaning", ""),
        "Sentence Audio": markup.as_sound(s.get("Sentence Audio", "")),
    }


def map_kanjistudy(s: dict) -> dict:
    ef = s.get("EntryFurigana", "")
    return {
        "Term": markup.entryfurigana_surface(ef),
        "Reading": markup.entryfurigana_reading(ef),
        "Furigana": ef,
        "Meaning": s.get("Meaning", ""),
        "Term Audio": markup.as_sound(s.get("Word Audio", "")),
    }


MAPPERS = {
    "MyNote": map_mynote,
    "jidoujisho Yuuna": map_yuuna,
    "Kanji Study Word Model v3": map_kanjistudy,
}
