package moe.antimony.hoshi.features.statistics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowCircleDown
import androidx.compose.material.icons.rounded.ArrowCircleUp
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import moe.antimony.hoshi.R
import moe.antimony.hoshi.ui.theme.LocalHoshiEInkMode

@Composable
internal fun StatisticsReadingTimeSection(
    currentRange: CurrentRangeStatisticsUi,
    today: java.time.LocalDate,
    onEvent: (StatisticsEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val onSelectBucket: (String?) -> Unit = { onEvent(StatisticsEvent.SelectTrendBucket(it)) }
    val pager = rememberStatisticsChartPagerState(currentRange) { onEvent(StatisticsEvent.SelectPeriodPage(it)) }
    val summary = currentRange.summary
    val selectedBucket = currentRange.selectedBucket
    val showTotal = selectedBucket != null
    val rangeTitleStyle = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp, fontWeight = FontWeight.Normal)
    // Reserve the unscaled title's line height even when a longer date needs smaller text.
    val rangeTitleHeight = with(LocalDensity.current) { maxOf(32.dp, rangeTitleStyle.lineHeight.toDp()) }
    StatisticsSection(
        title = stringResource(R.string.statistics_reading_time),
        modifier = modifier,
    ) {
        StatisticsSegmentedControl(
            options = StatisticsRangeMode.entries.map { StatisticsSegmentedOption(it, rangeModeLabel(it)) },
            selected = currentRange.mode,
            onSelect = { if (it != currentRange.mode) onEvent(StatisticsEvent.SelectRangeMode(it)) },
        )
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = rangeTitleHeight),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = selectedBucket?.let { statisticsBucketTitle(currentRange.mode, it) } ?: stringResource(
                    when (currentRange.mode) {
                        StatisticsRangeMode.Week, StatisticsRangeMode.Month -> R.string.statistics_period_daily_average_format
                        StatisticsRangeMode.Year, StatisticsRangeMode.All -> R.string.statistics_period_monthly_average_format
                    },
                    statisticsCompactRangeTitle(currentRange, today),
                ),
                modifier = Modifier.weight(1f),
                style = rangeTitleStyle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                autoSize = TextAutoSize.StepBased(minFontSize = 14.sp, maxFontSize = 20.sp, stepSize = 1.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (currentRange.mode != StatisticsRangeMode.All) {
                StatisticsPeriodNavigation(pager, currentRange.selectedPage)
            }
        }
        Spacer(Modifier.height(4.dp))
        val change = currentRange.periodChangePercent?.takeIf { selectedBucket == null }
        StatisticsReadingTimeHeadline(
            duration = formatStatisticsDuration(if (showTotal) summary.readingSeconds else summary.averageReadingSecondsPerBucket),
            change = change,
            comparison = change?.let {
                stringResource(
                    when (currentRange.mode) {
                        StatisticsRangeMode.Week -> R.string.statistics_average_change_week_format
                        StatisticsRangeMode.Month -> R.string.statistics_average_change_month_format
                        else -> R.string.statistics_average_change_year_format
                    },
                    formatStatisticsGroupedCount(abs(it).roundToInt()),
                )
            },
        )
        Spacer(Modifier.height(12.dp))
        StatisticsChartPager(
            range = currentRange,
            pager = pager,
            today = today,
            onSelectBucket = onSelectBucket,
        )
        Spacer(Modifier.height(12.dp))
        StatisticsSummaryRow(stringResource(R.string.statistics_characters_read), formatStatisticsGroupedCount(summary.totalCharacters))
        StatisticsSummaryRow(stringResource(R.string.statistics_average_speed), stringResource(R.string.statistics_speed_value_format, formatStatisticsGroupedCount(summary.averageSpeedPerHour)))
        if (!showTotal) {
            StatisticsSummaryRow(stringResource(R.string.statistics_total_time), formatStatisticsDuration(summary.readingSeconds))
        }
    }
}

