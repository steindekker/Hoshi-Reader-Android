package moe.antimony.hoshi.features.bookshelf

import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import moe.antimony.hoshi.ui.theme.LocalHoshiDarkTheme

internal data class BookCoverArtwork(
    val coverSource: BookCoverSource?,
    val blur: Boolean,
)

internal fun resolveBookCoverArtwork(
    coverMode: BookshelfCoverMode,
    apiLevel: Int,
    coverSource: BookCoverSource?,
): BookCoverArtwork = when {
    coverSource == null -> BookCoverArtwork(coverSource = null, blur = false)
    coverMode == BookshelfCoverMode.Hide -> BookCoverArtwork(coverSource = null, blur = false)
    coverMode == BookshelfCoverMode.Blur && apiLevel < Build.VERSION_CODES.S ->
        BookCoverArtwork(coverSource = null, blur = false)
    coverMode == BookshelfCoverMode.Blur -> BookCoverArtwork(coverSource = coverSource, blur = true)
    else -> BookCoverArtwork(coverSource = coverSource, blur = false)
}

internal fun coverFallbackHash(title: String): ULong =
    title.encodeToByteArray().fold(FnvOffsetBasis) { hash, byte ->
        (hash xor byte.toUByte().toULong()) * FnvPrime
    }

@Composable
internal fun BookCoverCard(
    title: String,
    author: String?,
    coverSource: BookCoverSource?,
    coverMode: BookshelfCoverMode,
    modifier: Modifier = Modifier,
) {
    val outerShape = RoundedCornerShape(7.dp)
    val innerShape = RoundedCornerShape(6.dp)
    val coverContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f)
    val coverBorderColor = if (LocalHoshiDarkTheme.current) {
        Color.White.copy(alpha = 0.18f)
    } else {
        Color.Black.copy(alpha = 0.06f)
    }
    val artwork = resolveBookCoverArtwork(coverMode, Build.VERSION.SDK_INT, coverSource)

    Box(
        modifier = Modifier
            .then(modifier)
            .fillMaxWidth()
            .aspectRatio(BookCoverAspectRatio)
            .clip(outerShape)
            .background(coverContainerColor)
            .border(BorderStroke(1.dp, coverBorderColor), outerShape)
            .padding(3.dp),
        contentAlignment = Alignment.Center,
    ) {
        BookCoverFallback(
            title = title,
            author = author,
            modifier = Modifier.fillMaxSize().clip(innerShape),
        )
        artwork.coverSource?.let { source ->
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val imageModifier = if (artwork.blur) {
                    Modifier.blur(
                        radius = maxWidth * CoverBlurRadiusFraction,
                        edgeTreatment = BlurredEdgeTreatment(innerShape),
                    )
                } else {
                    Modifier
                }
                AsyncImage(
                    model = source,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = imageModifier.fillMaxSize().clip(innerShape),
                )
            }
        }
    }
}

@Composable
private fun BookCoverFallback(
    title: String,
    author: String?,
    modifier: Modifier = Modifier,
) {
    val hash = coverFallbackHash(title)
    val hue = (hash % 3_600uL).toFloat() / 10f
    val gradient = Brush.linearGradient(
        listOf(
            Color.hsv(hue, saturation = 0.42f, value = 0.6f),
            Color.hsv((hue + 21.6f) % 360f, saturation = 0.58f, value = 0.366f),
        ),
    )
    BoxWithConstraints(modifier.background(gradient)) {
        val scale = maxWidth.value / 150f
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    start = (12f * scale).dp,
                    top = (20f * scale).dp,
                    end = (12f * scale).dp,
                    bottom = (12f * scale).dp,
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = title,
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                autoSize = TextAutoSize.StepBased(
                    minFontSize = (9f * scale).sp,
                    maxFontSize = (15f * scale).sp,
                    stepSize = (0.5f * scale).coerceAtLeast(0.1f).sp,
                ),
                maxLines = 5,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(
                Modifier
                    .weight(1f)
                    .heightIn(min = (12f * scale).dp),
            )
            author?.takeIf { it.isNotBlank() }?.let { value ->
                Text(
                    text = value,
                    color = Color.White.copy(alpha = 0.75f),
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    autoSize = TextAutoSize.StepBased(
                        minFontSize = (7.2f * scale).sp,
                        maxFontSize = (12f * scale).sp,
                        stepSize = (0.5f * scale).coerceAtLeast(0.1f).sp,
                    ),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private const val BookCoverAspectRatio = 0.709f
private const val CoverBlurRadiusFraction = 0.05f
private const val FnvOffsetBasis = 0xcbf29ce484222325uL
private const val FnvPrime = 0x100000001b3uL
