#!/usr/bin/env python3
"""
Prototype: deterministic rule-based cleanup of Anki "Meaning" / "Definitions" fields.
Covers four source formats:
  1. MyNote Definitions  — JMdict HTML (Dutch glosses)
  2. jidoujisho Yuuna Meaning — plain-text multi-dict with 【…】 headers
  3. Kanji Study Word Model v3 Meaning — KS markup: word[reading,base;tags]
  4. jidoujisho Kinomoto Meaning — yomitan HTML multi-dict with <br> separators

Run:  uv run python research/definition-cleanup/clean_meaning_prototype.py
"""

import re
import html
from html.parser import HTMLParser


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def _strip_tags(text: str) -> str:
    """Remove all HTML tags from a string."""
    return re.sub(r"<[^>]+>", "", text)


def _unescape(text: str) -> str:
    return html.unescape(text)


def _collapse_whitespace(text: str) -> str:
    """Collapse runs of spaces/tabs; leave newlines alone."""
    return re.sub(r"[ \t]+", " ", text).strip()


# ---------------------------------------------------------------------------
# 1. MyNote Definitions — JMdict HTML
# ---------------------------------------------------------------------------
# Format: <div style="text-align: left;"><ol><li><i>(POS)</i> <ul><li>gloss</li>…</ul></li>…</ol></div>
# or:     <div style="text-align: left;"><i>(POS)</i> gloss</div>   (single-sense)
#
# Signal:  sense numbers (implicit in <ol>), POS tag, glosses
# Noise:   <div style=…>, inline style attrs, empty list structure
# Target:  <ol><li><i>pos</i> gloss1; gloss2</li>…</ol>  (semicolon-joined glosses)
#          Plain text: "1. (pos) gloss1; gloss2\n2. …"

class _MyNoteParser(HTMLParser):
    def __init__(self):
        super().__init__()
        self.senses = []           # list of (pos, [glosses])
        self._current_pos = None
        self._current_glosses = []
        self._in_pos = False
        self._in_gloss = False
        self._buf = ""

    def handle_starttag(self, tag, attrs):
        if tag == "li":
            # Outer <li> starts a new sense; inner <li> is a gloss item
            # We distinguish by whether we already have a current pos
            if not self._in_gloss:
                # new sense
                if self._current_pos is not None or self._current_glosses:
                    self.senses.append((self._current_pos, self._current_glosses))
                self._current_pos = None
                self._current_glosses = []
            else:
                self._buf = ""
        elif tag == "i":
            self._in_pos = True
            self._buf = ""
        elif tag == "ul":
            self._in_gloss = True

    def handle_endtag(self, tag):
        if tag == "i":
            self._in_pos = False
            self._current_pos = self._buf.strip().strip("()")
            self._buf = ""
        elif tag == "li" and self._in_gloss:
            g = self._buf.strip()
            if g:
                self._current_glosses.append(g)
            self._buf = ""
        elif tag == "li" and not self._in_gloss:
            # Outer <li> ending: capture any direct text (bare gloss not in <ul>)
            direct_text = self._buf.strip()
            if direct_text:
                self._current_glosses.append(direct_text)
            self._buf = ""
        elif tag == "ul":
            self._in_gloss = False
        elif tag in ("ol", "div"):
            if self._current_pos is not None or self._current_glosses:
                self.senses.append((self._current_pos, self._current_glosses))
                self._current_pos = None
                self._current_glosses = []

    def handle_data(self, data):
        self._buf += data


def clean_mynote(raw: str) -> dict:
    """
    Returns {"html": ..., "plain": ...}
    """
    raw = raw.strip()
    if not raw:
        return {"html": "", "plain": ""}

    # Single-sense shortcut: <div style=…><i>(pos)</i> gloss</div>
    single = re.match(
        r'<div[^>]*><i>\(([^)]+)\)</i>\s*(.*?)</div>$', raw, re.DOTALL
    )
    if single:
        pos, gloss = single.group(1), _strip_tags(single.group(2)).strip()
        html_out = f"<i>({pos})</i> {_unescape(gloss)}"
        plain_out = f"({pos}) {_unescape(gloss)}"
        return {"html": html_out, "plain": plain_out}

    parser = _MyNoteParser()
    parser.feed(raw)

    if not parser.senses:
        # fallback: strip all tags
        return {"html": _unescape(_strip_tags(raw)), "plain": _unescape(_strip_tags(raw))}

    # Merge duplicate POS senses and collapse single-gloss lists
    html_parts = []
    plain_parts = []
    for i, (pos, glosses) in enumerate(parser.senses, 1):
        glosses = [_unescape(g) for g in glosses]
        gloss_str = "; ".join(glosses) if glosses else ""
        pos_tag = f"<i>({pos})</i> " if pos else ""
        pos_plain = f"({pos}) " if pos else ""
        html_parts.append(f"<li>{pos_tag}{gloss_str}</li>")
        plain_parts.append(f"{i}. {pos_plain}{gloss_str}")

    html_out = "<ol>" + "".join(html_parts) + "</ol>"
    plain_out = "\n".join(plain_parts)
    return {"html": html_out, "plain": plain_out}


