package moe.antimony.hoshi.features.live

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import moe.antimony.hoshi.R

/**
 * A small pill that pops in when a card is added from Live: a check that springs in with a
 * short sparkle burst, the mined term, and a haptic tick. It draws over the content without
 * taking touches, then fades out after [CelebrationHoldMillis] and reports [onFinished].
 */
@Composable
internal fun LiveMinedCelebration(
    celebration: LiveCelebration?,
    onFinished: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val currentOnFinished by rememberUpdatedState(onFinished)
    var shown by remember { mutableStateOf<LiveCelebration?>(null) }
    val pill = remember { Animatable(0f) }
    val alpha = remember { Animatable(0f) }
    val check = remember { Animatable(0f) }
    val burst = remember { Animatable(1f) }
    var sparkles by remember { mutableStateOf(true) }

    LaunchedEffect(celebration?.id) {
        val next = celebration ?: return@LaunchedEffect
        sparkles = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
        shown = next
        try {
            pill.snapTo(0f)
            alpha.snapTo(0f)
            check.snapTo(0f)
            burst.snapTo(0f)
            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
            coroutineScope {
                launch { alpha.animateTo(1f, tween(EnterFadeMillis)) }
                launch { pill.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow)) }
                launch {
                    delay(CheckDelayMillis)
                    check.animateTo(1f, spring(dampingRatio = 0.4f, stiffness = Spring.StiffnessMedium))
                }
                if (sparkles) {
                    launch {
                        delay(BurstDelayMillis)
                        burst.animateTo(1f, tween(BurstMillis, easing = LinearOutSlowInEasing))
                    }
                }
            }
            delay(CelebrationHoldMillis)
            coroutineScope {
                launch { alpha.animateTo(0f, tween(ExitMillis)) }
                launch { pill.animateTo(ExitPillValue, tween(ExitMillis)) }
            }
            shown = null
        } finally {
            currentOnFinished(next.id)
        }
    }

    val current = shown ?: return
    val headline = current.term.ifBlank { stringResource(R.string.live_card_added) }
    val subtitle = when {
        current.count > 1 -> pluralStringResource(R.plurals.live_card_added_session_count, current.count, current.count)
        current.term.isNotBlank() -> stringResource(R.string.live_card_added_to_anki)
        else -> null
    }
    val shape = RoundedCornerShape(percent = 50)
    val colors = MaterialTheme.colorScheme
    val sparkleColors = listOf(colors.primary, colors.tertiary)
    val riseOffset = with(density) { EnterRise.toPx() }
    Box(
        modifier = modifier
            .padding(horizontal = 16.dp)
            .widthIn(max = 360.dp)
            .graphicsLayer {
                // pill runs 0 -> 1 with overshoot on entry, 1 -> ExitPillValue on exit.
                val scale = 0.7f + 0.3f * pill.value
                scaleX = scale
                scaleY = scale
                translationY = riseOffset * (1f - pill.value)
                this.alpha = alpha.value.coerceIn(0f, 1f)
            }
            // No clip so the sparkles may spill past the pill's edge.
            .shadow(elevation = 6.dp, shape = shape, clip = false)
            .background(colors.primaryContainer, shape)
            .padding(start = 8.dp, end = 20.dp, top = 8.dp, bottom = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(CheckSize)
                    .drawBehind { if (sparkles && burst.value < 1f) drawSparkleBurst(burst.value, sparkleColors) }
                    .graphicsLayer {
                        scaleX = check.value
                        scaleY = check.value
                    }
                    .background(colors.primary, CircleShape),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = null,
                    tint = colors.onPrimary,
                    modifier = Modifier.size(22.dp),
                )
            }
            Column {
                Text(
                    text = headline,
                    style = MaterialTheme.typography.titleMedium.copy(localeList = CelebrationLocale),
                    fontWeight = FontWeight.SemiBold,
                    color = colors.onPrimaryContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.onPrimaryContainer.copy(alpha = 0.75f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** A ring of dots and four-point stars flying out of the check and fading as [progress] -> 1. */
private fun DrawScope.drawSparkleBurst(progress: Float, colors: List<Color>) {
    val origin = Offset(size.width / 2f, size.height / 2f)
    val start = size.minDimension / 2f + 2.dp.toPx()
    val travel = 14.dp.toPx()
    val fade = 1f - progress * progress
    repeat(SparkleCount) { index ->
        val angle = (index * 2.0 * PI / SparkleCount + PI / SparkleCount).toFloat()
        val distance = start + travel * progress + (if (index % 2 == 0) 3.dp.toPx() else 0f)
        val center = origin + Offset(cos(angle) * distance, sin(angle) * distance)
        val color = colors[index % colors.size].copy(alpha = fade)
        val grow = 1f - 0.5f * progress
        if (index % 2 == 0) {
            drawStar(center, 4.5.dp.toPx() * grow, color)
        } else {
            drawCircle(color, radius = 2.dp.toPx() * grow, center = center)
        }
    }
}

private fun DrawScope.drawStar(center: Offset, radius: Float, color: Color) {
    val inner = radius * 0.32f
    val path = Path().apply {
        moveTo(center.x, center.y - radius)
        lineTo(center.x + inner, center.y - inner)
        lineTo(center.x + radius, center.y)
        lineTo(center.x + inner, center.y + inner)
        lineTo(center.x, center.y + radius)
        lineTo(center.x - inner, center.y + inner)
        lineTo(center.x - radius, center.y)
        lineTo(center.x - inner, center.y - inner)
        close()
    }
    drawPath(path, color)
}

private val CheckSize = 36.dp
private val EnterRise = 16.dp
private val CelebrationLocale = LocaleList("ja")
private const val SparkleCount = 8
private const val EnterFadeMillis = 120
private const val CheckDelayMillis = 70L
private const val BurstDelayMillis = 110L
private const val BurstMillis = 650
private const val CelebrationHoldMillis = 900L
private const val ExitMillis = 200
private const val ExitPillValue = 0.75f
