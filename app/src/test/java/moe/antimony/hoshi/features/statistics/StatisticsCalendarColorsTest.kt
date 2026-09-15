package moe.antimony.hoshi.features.statistics

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import moe.antimony.hoshi.ui.theme.hoshiColorScheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StatisticsCalendarColorsTest {
    @Test
    fun selectedRangeSummaryColorsKeepLightEInkTextReadable() {
        val scheme = hoshiColorScheme(darkTheme = false, eInkMode = true)
        val colors = statisticsSelectedRangeSummaryColors(scheme, eInkMode = true)

        assertEquals(1f, colors.container.alpha, 0.0f)
        assertContrastAtLeast(colors.content, colors.container.compositeOver(scheme.surface), 7.0)
    }

    @Test
    fun selectedRangeSummaryColorsKeepDarkEInkTextReadable() {
        val scheme = hoshiColorScheme(darkTheme = true, eInkMode = true)
        val colors = statisticsSelectedRangeSummaryColors(scheme, eInkMode = true)

        assertEquals(1f, colors.container.alpha, 0.0f)
        assertContrastAtLeast(colors.content, colors.container.compositeOver(scheme.surface), 7.0)
    }

    @Test
    fun selectedRangeSummaryColorsKeepSoftContainerOutsideEInkMode() {
        val scheme = hoshiColorScheme(darkTheme = false, eInkMode = false)
        val colors = statisticsSelectedRangeSummaryColors(scheme, eInkMode = false)

        assertEquals(0.28f, colors.container.alpha, 0.002f)
        assertEquals(scheme.onPrimaryContainer, colors.content)
    }

    private fun assertContrastAtLeast(foreground: Color, background: Color, minimum: Double) {
        val contrast = contrastRatio(foreground, background)
        assertTrue("Expected contrast >= $minimum but was $contrast", contrast >= minimum)
    }

    private fun contrastRatio(first: Color, second: Color): Double {
        val lighter = maxOf(first.luminance(), second.luminance()).toDouble()
        val darker = minOf(first.luminance(), second.luminance()).toDouble()
        return (lighter + 0.05) / (darker + 0.05)
    }

    private fun Color.compositeOver(background: Color): Color {
        val alpha = alpha + background.alpha * (1f - alpha)
        if (alpha == 0f) return Color.Transparent
        return Color(
            red = (red * this.alpha + background.red * background.alpha * (1f - this.alpha)) / alpha,
            green = (green * this.alpha + background.green * background.alpha * (1f - this.alpha)) / alpha,
            blue = (blue * this.alpha + background.blue * background.alpha * (1f - this.alpha)) / alpha,
            alpha = alpha,
        )
    }
}