@Composable
private fun StatisticsPeriodNavigation(pager: PagerState, selectedPage: Int) = key(pager) {
    val scope = rememberCoroutineScope()
    val eInk = LocalHoshiEInkMode.current
    var navigationRequested by remember { mutableStateOf(false) }
    val ready = !navigationRequested && !pager.isScrollInProgress && pager.settledPage == selectedPage
    fun navigate(offset: Int) {
        val target = pager.settledPage + offset
        if (!ready || navigationRequested || target !in 0 until pager.pageCount) return
        navigationRequested = true
        scope.launch {
            try {
                if (eInk) pager.scrollToPage(target) else pager.animateScrollToPage(target)
            } finally {
                navigationRequested = false
            }
        }
    }
    Row {
        IconButton(
            onClick = { navigate(-1) },
            enabled = ready && pager.settledPage > 0,
            modifier = Modifier.size(32.dp).testTag("statistics_previous_period"),
        ) {
            Icon(Icons.Rounded.ChevronLeft, contentDescription = null, modifier = Modifier.size(20.dp))
        }
        IconButton(
            onClick = { navigate(1) },
            enabled = ready && pager.settledPage < pager.pageCount - 1,
            modifier = Modifier.size(32.dp).testTag("statistics_next_period"),
        ) {
            Icon(Icons.Rounded.ChevronRight, contentDescription = null, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun StatisticsReadingTimeHeadline(duration: String, change: Double?, comparison: String?) {
    val durationStyle = MaterialTheme.typography.headlineLarge.copy(fontSize = 38.sp, fontWeight = FontWeight.Normal)
    val comparisonStyle = MaterialTheme.typography.bodyMedium
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val comparisonIconSize = with(density) { 18.sp.toDp() }
    val comparisonSpacing = 4.dp
    val headlineSpacing = 10.dp
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val minimumDurationWidth = measurer.measure(duration, durationStyle.copy(fontSize = 22.sp), softWrap = false).size.width
        val durationHeight = measurer.measure(duration, durationStyle, softWrap = false).size.height
        val comparisonTextWidth = comparison?.let { measurer.measure(it, comparisonStyle, softWrap = false).size.width } ?: 0
        val comparisonWidth = with(density) {
            minOf(
                comparisonTextWidth + (comparisonIconSize + comparisonSpacing).toPx(),
                (constraints.maxWidth - minimumDurationWidth - headlineSpacing.toPx()).coerceAtLeast(0f),
            ).toDp()
        }
        // Keep the full-size headline's height as its values resize, so paging never moves the plot.
        Row(
            Modifier.fillMaxWidth().heightIn(min = with(density) { durationHeight.toDp() }),
            horizontalArrangement = Arrangement.spacedBy(headlineSpacing),
        ) {
            Text(duration, Modifier.weight(1f).alignByBaseline(), style = durationStyle,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                autoSize = TextAutoSize.StepBased(minFontSize = 22.sp, maxFontSize = 38.sp, stepSize = 1.sp))
            if (comparison != null && change != null) {
                Row(
                    Modifier.width(comparisonWidth).alignByBaseline(),
                    horizontalArrangement = Arrangement.spacedBy(comparisonSpacing),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(if (change >= 0) Icons.Rounded.ArrowCircleUp else Icons.Rounded.ArrowCircleDown,
                        contentDescription = null, tint = color, modifier = Modifier.size(comparisonIconSize))
                    Text(comparison, style = comparisonStyle, color = color,
                        modifier = Modifier.weight(1f).alignByBaseline(), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        autoSize = TextAutoSize.StepBased(minFontSize = 12.sp, maxFontSize = comparisonStyle.fontSize, stepSize = 1.sp))
                }
            }
        }
    }
}

@Composable
private fun StatisticsSummaryRow(label: String, value: String) {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Text(value, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun statisticsBucketTitle(mode: StatisticsRangeMode, bucket: StatisticsDateRange): String =
    if (mode == StatisticsRangeMode.Year || mode == StatisticsRangeMode.All) {
        rangeTitle(StatisticsRangeMode.Month, bucket)
    } else {
        formatStatisticsDate(bucket.start)
    }

@Composable
private fun statisticsCompactRangeTitle(current: CurrentRangeStatisticsUi, today: java.time.LocalDate): String {
    if (current.mode != StatisticsRangeMode.Week) return rangeTitle(current.mode, current.range)
    val pattern = stringResource(R.string.statistics_history_short_date_pattern)
    return stringResource(
        R.string.statistics_date_interval_format,
        formatStatisticsHistoryDate(current.range.start, today, pattern),
        formatStatisticsHistoryDate(current.range.end, today, pattern),
    )
}

@Composable
internal fun rangeTitle(mode: StatisticsRangeMode, range: StatisticsDateRange): String = when (mode) {
    StatisticsRangeMode.Year -> stringResource(R.string.statistics_range_fixed_year_format, range.start.year)
    StatisticsRangeMode.All -> stringResource(R.string.statistics_range_all_time)
    StatisticsRangeMode.Month -> {
        val pattern = stringResource(R.string.statistics_range_month_title_pattern)
        val locale = LocalConfiguration.current.locales[0]
        val formatter = remember(pattern, locale) { DateTimeFormatter.ofPattern(pattern, locale) }
        formatter.format(range.start)
    }
    StatisticsRangeMode.Week -> stringResource(R.string.statistics_date_interval_format, formatStatisticsDate(range.start), formatStatisticsDate(range.end))
}

@Composable
internal fun rangeModeLabel(mode: StatisticsRangeMode): String = stringResource(when (mode) {
    StatisticsRangeMode.Week -> R.string.statistics_range_week
    StatisticsRangeMode.Month -> R.string.statistics_range_month
    StatisticsRangeMode.Year -> R.string.statistics_range_year
    StatisticsRangeMode.All -> R.string.statistics_range_all
})
