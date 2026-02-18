package com.livetvpro.ui.player.compose

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.livetvpro.R
import kotlin.math.abs

// ─────────────────────────────────────────────────────────────────────────────
// Data / state
// ─────────────────────────────────────────────────────────────────────────────

/**
 * [volumePercent]     0 = muted,  1‥100 = audible
 * [brightnessPercent] 0 = AUTO,   1‥100 = manual
 */
data class GestureState(
    val volumePercent: Int = 50,
    val brightnessPercent: Int = 0,
)

// ─────────────────────────────────────────────────────────────────────────────
// Gesture overlay — full screen, transparent
// Handles tap (toggle controls) + vertical swipe on left/right thirds
// (brightness / volume). OSD cards are NOT rendered here — they are hoisted
// to PlayerControls so they appear above the lock overlay too.
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun GestureOverlay(
    modifier: Modifier = Modifier,
    gestureState: GestureState = remember { GestureState() },
    pixelsPerPercent: Float = 8f,
    onVolumeChange: (Int) -> Unit = {},
    onBrightnessChange: (Int) -> Unit = {},
    /** Called on plain tap (no swipe) — use to toggle player controls. */
    onTap: () -> Unit = {},
    /** Called every frame while swiping — true = show OSD, false = hide. */
    onShowVolumeOsd: (Boolean) -> Unit = {},
    onShowBrightnessOsd: (Boolean) -> Unit = {},
) {
    var currentVolume     by remember { mutableIntStateOf(gestureState.volumePercent) }
    var brightnessFloat   by remember { mutableFloatStateOf(gestureState.brightnessPercent.toFloat()) }
    var volumeAccum       by remember { mutableFloatStateOf(0f) }
    var brightnessAccum   by remember { mutableFloatStateOf(0f) }
    var totalWidth        by remember { mutableFloatStateOf(0f) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(pixelsPerPercent) {
                totalWidth = size.width.toFloat()
                val slopPx = viewConfiguration.touchSlop
                awaitEachGesture {
                    val down   = awaitFirstDown(requireUnconsumed = false)
                    val zone   = gestureZone(down.position.x, totalWidth)
                    var totalDy   = 0f
                    var committed = false

                    if (zone == GestureZone.VOLUME)     volumeAccum     = 0f
                    if (zone == GestureZone.BRIGHTNESS) brightnessAccum = 0f

                    while (true) {
                        val event  = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break

                        if (!change.pressed) {
                            if (!committed) onTap()          // tap — toggle controls
                            else {
                                // swipe ended — hide OSD
                                onShowVolumeOsd(false)
                                onShowBrightnessOsd(false)
                            }
                            break
                        }

                        val dy = -(change.position.y - change.previousPosition.y)
                        totalDy += dy
                        if (!committed && abs(totalDy) > slopPx) committed = true

                        if (committed && zone != GestureZone.NONE) {
                            change.consume()
                            when (zone) {
                                GestureZone.VOLUME -> {
                                    volumeAccum += dy
                                    val steps = (volumeAccum / pixelsPerPercent).toInt()
                                    if (steps != 0) {
                                        volumeAccum  -= steps * pixelsPerPercent
                                        currentVolume = (currentVolume + steps).coerceIn(0, 100)
                                        onVolumeChange(currentVolume)
                                        onShowVolumeOsd(true)
                                    }
                                }
                                GestureZone.BRIGHTNESS -> {
                                    brightnessAccum += dy
                                    val steps = (brightnessAccum / pixelsPerPercent).toInt()
                                    if (steps != 0) {
                                        brightnessAccum -= steps * pixelsPerPercent
                                        brightnessFloat  = (brightnessFloat + steps).coerceIn(0f, 100f)
                                        onBrightnessChange(brightnessFloat.toInt())
                                        onShowBrightnessOsd(true)
                                    }
                                }
                                GestureZone.NONE -> {}
                            }
                        }
                    }
                }
            }
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// OSD cards — rendered at PlayerControls level, above lock overlay
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun VolumeOsd(visible: Boolean, volume: Int, modifier: Modifier = Modifier) {
    AnimatedVisibility(
        visible  = visible,
        enter    = fadeIn(tween(150)) + scaleIn(tween(150), initialScale = 0.85f),
        exit     = fadeOut(tween(200)),
        modifier = modifier,
    ) {
        OsdCard {
            Icon(
                painter            = painterResource(if (volume == 0) R.drawable.ic_volume_off else R.drawable.ic_volume_up),
                contentDescription = "Volume",
                tint               = Color.White,
                modifier           = Modifier.size(36.dp),
            )
            Spacer(Modifier.width(12.dp))
            Text("$volume%", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
fun BrightnessOsd(visible: Boolean, brightness: Int, modifier: Modifier = Modifier) {
    AnimatedVisibility(
        visible  = visible,
        enter    = fadeIn(tween(150)) + scaleIn(tween(150), initialScale = 0.85f),
        exit     = fadeOut(tween(200)),
        modifier = modifier,
    ) {
        OsdCard {
            Icon(
                painter = painterResource(when {
                    brightness == 0  -> R.drawable.ic_brightness_auto
                    brightness <= 30 -> R.drawable.ic_brightness_low
                    brightness <= 60 -> R.drawable.ic_brightness_medium
                    else             -> R.drawable.ic_brightness_high
                }),
                contentDescription = "Brightness",
                tint               = Color.White,
                modifier           = Modifier.size(36.dp),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text       = if (brightness == 0) "Auto" else "$brightness%",
                color      = Color.White,
                fontSize   = 22.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun OsdCard(content: @Composable RowScope.() -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .background(Color(0xFF1F1F1F).copy(alpha = 0.82f), RoundedCornerShape(16.dp))
            .padding(horizontal = 24.dp, vertical = 18.dp),
    ) { content() }
}

// ─────────────────────────────────────────────────────────────────────────────
// Helpers
// ─────────────────────────────────────────────────────────────────────────────

private enum class GestureZone { BRIGHTNESS, NONE, VOLUME }

private fun gestureZone(x: Float, totalWidth: Float): GestureZone {
    if (totalWidth <= 0f) return GestureZone.NONE
    return when {
        x < totalWidth / 3f      -> GestureZone.BRIGHTNESS
        x > totalWidth * 2f / 3f -> GestureZone.VOLUME
        else                      -> GestureZone.NONE
    }
}
