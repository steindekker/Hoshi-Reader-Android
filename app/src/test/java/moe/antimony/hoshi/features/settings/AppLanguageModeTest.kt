package moe.antimony.hoshi.features.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class AppLanguageModeTest {
    @Test
    fun systemModeUsesEmptyLanguageTags() {
        assertEquals("", AppLanguageMode.System.languageTags)
    }

    @Test
    fun explicitModesUseSupportedLanguageTags() {
        assertEquals("en-US", AppLanguageMode.English.languageTags)
        assertEquals("zh-CN", AppLanguageMode.SimplifiedChinese.languageTags)
    }

    @Test
    fun emptyLanguageTagsMapToSystemMode() {
        assertEquals(AppLanguageMode.System, AppLanguageMode.fromLanguageTags(""))
        assertEquals(AppLanguageMode.System, AppLanguageMode.fromLanguageTags(" "))
    }

    @Test
    fun supportedLanguageTagsMapToExplicitModes() {
        assertEquals(AppLanguageMode.English, AppLanguageMode.fromLanguageTags("en-US"))
        assertEquals(AppLanguageMode.SimplifiedChinese, AppLanguageMode.fromLanguageTags("zh-CN"))
    }

    @Test
    fun unknownLanguageTagsMapToSystemMode() {
        assertEquals(AppLanguageMode.System, AppLanguageMode.fromLanguageTags("de-DE"))
    }
}
