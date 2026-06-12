"""Assemble the card-facing 例文 block from selected examples. No anki imports.

The whole section is one collapsed <details> so the card stays clean until the
user expands it (the "expandable example sentences" UX). Each item shows the JP
sentence (furigana kept where the source ships it) with the target word bolded
and the EN translation under it when present."""
import html


def render_block(examples: list[dict]) -> str:
    """examples -> HTML for the Examples field, or '' if none."""
    if not examples:
        return ""
    items = []
    for ex in examples:
        en = (f'<span class="aa-ex-en">{html.escape(ex["en"])}</span>'
              if ex.get("en") else "")
        items.append(f'<div class="aa-ex-item">'
                     f'<span class="aa-ex-jp" lang="ja">{ex["jp"]}</span>{en}</div>')
    return (f'<details class="aa-ex"><summary>例文</summary>'
            f'<div class="aa-ex-list">{"".join(items)}</div></details>')
