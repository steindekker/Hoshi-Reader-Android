#!/usr/bin/env python
"""Scrape OJAD's server-rendered word-conjugation table as a pitch-accent oracle.

OJAD marks each mora span with `accent_top` (the nucleus = last high mora before
the downstep) and `mola_-N` (position counted from the end). For a form with M
moras and the nucleus at mola_-k, the JRP/standard downstep number is M-k+1; no
`accent_top` => heiban (0).

Used only to BUILD the curated conjugation test set + validate the rule engine;
not shipped. Polite: caches each word's HTML under work/ojad_cache/.
"""
from __future__ import annotations
import os, re, sys, time, urllib.parse, urllib.request

CACHE = os.path.join(os.path.dirname(__file__), "work", "ojad_cache")
FORMS = {  # td class key -> human form name
    "jisho": "dict", "masu": "masu", "te": "te", "ta": "ta", "nai": "nai",
    "nakatta": "nakatta", "ba": "ba", "shieki": "causative", "ukemi": "passive",
    "meirei": "imperative", "kano": "potential", "ishi": "volitional",
}
_TD = re.compile(r'<td class="katsuyo katsuyo_(\w+?)_js">(.*?)</td>', re.S)
_MOLA = re.compile(r'class="([^"]*?)mola_(-?\d+)"><span class="inner"><span class="char">([^<]*)</span>')


def fetch(word: str) -> str:
    os.makedirs(CACHE, exist_ok=True)
    path = os.path.join(CACHE, urllib.parse.quote(word, safe="") + ".html")
    if os.path.exists(path):
        return open(path, encoding="utf-8").read()
    url = "https://www.gavo.t.u-tokyo.ac.jp/ojad/search/index/word:" + urllib.parse.quote(word)
    req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0 (research)"})
    with urllib.request.urlopen(req, timeout=30) as r:
        body = r.read().decode("utf-8", "replace")
    open(path, "w", encoding="utf-8").write(body)
    time.sleep(1.0)  # be gentle
    return body


def _accent_of(cell_html: str) -> tuple[str, int]:
    # take moras from the FIRST accented_word only (adjective cells list two
    # variants back-to-back; stop before the second)
    i = cell_html.find('class="accented_word"')
    block = cell_html[i:] if i >= 0 else cell_html
    j = block.find('class="accented_word"', 1)
    if j > 0:
        block = block[:j]
    moras = _MOLA.findall(block)
    reading = "".join(c for _, _, c in moras)
    total = len(moras)
    nucleus = 0
    for cls, idx, _ in moras:
        if "accent_top" in cls:
            nucleus = total + int(idx) + 1      # idx is negative (from end)
            break
    return reading, nucleus


def conjugations(word: str) -> dict[str, tuple[str, int]]:
    """{form_name: (reading_katakana, accent)} for the first matching headword."""
    h = fetch(word)
    out: dict[str, tuple[str, int]] = {}
    for key, cell in _TD.findall(h):
        name = FORMS.get(key)
        if not name or name in out or 'class="accented_word"' not in cell:
            continue
        out[name] = _accent_of(cell)
    return out


if __name__ == "__main__":
    for word in sys.argv[1:]:
        print(f"== {word}")
        for form, (rd, acc) in conjugations(word).items():
            print(f"   {form:11} {rd:10} [{acc}]")
