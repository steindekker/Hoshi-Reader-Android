"""Golden files for the Hoshi Android JRP parity harness (JrpParityTest).

Contains no corpus data; its output can contain private card text, so write it outside
the repo (e.g. a scratch dir) and never commit it.

usage:
  .venv/bin/python tools/jrp_parity_golden.py OUT_DIR --anki COLLECTION [--limit N]
  .venv/bin/python tools/jrp_parity_golden.py OUT_DIR --sentences FILE   (one field per line, \\n escapes)

Writes OUT_DIR/accent.jsonl ([key, positions] in index insertion order), gen.jsonl (each
distinct generate() input: output, units, SudachiPy mode C morphemes) and field.jsonl
(single-field apply_to_fixpoint(regenerate=False) result for the stored field and for its
plain surface).
"""
import argparse, gzip, json, marshal, os, sqlite3, sys, time

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from ankienrich import config, markup  # noqa: E402
from ankienrich.paths import ACCENT_INDEX  # noqa: E402
from ankienrich.jrp_fill.accent import AccentIndex  # noqa: E402
from ankienrich.jrp_fill.tokenize import units, _tok, _modes  # noqa: E402
from ankienrich.jrp_fill.emit import emit_sentence  # noqa: E402
from ankienrich.jrp_fill.splice import splice  # noqa: E402
from ankienrich.jrp_fill.engine import _norm  # noqa: E402


def anki_fields(path, limit):
    c = sqlite3.connect(f"file:{path}?immutable=1", uri=True)
    c.create_collation("unicase",
                       lambda a, b: (a.casefold() > b.casefold()) - (a.casefold() < b.casefold()))
    mid = c.execute("SELECT id FROM notetypes WHERE name=?", (config.NOTETYPE,)).fetchone()[0]
    names = [r[0] for r in c.execute("SELECT name FROM fields WHERE ntid=? ORDER BY ord", (mid,))]
    si = names.index(config.SENTENCE)
    rows = c.execute("SELECT id, flds FROM notes WHERE mid=? ORDER BY id", (mid,)).fetchall()
    for nid, flds in rows[:limit] if limit else rows:
        yield nid, flds.split("\x1f")[si]


def file_fields(path):
    with open(path, encoding="utf-8") as f:
        for i, line in enumerate(f):
            line = line.rstrip("\n")
            if line:
                yield i + 1, line.replace("\\n", "\n")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("out")
    ap.add_argument("--anki")
    ap.add_argument("--sentences")
    ap.add_argument("--limit", type=int)
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)

    with gzip.open(ACCENT_INDEX, "rb") as fh:
        table = marshal.loads(fh.read())
    with open(os.path.join(a.out, "accent.jsonl"), "w", encoding="utf-8") as f:
        for k, v in table.items():
            f.write(json.dumps([k, list(v)], ensure_ascii=False) + "\n")
    acc = AccentIndex(table)
    A, C = _modes()

    gen_rec = {}
    src = ["?"]

    def generate(text):
        r = gen_rec.get(text)
        if r is None:
            us = units(text, acc, conj_accent=False)
            r = (emit_sentence(us), us, set())
            gen_rec[text] = r
        r[2].add(src[0])
        return r[0]

    def clean(field):
        return markup.normalize_spaces(markup.strip_subtitle_labels(markup.strip_sentence_html(field)))

    def fixpoint(field, max_passes=6):
        cur = field
        for _ in range(max_passes):
            if not cur.strip():
                break
            cleaned = clean(cur)
            new = splice(cleaned, generate)
            if new == cur:
                break
            if _norm(markup.jrp_surface(new)) != _norm(markup.jrp_surface(cleaned)):
                break
            cur = new
        return cur

    fields = anki_fields(a.anki, a.limit) if a.anki else file_fields(a.sentences)
    t0, n = time.time(), 0
    with open(os.path.join(a.out, "field.jsonl"), "w", encoding="utf-8") as ff:
        for nid, field in fields:
            n += 1
            if not field.strip():
                continue
            cleaned = clean(field)
            has_markup = "{" in cleaned or "[" in cleaned
            if has_markup:  # build_plan regenerate path input
                src[0] = "regen"
                generate(markup.jrp_surface(cleaned))
            src[0] = "stored"  # pass 1 is exactly the server's splice path
            ff.write(json.dumps({"nid": nid, "kind": "stored", "in": field, "out": fixpoint(field)},
                                ensure_ascii=False) + "\n")
            plain = markup.jrp_surface(cleaned) if has_markup else field
            if plain != field:  # freshly mined card
                src[0] = "plain"
                ff.write(json.dumps({"nid": nid, "kind": "plain", "in": plain, "out": fixpoint(plain)},
                                    ensure_ascii=False) + "\n")

    def unit_json(u):
        return [u.surface, u.lemma, u.reading, u.lemma_reading, u.is_content, u.is_yougen,
                list(u.accents), u.ambiguous, u.conj_computed]

    def morph_json(m):
        return [m.surface(), list(m.part_of_speech()), m.dictionary_form(), m.reading_form(),
                [[x.surface(), x.reading_form()] for x in m.split(A)]]

    with open(os.path.join(a.out, "gen.jsonl"), "w", encoding="utf-8") as fg:
        for text, (o, us, srcs) in gen_rec.items():
            fg.write(json.dumps({"in": text, "out": o, "src": sorted(srcs),
                                 "morphs": [morph_json(m) for m in _tok().tokenize(text, C)],
                                 "units": [unit_json(u) for u in us]}, ensure_ascii=False) + "\n")
    print(f"fields={n} distinct_generate_inputs={len(gen_rec)} accent_entries={len(table)} "
          f"{time.time() - t0:.0f}s", file=sys.stderr)


if __name__ == "__main__":
    main()