# ---------------------------------------------------------------------------
# 2. jidoujisho Yuuna — plain-text multi-dict
# ---------------------------------------------------------------------------
# Format: 【dict name】\nheadword line\n🈩/🈔/㊀/㊁/► etc. definition\n…\n【…】\n…
#
# Signal:  actual definitions (lines after the headword), maybe one example in 「」
# Noise:   headword lines (kana ‐ spacing, pitch, alt spellings), decorative symbols,
#           ━ continuation dashes, cross-ref lines (⇨), empty sub-entry lines (━word)
#           Repeated content across dicts (esp. JMdict Extra duplicate glosses)
#
# Strategy:
#  - Keep 【dict】 as a small <b> header (or plain "[dict]")
#  - Strip headword line (matches: kana reading with ‐, 【word】, pitch marks like ①⓪)
#  - Strip decorative leading symbols (🈩🈔㊀㊁㊂►◨↔⇔) keeping the text
#  - Strip sub-headword lines (starts with ━ followed by kana, not a definition dash)
#  - Keep example sentences (in 「」) inline
#  - For JMdict Extra / KireiCake bullet lists: keep as-is (already clean)
#  - Remove cross-reference-only lines (△ 〔=…〕 style expansions that add noise)

# Symbols to strip from line starts
_YUUNA_LEADING_SYMBOLS = re.compile(
    r'^[🈩🈔㊀㊁㊂㊃㊄㊅㊆㊇►◨↔⇔・\s]*'
)
# Headword line: contains 【…】 and kana with pitch/reading markup
_YUUNA_HEADWORD = re.compile(
    r'^[ぁ-ヿ゠-ヿ぀-ゟ\s‐（）〔〕①②③④⑤⑥⑦⑧⑨⑩⓪]+【'
)
# Sub-entry line: starts with ━ then kana (not a quoting dash inside definition)
_YUUNA_SUBENTRY = re.compile(r'^━[ぁ-ヿ぀-ヿ]')
# Cross-reference-only line
_YUUNA_XREF = re.compile(r'^[⇔↔]')
# Empty / whitespace-only
_YUUNA_EMPTY = re.compile(r'^\s*$')

def _is_yuuna_headword(line: str) -> bool:
    """True if line looks like a dictionary headword (reading + 【kanji】)."""
    # Has 【…】 and starts with kana / reading / pitch number
    if '【' not in line:
        return False
    before = line[:line.index('【')]
    # headword: mostly kana, spaces, pitch markers, hyphens, parens
    non_kana = re.sub(r'[ぁ-ヿ぀-ヿ\s‐（）〔〕①②③④⑤⑥⑦⑧⑨⑩⓪─\-\[\]０-９a-zA-Z]', '', before)
    return len(non_kana) <= 3  # allow a bit of noise

def clean_yuuna(raw: str) -> dict:
    if not raw.strip():
        return {"html": "", "plain": ""}

    lines = raw.split("\n")
    html_sections = []
    plain_sections = []
    current_dict = None
    current_lines_html = []
    current_lines_plain = []

    def flush():
        if current_dict is not None and (current_lines_html or current_lines_plain):
            h = f'<b>[{current_dict}]</b><br>' + "<br>".join(current_lines_html)
            p = f"[{current_dict}]\n" + "\n".join(current_lines_plain)
            html_sections.append(h)
            plain_sections.append(p)

    for line in lines:
        # Dict header
        m = re.match(r'^【(.+?)】\s*$', line)
        if m:
            flush()
            current_dict = m.group(1).strip()
            current_lines_html = []
            current_lines_plain = []
            continue

        # Skip headword lines
        if _is_yuuna_headword(line):
            continue
        # Skip sub-entry lines
        if _YUUNA_SUBENTRY.match(line):
            continue
        # Skip xref-only lines
        if _YUUNA_XREF.match(line):
            continue
        # Skip empty
        if _YUUNA_EMPTY.match(line):
            continue

        # Strip leading decorative symbols
        cleaned = _YUUNA_LEADING_SYMBOLS.sub('', line).strip()
        if not cleaned:
            continue

        # Escape for HTML output
        cleaned_html = html.escape(cleaned)
        current_lines_html.append(cleaned_html)
        current_lines_plain.append(cleaned)

    flush()

    html_out = "<br><br>".join(html_sections)
    plain_out = "\n\n".join(plain_sections)
    return {"html": html_out, "plain": plain_out}


# ---------------------------------------------------------------------------
# 3. Kanji Study Word Model v3 — KS markup
# ---------------------------------------------------------------------------
# Markup grammar (simplified):
#   surface[reading,base;tag1,tag2]   — kanji word with reading
#   surface[,base;tag1,tag2]          — kana with no explicit reading (base=bare form)
#   surface[;tag1]                    — no reading/base, just tags
#   {<br>text<br>}                    — English gloss block
#   {<br>⋯}                          — rating/star block like { ★★★<br>}
#   [N]                               — pitch accent number
#   （名）スル                          — POS abbreviation
#
# Signal:  surface text (before [), glosses in {…}, POS abbreviations (（名）etc.)
# Noise:   [reading,base;tags] annotations, {<br> blocks except English gloss content,
#           ★ rating, pitch [N] markers, —あ ひ style alt-reading fragments
#
# Strategy:
#  - Replace word[reading,base;tags] → surface text only
#  - Replace {<br>gloss text} → keep gloss text
#  - Strip { ★★★<br>} rating blocks
#  - Strip [N] pitch markers (digit in brackets)
#  - Strip [sound:…] (shouldn't be here but defensive)
#  - Convert <br> to newlines, then clean up
#  - Strip （名[めい]）スル style POS annotations or keep them stripped of markup

