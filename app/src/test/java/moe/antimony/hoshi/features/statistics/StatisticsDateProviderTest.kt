package moe.antimony.hoshi.features.statistics

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class StatisticsDateProviderTest {
    @Test
    fun timeBeforeConfiguredResetBelongsToPreviousStatisticsDay() {
        val date = statisticsDateAt(
            instant = Instant.parse("2026-06-29T17:44:00Z"),
            zoneId = ZoneId.of("Asia/Shanghai"),
            resetMinutes = 105,
        )

        assertEquals(LocalDate.parse("2026-06-29"), date)
    }

    @Test
    fun configuredResetMinuteStartsNewStatisticsDay() {
        val date = statisticsDateAt(
            instant = Instant.parse("2026-06-29T17:45:00Z"),
            zoneId = ZoneId.of("Asia/Shanghai"),
            resetMinutes = 105,
        )

        assertEquals(LocalDate.parse("2026-06-30"), date)
    }

    @Test
    fun statisticsDateUsesCurrentLocalTimeZone() {
        val instant = Instant.parse("2026-06-29T23:30:00Z")

        assertEquals(
            LocalDate.parse("2026-06-29"),
            statisticsDateAt(instant, ZoneId.of("UTC"), resetMinutes = 60),
        )
        assertEquals(
            LocalDate.parse("2026-06-30"),
            statisticsDateAt(instant, ZoneId.of("Asia/Shanghai"), resetMinutes = 60),
        )
    }
}
