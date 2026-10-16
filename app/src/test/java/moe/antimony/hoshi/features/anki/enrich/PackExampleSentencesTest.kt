package moe.antimony.hoshi.features.anki.enrich

import moe.antimony.hoshi.features.anki.ExampleSentence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PackExampleSentencesTest {
    @Test
    fun renderedBlockBecomesPlainSentencesWithTheKeywordHighlighted() {
        val html = """<details class="aa-ex"><summary>例文</summary><div class="aa-ex-list">""" +
            """<div class="aa-ex-item"><span class="aa-ex-jp" lang="ja">あなたのサイトは、<b class="aa-ex-kw"><ruby>猫<rt>ねこ</rt></ruby></b>に<ruby>興<rt>きょう</rt></ruby><ruby>味<rt>み</rt></ruby>のある人達にとって魅力的です。</span><span class="aa-ex-en">Your site appeals to people who are interested in cats.</span></div>""" +
            """<div class="aa-ex-item"><span class="aa-ex-jp" lang="ja"><b class="aa-ex-kw">ネコ</b>が電線を渡る &amp; 鳴く。</span></div>""" +
            "</div></details>"

        assertEquals(
            listOf(
                ExampleSentence("あなたのサイトは、猫に興味のある人達にとって魅力的です。", listOf(9 until 10)),
                ExampleSentence("ネコが電線を渡る & 鳴く。", listOf(0 until 2)),
            ),
            parsePackExamples(html),
        )
    }

    @Test
    fun emptyOrForeignMarkupYieldsNothing() {
        assertTrue(parsePackExamples("").isEmpty())
        assertTrue(parsePackExamples("<div>例文</div>").isEmpty())
    }
}