# KS annotation: word[reading,base;tags] — reading and/or base may be empty
_KS_ANNOTATION = re.compile(
    r'([^\[\{]*?)\[([^\]]*?);([^\]]*?)\]'  # surface[reading,base;tags]  — broad match
)
# Also covers [reading;tags] with no comma
_KS_ANNOTATION2 = re.compile(
    r'([^\[\{]*?)\[([^\];]*)\]'  # [pitch] like [0] or [1] — catch leftover []
)

def _ks_strip_annotations(text: str) -> str:
    """
    Remove [reading,base;tags] annotations, keeping only the surface token.
    Also strip standalone [digit] pitch markers and empty [,;] brackets.
    """
    # Match surface[...;...] — keep surface, drop [...]
    # We iterate to handle overlapping / nested cases
    result = ""
    i = 0
    while i < len(text):
        if text[i] == '[':
            # find matching ]
            j = text.find(']', i)
            if j == -1:
                result += text[i:]
                break
            inner = text[i+1:j]
            # Pure digit = pitch marker → drop
            if re.match(r'^\d+$', inner):
                i = j + 1
                continue
            # Has semicolon = KS annotation bracket → drop
            if ';' in inner:
                i = j + 1
                continue
            # Has comma = base form annotation → drop
            if ',' in inner:
                i = j + 1
                continue
            # Pure kana = furigana reading bracket e.g. [めい] → drop
            if re.match(r'^[ぁ-ヿ぀-ヿ]+$', inner):
                i = j + 1
                continue
            # Otherwise keep (shouldn't normally appear)
            result += text[i:j+1]
            i = j + 1
        else:
            result += text[i]
            i += 1
    return result


def _ks_extract_gloss_blocks(text: str):
    """
    Extract {<br>gloss} blocks.  Returns (text_without_gloss_blocks, glosses_list).
    Handles both:
      { ★★★<br>}          → drop (rating)
      { ★★★<br>1. def\n2. def} → keep numbered gloss
      {<br>gloss text}    → keep gloss text
    """
    glosses = []

    def replacer(m):
        inner = m.group(1)
        # Strip leading <br> and whitespace
        inner = re.sub(r'^[\s<br>]+', '', inner, flags=re.IGNORECASE)
        inner = inner.replace('<br>', '\n').replace('<BR>', '\n')
        inner = inner.strip()
        # Drop if only stars or empty
        if re.match(r'^[★☆\s]*$', inner):
            return ''
        # Drop rating prefix
        inner = re.sub(r'^[★☆]+\s*', '', inner)
        if inner:
            glosses.append(inner)
        return ''

    cleaned = re.sub(r'\{(.*?)\}', replacer, text, flags=re.DOTALL)
    return cleaned, glosses


def clean_kanji_study(raw: str) -> dict:
    if not raw.strip():
        return {"html": "", "plain": ""}

    text = raw

    # 1. Extract gloss blocks { … }
    text, glosses = _ks_extract_gloss_blocks(text)

    # 2. Convert <br> to newlines
    text = re.sub(r'<br\s*/?>', '\n', text, flags=re.IGNORECASE)

    # 3. Strip KS annotations from remaining text
    text = _ks_strip_annotations(text)

    # 4. Strip [sound:…] artifacts
    text = re.sub(r'\[sound:[^\]]+\]', '', text)

    # 5. Clean up punctuation artifacts: lone ─, ━, —
    # (These are reading separators like "き ─ あ ひ" which become noise)
    # Remove lines that are just spacing + dash fragments
    lines = text.split('\n')
    cleaned_lines = []
    for line in lines:
        line = line.strip()
        if not line:
            continue
        # Strip header-fragment lines: kana + 【…】 blocks + dashes + digits
        # After annotation removal these look like: "き あい 【気合 】き  あい  —あ ひ  【気合 】"
        # or "しり つ 【私立 】し  りつ  【私立 】"
        # or "あり そう 【有り そう 】"
        # Heuristic: line contains only kana, spaces, 【…】, dashes, digits, parens
        stripped_of_headers = re.sub(r'【[^】]*】', '', line)
        if re.match(r'^[ぁ-ヿ぀-ヿ\s―─━—\-０-９0-9（）]+$', stripped_of_headers):
            # Has 【…】 = dictionary entry header line → drop entirely
            if '【' in line:
                continue
            # Pure kana/dash with no content → drop
            continue
        # Drop reading-only fragments: e.g. "き  あい  —あ ひ  " after stripping annotations
        if re.match(r'^[ぁ-ヿ぀-ヿ\s―─━—\-]+$', line):
            continue
        cleaned_lines.append(line)

    # 6. Build JP definition text (numbered senses from ①②③ or 1.2.3.)
    jp_text = "\n".join(cleaned_lines).strip()
    # Normalize multiple blank lines
    jp_text = re.sub(r'\n{3,}', '\n\n', jp_text)

    # 7. Build output
    parts_html = []
    parts_plain = []

    if jp_text:
        # Convert sense markers ①②③ to visible numbered list
        jp_display = jp_text
        # Convert <br> that may remain
        jp_display_html = html.escape(jp_display).replace('①', '①').replace('②', '②').replace('③', '③')
        jp_display_html = jp_display_html.replace('\n', '<br>')
        parts_html.append(jp_display_html)
        parts_plain.append(jp_display)

    for g in glosses:
        g = g.strip()
        if g:
            parts_html.append(html.escape(g).replace('\n', '<br>'))
            parts_plain.append(g)

    html_out = "<br><br>".join(parts_html) if parts_html else ""
    plain_out = "\n\n".join(parts_plain) if parts_plain else ""
    return {"html": html_out, "plain": plain_out}


