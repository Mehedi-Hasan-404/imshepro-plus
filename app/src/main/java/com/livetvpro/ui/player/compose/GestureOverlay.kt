package com.livetvpro.ui.player.compose

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ─────────────────────────────────────────────────────────────────────────────
// Data / state
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Tracks the current gesture state exposed to the caller.
 * [volumePercent] ranges 0‥100; 0 means muted.
 * [brightnessPercent] ranges 0‥100; 0 = AUTO (follows system brightness).
 */
data class GestureState(
    val volumePercent: Int = 50,        // 0 = muted, 1‥100 = audible
    val brightnessPercent: Int = 0,     // 0 = AUTO, 1‥100 = manual
)

// ─────────────────────────────────────────────────────────────────────────────
// Public composable
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Transparent overlay that sits **on top of** the video surface but **below**
 * the player UI controls.  It captures vertical swipe gestures in the left and
 * right halves of the screen and shows the appropriate OSD indicator.
 *
 * Layout regions (horizontal thirds):
 *   LEFT  third  → brightness  (swipe up = brighter, swipe down = darker → Auto below 1%)
 *   MIDDLE third → no gesture (play-pause area, as per spec)
 *   RIGHT  third → volume      (swipe up = louder,  swipe down = quieter)
 *
 * Usage – place inside the same [Box] that wraps [PlayerControls]:
 * ```
 * Box(modifier = Modifier.fillMaxSize()) {
 *     PlayerSurface(...)
 *     GestureOverlay(
 *         gestureState = gestureState,
 *         onVolumeChange     = { newVol -> applyVolume(newVol) },
 *         onBrightnessChange = { newBri -> applyBrightness(newBri) } // 0 = AUTO
 *     )
 *     PlayerControls(...)
 * }
 * ```
 */
