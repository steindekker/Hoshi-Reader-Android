package moe.antimony.hoshi.features.anki.enrich.jrp

import org.junit.Assert.assertEquals
import org.junit.Test

class JrpMarkupTest {
    @Test
    fun jrpSurface() {
        assertEquals("異変", JrpMarkup.jrpSurface("{[異変|いへん];0}"))
        assertEquals("一つ", JrpMarkup.jrpSurface("{[一|ひと]つ;2}"))
        assertEquals("気付いた", JrpMarkup.jrpSurface("{[気付|きづ][いた=く];Y2}"))
        assertEquals("ください", JrpMarkup.jrpSurface("{くださ[い=る];Y3}"))
        assertEquals("それ", JrpMarkup.jrpSurface("{それ;1}"))
        assertEquals("一", JrpMarkup.jrpSurface("一"))
        assertEquals("それ", JrpMarkup.jrpSurface("それ"))
    }

    @Test
    fun jrpSurfaceUsesPythonWhitespaceForRubyBase() {
        // Python `\s` includes NBSP and U+001C, so they delimit the ruby base
        assertEquals("漢 字", JrpMarkup.jrpSurface("漢 字[かんじ]"))
        assertEquals("漢\u001c字", JrpMarkup.jrpSurface("漢\u001c字[かんじ]"))
        assertEquals("<漢", JrpMarkup.jrpSurface("<漢[かん]"))
        assertEquals("漢]", JrpMarkup.jrpSurface("漢[[かん]]"))
        assertEquals("漢[かん]字", JrpMarkup.jrpSurface(" 漢 [かん] 字"))
        assertEquals("漢", JrpMarkup.jrpSurface("　{[漢|かん];1}"))
    }

    @Test
    fun jrpToFurigana() {
        assertEquals("異変[いへん]", JrpMarkup.jrpToFurigana("{[異変|いへん];0}"))
        assertEquals("一[ひと]つ", JrpMarkup.jrpToFurigana("{[一|ひと]つ;2}"))
        assertEquals("気付[きづ]いた", JrpMarkup.jrpToFurigana("{[気付|きづ][いた=く];Y2}"))
        assertEquals("一", JrpMarkup.jrpToFurigana("一"))
        assertEquals("気付[きづ]いた", JrpMarkup.jrpToFurigana("{[気付|きづ];0}{[いた=く];Y2}"))
    }

    @Test
    fun jrpToFuriganaEdgeCases() {
        assertEquals("一[ひと]つ;2", JrpMarkup.jrpToFurigana("{[一|ひと]つ;2;3}")) // only the last ;… goes
        assertEquals("a", JrpMarkup.jrpToFurigana("{a;b\n}"))
        assertEquals("漢[かん]", JrpMarkup.jrpToFurigana("　{[漢|かん];1} ")) // Python strip()
        assertEquals("a", JrpMarkup.jrpToFurigana("[a=b=c]"))
        assertEquals("[|x]", JrpMarkup.jrpToFurigana("[|x]"))
        assertEquals("", JrpMarkup.jrpToFurigana("[=x]"))
        assertEquals("x", JrpMarkup.jrpToFurigana("{x}"))
        assertEquals("a[b]c", JrpMarkup.jrpToFurigana("{[a|b]}c "))
    }

    @Test
    fun stripSentenceHtmlUnwrapsInlineTags() {
        // <b> etc. are unwrapped (contents kept), JRP markup left intact
        assertEquals(
            "この{[果|は]てし;0,3}ない{[旅路|たびじ];0,2}を。",
            JrpMarkup.stripSentenceHtml("この{[果|は]てし;0,3}ない<b>{[旅路|たびじ];0,2}</b>を。"),
        )
    }

    @Test
    fun stripSentenceHtmlDropsRtWithContent() {
        // <rt> holds a duplicate reading; tag AND its text must go, base kept
        assertEquals("{[今度|こんど];1}は", JrpMarkup.stripSentenceHtml("<ruby>{[今度|こんど];1}<rt>こんど</rt></ruby>は"))
        assertEquals("漢字", JrpMarkup.stripSentenceHtml("<ruby>漢<rt>かん</rt>字<rt>じ</rt></ruby>"))
        assertEquals("ad", JrpMarkup.stripSentenceHtml("<RT>x</RT>a<rt\nclass=y>b\nc</rt>d"))
        assertEquals("y", JrpMarkup.stripSentenceHtml("<rt-x>x</rt>y"))
    }

    @Test
    fun stripSentenceHtmlRtNeedsWordBoundary() {
        // `<rt\b`: <rtc>, <rté>, <rt_> are other tags, only unwrapped
        assertEquals("z", JrpMarkup.stripSentenceHtml("<rtc>z</rtc>"))
        assertEquals("xy", JrpMarkup.stripSentenceHtml("<rté>x</rt>y"))
        assertEquals("xy", JrpMarkup.stripSentenceHtml("<rt_>x</rt>y"))
    }

    @Test
    fun stripSentenceHtmlBrBecomesNewline() {
        assertEquals("一\n二\n三\n四", JrpMarkup.stripSentenceHtml("一<br>二<br/>三<br />四"))
        assertEquals("\n一\n二\n三\n四", JrpMarkup.stripSentenceHtml("<BR>一<Br/>二<br\t/>三<br　>四"))
    }