# ---------------------------------------------------------------------------
# 4. jidoujisho Kinomoto — yomitan HTML multi-dict
# ---------------------------------------------------------------------------
# Format: 【dict】<br>headword<br>[POS]<br>① def<br><br>【dict2】<br>…
#
# Signal:  definitions (lines starting with ①②etc or plain prose),
#          POS tags [名・形動], example sentences (「…」)
# Noise:   romaji [ローマ字](xxx), ► and ・ and ◨ bullet lines (for 研究社),
#          ┏ (word break hint), 《口》 register notes (keep), 〔…〕 sense labels (keep),
#          [派生]… derivative lines, sub-entry lines at 研究社 (word ending in ─)
#          NEW斎藤 numbered examples (0. lines)
#
# Strategy:
#  - Split on 【dict】 headers
#  - Per dict: strip headword line, strip [ローマ字](…), strip ┏ hints
#  - For 研究社: the format is very example-heavy; keep main definition line(s)
#    only (lines not starting with ► or ・), up to 2 example-intro lines
#  - For NEW斎藤: keep the 〈形〉/〈名〉 line + first sense; drop 0. example lines
#  - Emit as dict sections with <b>[dict]</b> header
#
# Dict preference order (for potential reduction): デジタル大辞泉 > 旺文社 > 研究社 > NEW斎藤

_KINOMOTO_DICT_SPLIT = re.compile(r'【(.+?)】')

# Lines to unconditionally drop
_KINO_ROMAJI = re.compile(r'\[ローマ字\]\([^)]+\)')
_KINO_WORD_BREAK = re.compile(r'┏')
_KINO_EXAMPLE_BULLET = re.compile(r'^[►・◨]')
_KINO_NUMBERED_EX = re.compile(r'^\d+\.\s+')  # "0. " examples in NEW斎藤
_KINO_HEADWORD = re.compile(
    r'^[ぁ-ヿ぀-ヿ\s‐〔〕０-９0-9]+【'   # kana headword + 【word】
)
# Saito: "いへん〔異変〕" — kana reading + 〔kanji〕 citation form
_KINO_SAITO_CITATION = re.compile(
    r'^[ぁ-ヿ぀-ヿ\s‐０-９0-9]+〔[^〕]+〕\s*$'
)
_KINO_READING_ONLY = re.compile(
    r'^[ぁ-ヿ぀-ヿ\s‐〔〕〈〉①-⑳⓪─ー゠-ヿ０-９]+$'
)
_KINO_DERIVATIVE = re.compile(r'^\[派生\]')
_KINO_POS = re.compile(r'^\[.+?\]$')  # standalone [名・形動] POS lines

def _clean_kinomoto_section(dict_name: str, section_text: str) -> str:
    """Clean one dictionary's section, return cleaned plain text."""
    # section_text is already newline-split (no <br> remain)
    lines = [l.strip() for l in section_text.split('\n')]
    out = []
    is_saito = 'NEW斎藤' in dict_name or '斎藤' in dict_name
    is_kenkyusha = '研究社' in dict_name
    for line in lines:
        if not line:
            continue
        # Drop romaji annotations
        line = _KINO_ROMAJI.sub('', line).strip()
        # Drop ┏ word-break hints
        line = _KINO_WORD_BREAK.sub('', line)
        # Drop headword lines
        if _KINO_HEADWORD.match(line):
            continue
        # Drop standalone POS bracket lines like [名・形動]
        # (keep them if they have content after)
        if _KINO_POS.match(line):
            continue
        # Drop derivative lines
        if _KINO_DERIVATIVE.match(line):
            continue
        # For NEW斎藤: drop "0. " numbered example lines and kana〔kanji〕 citation lines
        if is_saito and _KINO_NUMBERED_EX.match(line):
            continue
        if is_saito and _KINO_SAITO_CITATION.match(line):
            continue
        # For 研究社: drop ► and ・ bullet lines (examples/compound lines)
        if is_kenkyusha and _KINO_EXAMPLE_BULLET.match(line):
            continue
        # For 研究社: drop inflected-form header lines like "裕福に" or "裕福さ word."
        # These are lone kanji+kana lines that introduce sub-entries (no definition on them)
        if is_kenkyusha and re.match(r'^[一-鿿]+[ぁ-ヿ]*\s*$', line):
            continue
        # Drop lines that are only kana/spaces/dashes (residual reading-only fragments)
        if _KINO_READING_ONLY.match(line) and '【' not in line:
            # Allow if it looks like a POS annotation e.g. （名・形動ダ）
            if not re.search(r'（[^）]+）', line):
                continue
        # Strip ◨ line-leading marker (rare)
        line = re.sub(r'^◨', '', line).strip()
        if line:
            out.append(line)
    return "\n".join(out)



# Known dict names for Kinomoto (used to distinguish real headers from inline 【…】)
_KINOMOTO_KNOWN_DICTS = {
    'デジタル大辞泉', '旺文社国語辞典 第十一版 画像無し', '旺文社国語辞典',
    'NEW斎藤和英大辞典', '研究社　新和英大辞典　第５版', '研究社 新和英大辞典 第５版',
    '研究社新和英大辞典', 'KireiCake', 'JMdict Extra',
    '新明解国語辞典　第七版', '大辞林', '広辞苑',
}

