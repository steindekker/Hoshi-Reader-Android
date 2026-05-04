package moe.antimony.hoshi.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class HoshiReaderThemeTest {
    @Test
    fun eInkLightColorSchemeUsesPureBlackAndWhiteWithHighContrastContainers() {
        val scheme = hoshiColorScheme(darkTheme = false, eInkMode = true)

        assertEquals(Color.White, scheme.background)
        assertEquals(Color.White, scheme.surface)
        assertEquals(Color.Black, scheme.onBackground)
        assertEquals(Color.Black, scheme.onSurface)
        assertEquals(Color.Black, scheme.primary)
        assertEquals(Color.White, scheme.onPrimary)
        assertEquals(Color.Black, scheme.secondaryContainer)
        assertEquals(Color.White, scheme.onSecondaryContainer)
        assertEquals(Color.Black, scheme.outline)
        assertEquals(Color.Black, scheme.outlineVariant)
    }

    @Test
    fun eInkDarkColorSchemeUsesPureBlackAndWhiteWithHighContrastContainers() {
        val scheme = hoshiColorScheme(darkTheme = true, eInkMode = true)

        assertEquals(Color.Black, scheme.background)
        assertEquals(Color.Black, scheme.surface)
        assertEquals(Color.White, scheme.onBackground)
        assertEquals(Color.White, scheme.onSurface)
        assertEquals(Color.White, scheme.primary)
        assertEquals(Color.Black, scheme.onPrimary)
        assertEquals(Color.White, scheme.secondaryContainer)
        assertEquals(Color.Black, scheme.onSecondaryContainer)
        assertEquals(Color.White, scheme.outline)
        assertEquals(Color.White, scheme.outlineVariant)
    }

    @Test
    fun eInkModeDisablesDynamicMaterialColor() {
        val source = File("src/main/java/moe/antimony/hoshi/ui/theme/Theme.kt").readText()
        val dynamicBranch = source.substringAfter("val colorScheme = when")
            .substringBefore("else ->")

        assertTrue(dynamicBranch.contains("dynamicColor && !eInkMode"))
        assertFalse(dynamicBranch.contains("dynamicColor && eInkMode"))
    }

    @Test
    fun exposesAppDarkThemeAsCompositionLocal() {
        val source = File("src/main/java/moe/antimony/hoshi/ui/theme/Theme.kt").readText()

        assertTrue(source.contains("val LocalHoshiDarkTheme = staticCompositionLocalOf { false }"))
        assertTrue(source.contains("LocalHoshiDarkTheme provides darkTheme"))
    }
}
