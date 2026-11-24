package me.lesh.chatter.ui.recap

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.lesh.chatter.R
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The gear of the chat's top bar. [updateDot] marks a waiting update; [celebrate] puts confetti
 * around it while a look back at the year is ready and not opened yet.
 */
@Composable
fun SettingsGear(updateDot: Boolean, celebrate: Boolean) {
    val label = stringResource(R.string.settings)
    Box(contentAlignment = Alignment.Center) {
        if (celebrate) {
            // Wider than the icon, so the pieces fly out around it; drawn behind, never in the way.
            GearConfetti(Modifier.requiredSize(44.dp))
        }
        BadgedBox(badge = { if (updateDot) Badge() }) {
            Icon(
                Icons.Default.Settings,
                contentDescription = if (celebrate) "$label. ${stringResource(R.string.recap_ready)}" else label,
            )
        }
    }
}

/**
 * The way into the look back atop the settings, from December 1 to January 10, dressed for the
 * occasion. [onHide] takes it away for the year; the look back stays under Stats.
 */
@Composable
fun RecapFeatureCard(year: Int, onOpen: () -> Unit, onHide: () -> Unit) {
    Surface(
        color = Violet,
        shape = RoundedCornerShape(28.dp),
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box {
            FallingConfetti(Modifier.matchParentSize())
            Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 24.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(ImageVector.vectorResource(R.drawable.ic_chatter_monochrome), contentDescription = null, tint = Lime, modifier = Modifier.size(28.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.recap_ready), color = Paper, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    IconButton(onClick = onHide) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.recap_hide), tint = Paper)
                    }
                }
                Text(year.toString(), color = Lime, fontSize = 72.sp, lineHeight = 76.sp, fontWeight = FontWeight.Black)
                Spacer(Modifier.height(12.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(Lime)
                        .padding(start = 20.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
                ) {
                    Text(stringResource(R.string.recap_card_open), color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Black)
                    Spacer(Modifier.width(4.dp))
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = Ink)
                }
            }
        }
    }
}

/**
 * Confetti that bursts out of the gear, drifts down and fades, again and again with a pause in
 * between. Between bursts nothing is drawn, so the chat screen, where the gear sits, does not
 * redraw every frame for it the whole time.
 */
@Composable
private fun GearConfetti(modifier: Modifier) {
    val burst = remember { Animatable(1f) }
    LaunchedEffect(Unit) {
        while (true) {
            burst.snapTo(0f)
            burst.animateTo(1f, tween(GEAR_BURST_MS, easing = LinearEasing))
            delay(GEAR_PAUSE_MS)
        }
    }
    Canvas(modifier) {
        val t = burst.value
        if (t >= 1f) return@Canvas
        // Out fast, then slower, while falling a little and fading in the last third.
        val out = FastOutSlowInEasing.transform(t)
        val alpha = if (t < 0.65f) 1f else (1f - t) / 0.35f
        PIECES_AROUND_GEAR.forEach { piece ->
            val reach = size.minDimension / 2 * piece.distance * out
            val at = Offset(
                center.x + cos(piece.angle) * reach,
                center.y + sin(piece.angle) * reach + t * t * size.minDimension * 0.3f,
            )
            drawPiece(piece, at, piece.spin * t * 2, alpha)
        }
    }
}

/** Confetti raining down, endlessly, over the right part of an area so text on the left stays clear. */
@Composable
private fun FallingConfetti(modifier: Modifier) {
    val fall = rememberInfiniteTransition(label = "confetti")
    val t by fall.animateFloat(0f, 1f, infiniteRepeatable(tween(FALL_CYCLE_MS, easing = LinearEasing)), label = "t")
    Canvas(modifier) {
        val margin = size.minDimension * 0.1f
        PIECES_FALLING.forEach { piece ->
            // Whole rounds per cycle, so the loop has no seam.
            val progress = (piece.phase + t * piece.rounds) % 1f
            val x = size.width * piece.x + sin(progress * 2 * PI.toFloat() * 2) * size.minDimension * 0.04f
            val y = progress * (size.height + 2 * margin) - margin
            drawPiece(piece.piece, Offset(x, y), piece.piece.spin + t * 360f * piece.rounds, 1f)
        }
    }
}

private fun DrawScope.drawPiece(piece: Piece, at: Offset, turn: Float, alpha: Float) {
    val s = size.minDimension * piece.size
    val color = piece.color.copy(alpha = alpha)
    rotate(turn, at) {
        if (piece.round) {
            drawCircle(color, s / 2, at)
        } else {
            drawRoundRect(color, Offset(at.x - s / 2, at.y - s / 4), Size(s, s / 2), CornerRadius(s / 6))
        }
    }
}

/** One piece: [angle] in radians from the middle, [distance] and [size] as shares of the area. */
private class Piece(val angle: Float, val distance: Float, val size: Float, val color: Color, val spin: Float, val round: Boolean)

/** A falling piece: [x] across the area, where in its fall it starts, and how many falls per cycle. */
private class FallingPiece(val piece: Piece, val x: Float, val phase: Float, val rounds: Int)

private val ConfettiColors = listOf(Lime, Color(0xFFFF6B4F), Color(0xFFFFC83D), Color(0xFF2FD3AE), Color(0xFFFF8AC9), Color(0xFF7CC4FF))

/** Fixed rather than random, so the confetti looks the same on every start. */
private fun pieces(count: Int, size: Float, minDistance: Float, seed: Int): List<Piece> = List(count) { i ->
    Piece(
        angle = i * 2.399963f + seed,
        distance = minDistance + (1 - minDistance) * ((i * 37 + seed) % 100) / 100f,
        size = size * (0.7f + ((i * 53) % 10) / 20f),
        color = ConfettiColors[(i + seed) % ConfettiColors.size],
        spin = ((i * 71) % 180).toFloat(),
        round = i % 3 == 0,
    )
}

private val PIECES_AROUND_GEAR = pieces(count = 10, size = 0.12f, minDistance = 0.65f, seed = 1)
private val PIECES_FALLING = pieces(count = 22, size = 0.05f, minDistance = 0f, seed = 3).mapIndexed { i, piece ->
    FallingPiece(piece, x = 0.45f + 0.53f * ((i * 61) % 100) / 100f, phase = ((i * 29) % 100) / 100f, rounds = 1 + i % 2)
}

private const val GEAR_BURST_MS = 1_600
private const val GEAR_PAUSE_MS = 3_500L

/** One cycle of the rain; the slower pieces fall once in it, the faster twice. */
private const val FALL_CYCLE_MS = 7_000