def _is_kinomoto_dict_header(name: str) -> bool:
    """True if the 【…】 content is a known dictionary name (not an inline word)."""
    name = name.strip()
    # Exact match
    if name in _KINOMOTO_KNOWN_DICTS:
        return True
    # Prefix match for long names with version numbers
    for k in _KINOMOTO_KNOWN_DICTS:
        if name.startswith(k) or k.startswith(name):
            return True
    # Heuristic: dict names contain Latin chars or spaces; inline headwords are pure kanji
    if re.search(r'[a-zA-Zａ-ｚＡ-Ｚ\s　第版]', name) and len(name) > 3:
        return True
    return False


def clean_kinomoto(raw: str) -> dict:
    if not raw.strip():
        return {"html": "", "plain": ""}

    # Split on 【…】<br> patterns only at section boundaries
    # A real dict header appears either at start or after <br><br>
    # We split on <br><br>【…】 or ^【…】
    # Strategy: split by <br> into lines first, then scan for dict headers
    all_lines = raw.split('<br>')
    sections = []
    current_dict = None
    current_raw_lines = []

    for line in all_lines:
        line_stripped = line.strip()
        m = re.match(r'^【(.+?)】\s*$', line_stripped)
        if m and _is_kinomoto_dict_header(m.group(1)):
            if current_dict is not None:
                sections.append((current_dict, '\n'.join(current_raw_lines)))
            current_dict = m.group(1).strip()
            current_raw_lines = []
        else:
            current_raw_lines.append(line_stripped)

    if current_dict is not None:
        sections.append((current_dict, '\n'.join(current_raw_lines)))

    html_parts = []
    plain_parts = []
    for dict_name, section_raw in sections:
        cleaned = _clean_kinomoto_section(dict_name, section_raw)
        if not cleaned.strip():
            continue
        cleaned_html = html.escape(cleaned).replace('\n', '<br>')
        html_parts.append(f'<b>[{html.escape(dict_name)}]</b><br>{cleaned_html}')
        plain_parts.append(f'[{dict_name}]\n{cleaned}')

    html_out = "<br><br>".join(html_parts)
    plain_out = "\n\n".join(plain_parts)
    return {"html": html_out, "plain": plain_out}


# ---------------------------------------------------------------------------
# Format detection
# ---------------------------------------------------------------------------

def detect_format(raw: str) -> str:
    """Heuristically identify source format."""
    if raw.startswith('<div') or raw.startswith('<ol'):
        return 'mynote'
    # KS: has [,base;tags] or [;tags] annotation syntax
    if re.search(r'\[[^\]]*;[^\]]*\]', raw) and '{' in raw:
        return 'kanji_study'
    # Kinomoto: HTML <br> with 【dict】 headers
    if '<br>' in raw and '【' in raw:
        return 'kinomoto'
    # Yuuna: plain-text with 【dict】 headers, no <br>
    if '【' in raw and '<br>' not in raw:
        return 'yuuna'
    return 'unknown'


def clean(raw: str) -> dict:
    fmt = detect_format(raw)
    if fmt == 'mynote':
        return {**clean_mynote(raw), 'format': fmt}
    elif fmt == 'yuuna':
        return {**clean_yuuna(raw), 'format': fmt}
    elif fmt == 'kanji_study':
        return {**clean_kanji_study(raw), 'format': fmt}
    elif fmt == 'kinomoto':
        return {**clean_kinomoto(raw), 'format': fmt}
    else:
        return {'html': raw, 'plain': raw, 'format': 'unknown'}


# ---------------------------------------------------------------------------
# Samples from samples.md (embedded for standalone run)
# ---------------------------------------------------------------------------