    @Test
    fun stripSentenceHtmlLeavesPlainUntouched() {
        assertEquals("{猫;1}が歩いた", JrpMarkup.stripSentenceHtml("{猫;1}が歩いた"))
        assertEquals("a<1>b<>c", JrpMarkup.stripSentenceHtml("a<1>b<>c"))
    }

    @Test
    fun stripSubtitleLabelsDropsLeadingSpeaker() {
        assertEquals("{カンナ;1}何を", JrpMarkup.stripSubtitleLabels("（{トール;1}）{カンナ;1}何を"))
        assertEquals("{こいつ;0}ら", JrpMarkup.stripSubtitleLabels("（[聖哉|きよちか]）{こいつ;0}ら"))
        assertEquals("{[護送|ごそう];0}", JrpMarkup.stripSubtitleLabels("（マルロ）{[護送|ごそう];0}"))
        assertEquals("はい", JrpMarkup.stripSubtitleLabels("（エレン）　はい"))
        assertEquals("はい", JrpMarkup.stripSubtitleLabels("　（エレン）　はい"))
        // one pass: only the first label at the line start goes
        assertEquals("（二）三", JrpMarkup.stripSubtitleLabels("（一）（二）三"))
    }

    @Test
    fun stripSubtitleLabelsDropsAfterSentenceBreak() {
        assertEquals("言ってみろ！はい", JrpMarkup.stripSubtitleLabels("言ってみろ！（{トール;1}）はい"))
        // ...including across a <br>-derived newline
        assertEquals("一行目\nすいません", JrpMarkup.stripSubtitleLabels("一行目\n（エレン）すいません"))
        assertEquals("。c", JrpMarkup.stripSubtitleLabels("。（a\nb）c"))
        assertEquals("？y", JrpMarkup.stripSubtitleLabels("？ (x) y"))
    }

    @Test
    fun stripSubtitleLabelsLineStartIsNewlineOnly() {
        // Python re.M `^` follows only \n, not \r or U+2028
        assertEquals("a\r（エレン）はい", JrpMarkup.stripSubtitleLabels("a\r（エレン）はい"))
        assertEquals("a （エレン）はい", JrpMarkup.stripSubtitleLabels("a （エレン）はい"))
    }

    @Test
    fun stripSubtitleLabelsNeedsTrailingContent() {
        // NBSP is whitespace for the (?=\S) lookahead but not one of the stripped spaces
        assertEquals("（エレン） はい", JrpMarkup.stripSubtitleLabels("（エレン） はい"))
        assertEquals("（エレン）", JrpMarkup.stripSubtitleLabels("（エレン）"))
    }

    @Test
    fun stripSubtitleLabelsKeepsInlineGlosses() {
        // a gloss FOLLOWS its term (not line-start / sentence-break) -> kept
        val fifa = "{[国際|こくさい]サッカー[連盟|れんめい];9}（ＦＩＦＡ）が"
        assertEquals(fifa, JrpMarkup.stripSubtitleLabels(fifa))
        assertEquals("「イカゲーム」（SquidGame）が", JrpMarkup.stripSubtitleLabels("「イカゲーム」（SquidGame）が"))
        assertEquals("さん（28）は", JrpMarkup.stripSubtitleLabels("さん（28）は")) // age
        assertEquals("{イルカ;0}({[海豚|いるか];0})", JrpMarkup.stripSubtitleLabels("{イルカ;0}({[海豚|いるか];0})"))
    }

    @Test
    fun stripSubtitleLabelsKeepsWholeSentenceAside() {
        // the parenthetical IS the entire line (no content after) -> not a label
        val s = "({ディープ;1}{ステート;2}、{[影|かげ];1}の{[国家|こっか];1}）"
        assertEquals(s, JrpMarkup.stripSubtitleLabels(s))
    }

    @Test
    fun stripSubtitleLabelsNoopWithoutLabels() {
        assertEquals("{猫;1}が歩いた", JrpMarkup.stripSubtitleLabels("{猫;1}が歩いた"))
    }

    @Test
    fun normalizeSpacesFullwidthToAscii() {
        assertEquals("今は そげな話は あとじゃ。", JrpMarkup.normalizeSpaces("今は　そげな話は　あとじゃ。"))
        assertEquals("{[今|いま];1}は {あと;1}じゃ", JrpMarkup.normalizeSpaces("{[今|いま];1}は　{あと;1}じゃ"))
        // no other whitespace touched (ASCII space, newline preserved)
        assertEquals("a b\nc", JrpMarkup.normalizeSpaces("a b\nc"))
        // idempotent
        assertEquals("今は そげな話は", JrpMarkup.normalizeSpaces("今は そげな話は"))
    }

    @Test
    fun pyStripMatchesPythonWhitespace() {
        assertEquals("a", JrpMarkup.pyStrip("\u001c 　  a\u0085 \t"))
        // U+200B (zero width space) and U+FEFF are not whitespace in Python
        assertEquals("​a﻿", JrpMarkup.pyStrip("​a﻿"))
    }
}
