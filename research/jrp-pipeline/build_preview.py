#!/usr/bin/env python
"""Render the JRP sentence-fill dry-run as an HTML page using the card's own
renderer (the JRP add-on JS + CSS pulled live from the note type), so the
output can be eyeballed in a browser instead of read as raw {unit;accent}.

    uv run --group dev --group jrp python research/jrp-pipeline/build_preview.py \
        [--limit N] [--out preview.html]

Open the resulting file in any browser. No Anki required to view.
"""
from __future__ import annotations
import argparse, os, re, sys

ROOT = os.path.expanduser("~/projects/anki-align")
sys.path.insert(0, ROOT)


def _extract_renderer(css: str, afmt: str) -> tuple[str, str]:
    """Pull the JRP-managed CSS block and the renderer <script> from the live
    note type so the preview always matches what Anki actually shows."""
    css_block = css.split("/* JRP add-on managed section start", 1)
    if len(css_block) != 2:
        raise SystemExit("JRP CSS block not found in note type")
    jrp_css = "/* JRP add-on managed section start" + \
        css_block[1].split("/* JRP add-on managed section end */", 1)[0]

    m = re.search(r"<script>.*?</script>", afmt, re.S)
    if not m:
        raise SystemExit("JRP renderer <script> not found in template")
    return jrp_css, m.group(0)


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--collection", default=os.path.join(ROOT, "collection.anki2"))
    ap.add_argument("--note-type", default="jidoujisho Kinomoto")
    ap.add_argument("--field", default="Sentence")
    ap.add_argument("--limit", type=int, default=100,
                    help="number of evenly-sampled cards to show (0 = all)")
    ap.add_argument("--mode", choices=("fill", "existing", "conj"), default="fill",
                    help="fill: plain/partial cards we'd change (old=before, new=our fill); "
                         "existing: cards that ALREADY have pitch (old=accepted gold, "
                         "new=our regeneration); conj: computed conjugated pitch (new) vs "
                         "dict-form graph (old), only cards where they differ")
    ap.add_argument("--out", default=os.path.join(ROOT, "research/jrp-pipeline/work/preview.html"))
    args = ap.parse_args(argv)

    from anki.collection import Collection
    from ankialign.jrp_fill import engine
    from ankialign.jrp_fill.accent import AccentIndex
    from ankialign.jrp_fill.tokenize import units
    from ankialign.jrp_fill.emit import emit_sentence, emit_unit
    from ankialign import markup

    col = Collection(args.collection)
    try:
        m = col.models.by_name(args.note_type)
        jrp_css, renderer = _extract_renderer(m["css"], m["tmpls"][0]["afmt"])
        acc = AccentIndex.load()
        gen = lambda text: emit_sentence(units(text, acc))
        if args.mode == "fill":
            # (old, new) = (before, our fill) for cards lacking pitch
            pairs = [(f.old_value, f.new_value)
                     for f in engine.build_plan(col, args.note_type, args.field, gen)]
        else:
            # (old, new) = (accepted gold, our regen) | (dict-form, computed conj)
            fields = [fl["name"] for fl in m["flds"]]
            si = fields.index(args.field)
            pairs = []
            seen: set = set()
            for nid in col.find_notes(f'note:"{args.note_type}"'):
                v = col.get_note(nid).fields[si]
                if args.mode == "existing":
                    if "{" in v and ";" in v:             # already has pitch data
                        pairs.append((v, gen(markup.jrp_surface(v))))
                else:  # conj: a deduplicated catalog of the CHANGED words
                    plain = markup.jrp_surface(v) if ("{" in v or "[" in v) else v
                    a = units(plain, acc, conj_accent=True)   # computed
                    b = units(plain, acc, conj_accent=False)  # dict-form
                    if len(a) != len(b):
                        continue
                    for ua, ub in zip(a, b):
                        if not ua.conj_computed:
                            continue
                        new, old = emit_unit(ua), emit_unit(ub)
                        if new != old and (key := (ua.surface, ua.reading)) not in seen:
                            seen.add(key)
                            pairs.append((old, new))
    finally:
        col.close()

    total = len(pairs)
    # sample evenly across the whole set so the examples are varied, not just the
    # first N (which cluster by note order)
    if args.limit and args.limit < total:
        step = total / args.limit
        pairs = [pairs[int(i * step)] for i in range(args.limit)]

    rows = []
    for old, new in pairs:
        # both lines go through the card's JRP renderer (unescaped, as Anki injects
        # the field) for a like-for-like visual comparison.
        rows.append(f"""
    <div class="row">
      <div class="ja" data-jrp-generate>{new}</div>
      <div class="ja old" data-jrp-generate>{old}</div>
    </div>""")

    # top line is always OUR output; the "old" line is the before (fill) or the
    # accepted jidoujisho gold (existing)
    old_label = {"fill": "was", "existing": "gold (jidoujisho)",
                 "conj": "dict-form (current)"}[args.mode]
    heading = {"fill": "our fill vs before", "existing": "ours vs accepted gold",
               "conj": "computed conjugated pitch (top) vs dict-form (bottom) — distinct changed words"}[args.mode]

    # conj mode: a catalog of single changed words -> show the full pitch GRAPH
    # (the line contour) always, so the difference is unmistakable.
    if args.mode == "conj":
        mode_css = (".ja { line-height: 4.2; }\n"
                    ".jrp-unit { margin-top: 2.2em !important; }\n"
                    ".jrp-graph { visibility: visible !important; position: static !important;"
                    " display: inline-block; margin-left: .6em; vertical-align: middle; }")
    else:
        mode_css = (".jrp-unit { margin:0 .12em 0 .12em !important; }\n"
                    ".jrp-indicator-container { display:none !important; }")

    os.makedirs(os.path.dirname(args.out), exist_ok=True)
    doc = f"""<!doctype html>
<html lang="ja"><head><meta charset="utf-8">
<title>JRP preview — {heading} ({len(pairs)} of {total})</title>
<style>
{jrp_css}
/* preview overrides */
body {{ background:#faf9f7; color:#222; font-family:system-ui,sans-serif;
       max-width:54rem; margin:2rem auto; padding:0 1.5rem; line-height:1.5; }}
h1 {{ font-size:1rem; font-weight:600; color:#666; margin-bottom:1.5rem; }}
.row {{ border-bottom:1px solid #e5e2dc; padding:1.1rem 0; }}
.ja  {{ font-size:1.7rem; line-height:1.9; }}
/* OLD line: identical rendering, same left edge as ours so characters line up
   vertically; marked only by the label above (no horizontal offset) */
.old {{ margin-top:.5rem; padding-top:.4rem; border-top:1px dashed #e0ddd5; }}
.old::before {{ content:"{old_label}"; display:block; font-size:.6rem; letter-spacing:.08em;
                text-transform:uppercase; color:#bbb; font-family:system-ui,sans-serif; }}
{mode_css}
rt {{ font-size:60%; color:#888; }}
</style></head>
<body>
<h1>JRP preview — {heading} — {len(pairs)} of {total} (evenly sampled)</h1>
{''.join(rows)}
{renderer}
</body></html>"""

    with open(args.out, "w", encoding="utf-8") as fh:
        fh.write(doc)
    print(f"wrote {args.out} ({len(pairs)} of {total}, mode={args.mode})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
