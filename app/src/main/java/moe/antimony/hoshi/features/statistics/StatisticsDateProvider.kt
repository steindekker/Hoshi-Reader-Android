package moe.antimony.hoshi.features.statistics

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

internal interface StatisticsDateProvider {
    fun currentDate(resetMinutes: Int): LocalDate
}

internal class SystemStatisticsDateProvider @Inject constructor() : StatisticsDateProvider {
    override fun currentDate(resetMinutes: Int): LocalDate = statisticsDateAt(
        instant = Instant.now(),
        zoneId = ZoneId.systemDefault(),
        resetMinutes = resetMinutes,
    )
}

internal fun statisticsDateAt(
    instant: Instant,
    zoneId: ZoneId,
    resetMinutes: Int,
): LocalDate = instant
    .atZone(zoneId)
    .minusMinutes(resetMinutes.toLong())
    .toLocalDate()