@Composable
fun GestureOverlay(
    modifier: Modifier = Modifier,
    gestureState: GestureState = remember { GestureState() },
    /** Called whenever the user swipes to change volume (0‥100). */
    onVolumeChange: (Int) -> Unit = {},
    /** Called whenever the user swipes to change brightness (0 = AUTO, 1‥100 = manual). */
    onBrightnessChange: (Int) -> Unit = {},
    /** Sensitivity: how many pixels of drag equal 1 percent of change. */
    pixelsPerPercent: Float = 8f,
) {
    val scope = rememberCoroutineScope()

    // ── Local gesture tracking ──────────────────────────────────────────────
    var currentVolume by remember { mutableIntStateOf(gestureState.volumePercent) }
    // 0 = AUTO, 1‥100 = manual. We use a float accumulator that can dip below 1
    // to latch into AUTO before climbing back up.
    var currentBrightness by remember { mutableIntStateOf(gestureState.brightnessPercent) }
    // Internal float tracker so we can smoothly cross the 0 (AUTO) boundary
    var brightnessFloat by remember { mutableFloatStateOf(gestureState.brightnessPercent.toFloat()) }

    // ── OSD visibility ──────────────────────────────────────────────────────
    var showVolumeOsd by remember { mutableStateOf(false) }
    var showBrightnessOsd by remember { mutableStateOf(false) }

    // Auto-hide OSD jobs
    var volumeHideJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var brightnessHideJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    fun scheduleVolumeHide() {
        volumeHideJob?.cancel()
        volumeHideJob = scope.launch {
            delay(1_500)
            showVolumeOsd = false
        }
    }

    fun scheduleBrightnessHide() {
        brightnessHideJob?.cancel()
        brightnessHideJob = scope.launch {
            delay(1_500)
            showBrightnessOsd = false
        }
    }

    // ── Width tracking for zone detection ──────────────────────────────────
    var totalWidth by remember { mutableFloatStateOf(0f) }

    // Accumulates the raw drag delta so we can do integer stepping
    var volumeAccum by remember { mutableFloatStateOf(0f) }
    var brightnessAccum by remember { mutableFloatStateOf(0f) }

    Box(
        modifier = modifier
            .fillMaxSize()
            // Capture ALL touch events in this overlay; let tap events fall through
            // when neither zone matches (we only consume drag in valid zones).
            .pointerInput(pixelsPerPercent) {
                totalWidth = size.width.toFloat()
                detectDragGestures(
                    onDragStart = { offset ->
                        // Reset accumulators at the start of each swipe
                        val zone = gestureZone(offset.x, totalWidth)
                        if (zone == GestureZone.VOLUME) {
                            volumeAccum = 0f
                        } else if (zone == GestureZone.BRIGHTNESS) {
                            brightnessAccum = 0f
                        }
                    },
                    onDrag = { change, dragAmount ->
                        change.consume() // consume so controls underneath don't also react
                        val zone = gestureZone(change.position.x, totalWidth)
                        // Negative dy = swipe up = increase; positive dy = swipe down = decrease
                        val dy = -dragAmount.y

                        when (zone) {
                            GestureZone.VOLUME -> {
                                volumeAccum += dy
                                val steps = (volumeAccum / pixelsPerPercent).toInt()
                                if (steps != 0) {
                                    volumeAccum -= steps * pixelsPerPercent
                                    currentVolume = (currentVolume + steps).coerceIn(0, 100)
                                    onVolumeChange(currentVolume)
                                    showVolumeOsd = true
                                    scheduleVolumeHide()
                                }
                            }
                            GestureZone.BRIGHTNESS -> {
                                brightnessAccum += dy
                                val steps = (brightnessAccum / pixelsPerPercent).toInt()
                                if (steps != 0) {
                                    brightnessAccum -= steps * pixelsPerPercent
                                    // clamp to 0 (AUTO) .. 100; 0 is the AUTO state
                                    brightnessFloat = (brightnessFloat + steps).coerceIn(0f, 100f)
                                    currentBrightness = brightnessFloat.toInt()
                                    onBrightnessChange(currentBrightness)
                                    showBrightnessOsd = true
                                    scheduleBrightnessHide()
                                }
                            }
                            GestureZone.NONE -> { /* no-op */ }
                        }
                    },
                    onDragEnd = {
                        // accumulators reset on next drag start
                    }
                )
            }
    ) {
        // ── Volume OSD (right side) ─────────────────────────────────────────
        AnimatedVisibility(
            visible = showVolumeOsd,
            enter = fadeIn(tween(180)) + scaleIn(tween(180), initialScale = 0.85f),
            exit  = fadeOut(tween(300)),
            modifier = Modifier.align(Alignment.Center)
        ) {
            VolumeIndicator(volume = currentVolume)
        }

        // ── Brightness OSD (left side) ──────────────────────────────────────
        AnimatedVisibility(
            visible = showBrightnessOsd,
            enter = fadeIn(tween(180)) + scaleIn(tween(180), initialScale = 0.85f),
            exit  = fadeOut(tween(300)),
            modifier = Modifier.align(Alignment.Center)
        ) {
            BrightnessIndicator(brightness = currentBrightness)
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// OSD indicators
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Volume OSD: mute icon at 0 %, speaker icon otherwise.
 * Matches the screenshot style exactly.
 */
@Composable
private fun VolumeIndicator(volume: Int) {
    OsdCard {
        val iconRes = if (volume == 0) R.drawable.ic_volume_off else R.drawable.ic_volume_up
        Icon(
            painter = painterResource(id = iconRes),
            contentDescription = "Volume",
            tint = Color.White,
            modifier = Modifier.size(36.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = "$volume%",
            color = Color.White,
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

/**
 * Brightness OSD with four icon states:
 *   0        → auto   (ic_brightness_auto)   — "AUTO" label
 *   1 – 30  % → low   (ic_brightness_low)
 *   31– 60  % → mid   (ic_brightness_medium)
 *   61–100  % → high  (ic_brightness_high)
 */
@Composable
private fun BrightnessIndicator(brightness: Int) {
    OsdCard {
        val iconRes = when {
            brightness == 0  -> R.drawable.ic_brightness_auto
            brightness <= 30 -> R.drawable.ic_brightness_low
            brightness <= 60 -> R.drawable.ic_brightness_medium
            else             -> R.drawable.ic_brightness_high
        }
        Icon(
            painter = painterResource(id = iconRes),
            contentDescription = "Brightness",
            tint = Color.White,
            modifier = Modifier.size(36.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = if (brightness == 0) "Auto" else "$brightness%",
            color = Color.White,
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

/**
 * Dark rounded card — same look as in the screenshots.
 */
@Composable
private fun OsdCard(content: @Composable RowScope.() -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .background(
                color = Color(0xFF1F1F1F).copy(alpha = 0.82f),
                shape = RoundedCornerShape(16.dp)
            )
            .padding(horizontal = 24.dp, vertical = 18.dp)
    ) {
        content()
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Internal helpers
// ─────────────────────────────────────────────────────────────────────────────

private enum class GestureZone { BRIGHTNESS, NONE, VOLUME }

/**
 * Divides the screen into three equal horizontal thirds.
 *
 *  [0,   w/3)  → BRIGHTNESS (left)
 *  [w/3, 2w/3) → NONE       (middle / play-pause area)
 *  [2w/3, w]   → VOLUME     (right)
 */
private fun gestureZone(x: Float, totalWidth: Float): GestureZone {
    if (totalWidth <= 0f) return GestureZone.NONE
    return when {
        x < totalWidth / 3f       -> GestureZone.BRIGHTNESS
        x > totalWidth * 2f / 3f  -> GestureZone.VOLUME
        else                       -> GestureZone.NONE
    }
}