SAMPLES = [
    # --- MyNote ---
    {
        "source": "MyNote",
        "term": "それ",
        "raw": '<div style="text-align: left;"><ol><li><i>(pn)</i> <ul><li>dat</li><li>die</li><li>het</li></ul></li><li><i>(pn)</i> <ul><li>daar-</li><li>-dien</li></ul></li><li><i>(pn)</i> <ul><li>daar</li><li>ziedaar</li><li>zie (je wel)</li><li>alstublieft</li><li>voilà</li></ul></li><li><i>(pn)</i> <ul><li>goed zo!</li><li>tsa</li><li>pak ze</li><li>hallali</li><li>hup {tegen afgericht dier enz.}</li></ul></li></ol></div>',
    },
    {
        "source": "MyNote",
        "term": "一つ",
        "raw": '<div style="text-align: left;"><ol><li><i>(adj-no)</i> <ul><li>één</li><li>eentje</li><li>{per} stuk</li></ul></li><li><i>(adj-no)</i> <ul><li>enkel ~</li><li>slechts ~</li><li>alleen ~</li><li>{met negatie} zelfs geen ~ {suffix dat het voorafgaande substantief beperkt of beklemtoont}</li></ul></li><li><i>(adj-no)</i> <ul><li>één en hetzelfde</li><li>één en dezelfde</li><li>een geheel</li><li>iets eenders</li></ul></li><li><i>(adj-no)</i> <ul><li>ten eerste</li><li>primo</li><li>om te beginnen {gebruikt bij het opgeven van één uit meerdere alternatieven}</li></ul></li><li><i>(adj-no)</i> <ul><li>eens (even)</li><li>eens (effen)</li></ul></li><li><i>(adj-no)</i> <ul><li>alstublieft</li><li>alsjeblieft {als lichte aandrang bij een verzoek}</li></ul></li></ol></div>',
    },
    {
        "source": "MyNote",
        "term": "一",
        "raw": '<div style="text-align: left;"><i>(n)</i> een</div>',
    },
    {
        "source": "MyNote",
        "term": "二",
        "raw": '<div style="text-align: left;"><ol><li><i>(num)</i> <ul><li>twee</li><li>{Barg.} beis</li></ul></li><li><i>(num)</i> tweede</li><li><i>(num)</i> <ul><li>twee</li><li>tweede</li><li>volgende</li></ul></li><li><i>(num)</i> <ul><li>{ritsuryō} vice-minister voor Dazaifu 大宰府</li><li>(a) formele Chinese cijfervariant van 二</li><li>(b) verraden</li></ul></li></ol></div>',
    },
    # --- Yuuna ---
    {
        "source": "Yuuna",
        "term": "表裏",
        "raw": "【旺文社国語辞典 第十一版 画像無し】\nひょう‐り【表裏】ヘウ――\n🈩 （名）おもてとうら。外面と内心。\n🈔 （名・自スル）人前での言葉や態度と内心とが相違すること。「―のある人物」「言行が―する」\n\n【新明解国語辞典　第七版】\nひょう（オ） り ① ヘウ─【表裏】\n㊀ おもてと うら。\n「━の無い〔=他人の見ている時と そうでない時とで食い違ったことをしない〕人」\n「━をなす〔=⇨表裏一体〕」\n㊁ ━する（自サ）\n〔意味の実質は「裏」にある〕裏。\n「人の心の━を読む」\n「社会の━に通じる」\n\n━いったい ① ⓪ 【─一体】\n現象的には△無関係（二つの異なる方向を志向しているか）に見えるものが、根底においては一つにつながっていると考えられること。\n「刑事責任の問題と━である賠償問題」\n「核兵器の開発と━をなす原子力の商業利用の歴史」\n「笑いと涙が━となっている新国劇」\n\n【KireiCake】\n• two sides\n• inside and outside",
    },
    {
        "source": "Yuuna",
        "term": "土砂",
        "raw": "【旺文社国語辞典 第十一版 画像無し】\nど‐しゃ【土砂】\n土と砂。「―崩れ」\n\n【新明解国語辞典　第七版】\nど しゃ ① 【土砂】\n土と砂。\n「━崩れ ③ 」\n\n【JMdict Extra】\n• sediment\n• earth and sand\n\n【KireiCake】\nearth and sand",
    },
    {
        "source": "Yuuna",
        "term": "買手",
        "raw": "【旺文社国語辞典 第十一版 画像無し】\nかい‐て【買手】カヒ――\n物を買うほうの人。買い主。⇔売手\n\n【新明解国語辞典　第七版】\nかい て ⓪ カヒ─【買（い）手】\nその商品を買う人。↔売り手\n\n━しじょう（オ） ④ ─ヂヤウ【─市場】\n需要が供給より少なかったり 品がだぶついていたり して、買手の側が有利な立場に在ること。↔売り手市場\n\n【JMdict Extra】\n• 買い手（★）\n• 買手（★）\n\n【KireiCake】\nbuyer",
    },
    # --- Kanji Study ---
    {
        "source": "KanjiStudy",
        "term": "有りそう",
        "raw": "あり[,ある;k1] そう[;h,a] 【有[あ,ある;k1]り そう[;h,a] 】{<br>probable, likely}",
    },
    {
        "source": "KanjiStudy",
        "term": "気合",
        "raw": "き[,くる;k1] あい[,あう;k1] 【気合[きあい;h] 】{ ★★★<br>}き  あい[;a]  —あ ひ[,ひる;k1]  [0]【気合[きあい;h] 】<br>①ある[,ある;k1] こと[;o] に 精神[せいしん;a] を 集中[しゅうちゅう;h] し[,する;h]て かかる[,かかる;k2] とき[;o] の 気持[きも;h]ち の 勢[いきお;n3]い 。また[;h] ，それ[;a] を 表[あらわ,あらわす;k3]す かけ声[かけごえ;n3] 。「━を かける[,かける;k2] 」「━が 入[はい,はいる;k1]る 」<br>②物事[ものごと;n2] を 行[おこな,おこなう;h]う とき[;o] のこ つ 。また[;h] ，互[たが;h]い の 間[ま;h] の 気分[きぶん;a] 。息[いき;a] 。呼吸[こきゅう;h] 。「━が 合[あ,あう;k1]う 」<br>③気分[きぶん;a] 。こころ[;o,n2] もち[;a] 。「お ━は いかが[;n2] ／浮世草子[うきよぞうし;n4] ・好色五人女[こうしょくごにんおんな;n8,hn4] 2」<br><br>き[,くる;k1] あい[,あう;k1] 【気合[きあい;h] 】{ ★★★<br>1.  (fighting) spirit, motivation, effort<br>2.  shout (for getting in the right mood to deal with something, etc.), cheer, yell<br>3.  kiai, short shout when performing an attacking move}",
    },
    {
        "source": "KanjiStudy",
        "term": "私立",
        "raw": "しり[,しる;o] つ 【私立[しりつ;a] 】{ ★★★<br>}し[,する;h]  りつ  [1]【私立[しりつ;a] 】<br>（名[めい] ）スル<br>①個人[こじん;a] や 法人[ほうじん;h] が 設立[せつりつ;h] し[,する;h] 経営[けいえい;h] し[,する;h]て いる[,いる;h] こと[;o] 。また[;h] ，そのもの[;o,n2] 。特[とく;a]に ，私立学校[しりつがっこう;n4] を いう[,いう;o] こと[;o] が 多[おお,おおい;k1,k2]い 。わたくし[;h] りつ 。→国立[こくりつ;h] ・公立[こうりつ;h] <br>②自分[じぶん;h] の 力[ちから;o] で やっ[,やる;h]て いく[,いく;h] こと[;o] 。「官[かん;a] に 在[ざい;a] て 事[こと;o] を 為[な,なす;k1]す と ，其範囲[はんい;a] を 脱[だっ,だっす]して ━する[,する;h] と の 利害[りがい;a] 得失[とくしつ;h] を 述[の,のべる;k2]べ ／学問[がくもん;n2] ノススメ諭吉[ゆきち;h] 」<br><br>し[,する;h] りつ 【私立[しりつ;a] 】{ ★★★<br>private (establishment)}",
    },
    # --- Kinomoto ---
    {
        "source": "Kinomoto",
        "term": "異変",
        "raw": "【デジタル大辞泉】<br>い‐へん【異変】<br>［名・形動］<br>① 普段は見られない異常な現象・出来事。変事。「政界に異変が起こる」「暖冬異変」<br>② 普通と異なり変わっていること。また、そのさま。「病状に異変を来す」<br>「どうも―な声がらだ。人間の音 (おん) でねえやうだ」〈滑・和合人・三〉<br><br>【旺文社国語辞典 第十一版 画像無し】<br>い‐へん【異変】<br>① 変わること。変化。「病状に―はない」<br>② ふつうとは異なったできごと。「暖冬―」<br><br>【NEW斎藤和英大辞典】<br>いへん〔異変〕<br>〈名〉An accident; a mishap; a disaster; a calamity; a catastrophe; a cataclysm<br>0.  (到着が遅いから)途中で何か異変があったのではないかと心配しておった<br>0.  I feared lest there should have been some disaster on the road.<br><br>【研究社　新和英大辞典　第５版】<br>いへん【異変】 [ローマ字](ihen)<br>〔事故〕 an accident; 〔変事〕 a strange [an extraordinary, a dramatic] event [occurrence]; something strange 《happens》; a disaster; a calamity; 〔非常事態〕 an emergency 《occurs》; 《be in》 a state of emergency; 〔異常現象〕 a strange [an unusual, a remarkable] phenomenon [occurrence]; 〔変化〕 a change (for the worse).<br>►太陽の異変　an extraordinary solar phenomenon<br>・天候の異変　an unusual change in the weather; unusual weather<br>・財界の異変　a disturbance in the financial world<br>・日本国内の異変　a dramatic ┏event [incident] in Japan.<br>►政界に異変が起きた.　There was ┏an upheaval [a commotion] in political circles.<br>・夫の身にきっと何か異変が起きたに違いない.　I'm sure something has [Something must have] happened to my husband. ｜ My husband must have had an accident of some sort.<br>・今, ファッションの世界に異変が起きている.　A major change is taking place in the world of fashion.<br>►地鳴りは異変の前ぶれだ.　A rumbling of the earth is a warning that something strange is going to happen.<br>►彼は病状に異変を来した.　There has been an alarming and unexpected change in his condition. ｜ His condition has suddenly changed for the worse.<br>►彼女の病状に異変はない.　Her condition is unchanged. ｜ There has been no change in her condition.<br>◨地域的連続異変　【生物】 a cline.",
    },
    {
        "source": "Kinomoto",
        "term": "綿密",
        "raw": "【デジタル大辞泉】<br>めん‐みつ【綿密】<br>［名・形動］詳しく細かいこと。すみずみまで注意が行き届いていること。また、そのさま。「綿密な調査」「綿密に計画を立てる」<br>[派生]めんみつさ［名］<br><br>【旺文社国語辞典 第十一版 画像無し】<br>めん‐みつ【綿密】<br>（名・形動ダ）細かいところまで注意が行き届いていること。また、そのさま。念入り。「―な計画を立てる」<br><br>【NEW斎藤和英大辞典】<br>めんみつ〔綿密〕<br>〈形〉Minute (observation); nice (observer); close (examination); thorough (care); scrupulous(attention); elaborate(workmanship)<br>0.  綿密な観察<br>0.  minute observation―nice observation―close observation<br>0.  綿密な注意<br>0.  close attention―thorough care―scrupulous care<br>0.  綿密な計算<br>0.  minute calculation<br>0.  綿密な検査<br>0.  a close examination<br>0.  綿密な調査<br>0.  a thorough investigation<br>0.  綿密に調査する<br>0.  to investigate a matter thoroughly―probe a matter to the bottom<br>0.  綿密な細工<br>0.  minute workmanship―elaborate workmanship<br>0.  彼は頭が綿密だ<br>0.  He has a closely-thinking head.<br>0.  彼は綿密な人だ<br>0.  He is a closely-thinking man―a nicely-observing man―a scrupulous man.<br>0.  綿密過ぎる<br>0.  to be overminute―minute to excess―to split hairs<br>0.  この本は綿密過ぎるのきらいがある<br>0.  The book has the fault of over-minuteness.<br><br>【研究社　新和英大辞典　第５版】<br>めんみつ【綿密】 [ローマ字](menmitsu)<br>1 〔細密〕 〜な minute; nice; close; detailed.<br>►綿密な観察　minute observation<br>・綿密な調査　a close investigation; a searching inquiry<br>・綿密な推理　close reasoning<br>・綿密な分析　an in-depth analysis.<br>2 〔周到〕 〜な careful; scrupulous; meticulous; sedulous; elaborate.<br>►綿密な計画を立てる　make a detailed plan.<br>綿密さ　minuteness; thoroughness.<br>綿密に　〔細密に〕 minutely; closely; in detail; thoroughly; 〔周到に〕 carefully; scrupulously; elaborately.<br>►綿密に捜査する　make a narrow search<br>・綿密にメモを取る　take notes ┏in detail [thoroughly].",
    },
    {
        "source": "Kinomoto",
        "term": "自戒",
        "raw": "【デジタル大辞泉】<br>じ‐かい【自戒】<br>［名］(スル)自分の言動を自分でいましめ慎むこと。「おごり高ぶらないように自戒する」<br><br>【旺文社国語辞典 第十一版 画像無し】<br>じ‐かい【自戒】<br>（名・自スル）自分自身を戒めて気をつけること。「自重―する」<br><br>【研究社　新和英大辞典　第５版】<br>じかい５【自戒】 [ローマ字](jikai)<br>remonstrating with oneself; self-castigation; self-reproach; self-remonstrance.<br>〜する caution oneself 《against…》.",
    },
    {
        "source": "Kinomoto",
        "term": "裕福",
        "raw": "【デジタル大辞泉】<br>ゆう‐ふく【裕福】<br>［名・形動］財産や収入がゆたかで生活に余裕があること。また、そのさま。富裕。「裕福な家庭」「裕福に暮らす」<br>[派生]ゆうふくさ［名］<br><br>【旺文社国語辞典 第十一版 画像無し】<br>ゆう‐ふく【裕福】<br>（名・形動ダ）経済的にゆとりがあって、生活が豊かなこと。また、そのさま。富裕。「―な生活」<br><br>【NEW斎藤和英大辞典】<br>ゆうふく〔裕福〕<br>〈形〉Wealthy; affluent; opulent; warm; well-to-do<br>0.  裕福な連中だ<br>0.  They are a warm set.<br>0.  彼は裕福に暮らしている<br>0.  He is in affluent circumstances―well off.<br>0.  裕福な商人だ<br>0.  He is a well-to-do merchant.<br>0.  裕福な家に生まれた<br>0.  He was born rich―born to wealth―born with a silver spoon in his mouth.<br>0.  裕福な家庭に育った<br>0.  He grew up lapped in luxury.<br><br>【研究社　新和英大辞典　第５版】<br>ゆうふく【裕福】 [ローマ字](yūfuku)<br>〜な rich; wealthy; affluent; opulent; well-to-do; well-off; well-fixed; comfortably off; prosperous; 《口》 well-heeled.<br>►裕福な人　a ┏well-off [well-to-do] person; a person in ┏easy [affluent] circumstances; a person of ┏ample [easy] means<br>・裕福な農家　a ┏wealthy [substantial] farmer<br>・裕福な国　a wealthy nation<br>・裕福な家に生まれる　be born ┏rich [to wealth]; be born in a ┏rich [well-to-do] family; be born with a silver spoon<br>・裕福な家庭に育つ　grow up lapped in luxury; be reared in affluence<br>・あまり裕福でない　be in moderate circumstances.<br>►彼は裕福だ.　He is ┏well-off [comfortably off].<br>・今の彼は数年前に比べずっと裕福だ.　He is far better off now than some years ago.<br>裕福さ　affluence; opulence; prosperity; easy ┏means [circumstances].<br>裕福に<br>►裕福に暮らす　live well; be ┏well-off [comfortably off]; live in ┏easy [affluent] circumstances; live in ┏opulence [abundance, affluence, plenty]<br>・裕福になる　grow rich; come to wealth.",
    },
]


# ---------------------------------------------------------------------------
# Main: print BEFORE / AFTER for every sample
# ---------------------------------------------------------------------------

def _divider(title: str):
    print("=" * 72)
    print(f"  {title}")
    print("=" * 72)

def _truncate(s: str, n: int = 300) -> str:
    return s if len(s) <= n else s[:n] + f"… [+{len(s)-n} chars]"

def main():
    current_source = None
    for sample in SAMPLES:
        src = sample["source"]
        if src != current_source:
            _divider(f"SOURCE: {src}")
            current_source = src

        print(f"\n--- term: {sample['term']} ---")
        raw = sample["raw"]
        result = clean(raw)
        fmt = result.get("format", "?")

        print(f"FORMAT DETECTED: {fmt}")
        print(f"\nBEFORE ({len(raw)} chars):")
        print(_truncate(raw))
        print(f"\nAFTER — plain ({len(result['plain'])} chars):")
        print(result["plain"] or "(empty)")
        print(f"\nAFTER — html ({len(result['html'])} chars):")
        print(result["html"] or "(empty)")
        print()

if __name__ == "__main__":
    main()
