package com.livetvpro.ui.player.compose

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material3.ripple
import androidx.compose.ui.focus.focusable
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.livetvpro.R
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.res.painterResource

// ExoPlayer's PlayerControlView animates at 150ms linear alpha. We match that.
private val exoEnterAnim = fadeIn(tween(150, easing = LinearEasing))
private val exoExitAnim  = fadeOut(tween(150, easing = LinearEasing))

// ─── Controls State ──────────────────────────────────────────────────────────

class PlayerControlsState(
    initialVisible: Boolean = true,
    val autoHideDelay: Long = 5000L
) {
    var isVisible by mutableStateOf(initialVisible)
        private set

    var isLocked by mutableStateOf(false)

    var isLockOverlayVisible by mutableStateOf(false)
        private set

    private var hideJob: Job? = null
    private var lockOverlayHideJob: Job? = null

    fun show(coroutineScope: CoroutineScope) {
        hideJob?.cancel()
        isVisible = true
        if (!isLocked) {
            hideJob = coroutineScope.launch {
                delay(autoHideDelay)
                isVisible = false
            }
        }
    }

    fun hide() {
        if (!isLocked) {
            hideJob?.cancel()
            isVisible = false
        }
    }

    fun toggle(coroutineScope: CoroutineScope) {
        if (isLocked) {
            toggleLockOverlay(coroutineScope)
        } else {
            if (isVisible) hide() else show(coroutineScope)
        }
    }

    fun lock() {
        isLocked = true
        hideJob?.cancel()
        isVisible = false
        isLockOverlayVisible = false
    }

    fun unlock(coroutineScope: CoroutineScope) {
        lockOverlayHideJob?.cancel()
        isLocked = false
        isLockOverlayVisible = false
        show(coroutineScope)
    }

    private fun toggleLockOverlay(coroutineScope: CoroutineScope) {
        lockOverlayHideJob?.cancel()
        isLockOverlayVisible = !isLockOverlayVisible
        if (isLockOverlayVisible) {
            lockOverlayHideJob = coroutineScope.launch {
                delay(autoHideDelay)
                isLockOverlayVisible = false
            }
        }
    }
}

// ─── Main PlayerControls composable ─────────────────────────────────────────

@Composable
fun PlayerControls(
    modifier: Modifier = Modifier,
    state: PlayerControlsState = remember { PlayerControlsState() },
    isPlaying: Boolean,
    isMuted: Boolean,
    currentPosition: Long,
    duration: Long,
    bufferedPosition: Long,
    channelName: String,
    showPipButton: Boolean,
    showAspectRatioButton: Boolean,
    isLandscape: Boolean,
    isTvMode: Boolean = false,
    onBackClick: () -> Unit,
    onPipClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onMuteClick: () -> Unit,
    onLockClick: (Boolean) -> Unit,
    onPlayPauseClick: () -> Unit,
    onSeek: (Long) -> Unit,
    onRewindClick: () -> Unit,
    onForwardClick: () -> Unit,
    onAspectRatioClick: () -> Unit,
    onFullscreenClick: () -> Unit,
    onChannelListClick: () -> Unit = {},
    isChannelListAvailable: Boolean = false,
    onVolumeSwipe: (Int) -> Unit = {},
    onBrightnessSwipe: (Int) -> Unit = {},
    initialVolume: Int = 100,
    initialBrightness: Int = 0,
    // Number-pad OSD: the activity passes the current digit buffer here so
    // PlayerControls can render the digit overlay on TV.
    digitBuffer: String = "",
) {
    val scope = rememberCoroutineScope()

    var gestureVolume     by remember { mutableIntStateOf(initialVolume) }
    var gestureBrightness by remember { mutableIntStateOf(initialBrightness) }
    var showVolumeOsd     by remember { mutableStateOf(false) }
    var showBrightnessOsd by remember { mutableStateOf(false) }

    // Auto-hide when playback starts
    LaunchedEffect(isPlaying) {
        if (isPlaying && state.isVisible && !state.isLocked) {
            delay(state.autoHideDelay)
            state.hide()
        }
    }

    Box(modifier = modifier.fillMaxSize()) {

        // Touch gesture overlay (volume swipe / brightness swipe / tap-to-toggle)
        // Hidden on pure TV mode since there's no touchscreen
        if (!isTvMode) {
            GestureOverlay(
                gestureState = GestureState(
                    volumePercent     = gestureVolume,
                    brightnessPercent = gestureBrightness,
                ),
                onVolumeChange = { v ->
                    gestureVolume = v
                    onVolumeSwipe(v)
                },
                onBrightnessChange = { b ->
                    gestureBrightness = b
                    onBrightnessSwipe(b)
                },
                onTap               = { state.toggle(scope) },
                onShowVolumeOsd     = { show -> showVolumeOsd = show },
                onShowBrightnessOsd = { show -> showBrightnessOsd = show },
            )
        } else {
            // On TV, a plain tap on the surface toggles controls
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures(onTap = { state.toggle(scope) })
                    }
            )
        }

        // Main controls — 150 ms linear fade, same as ExoPlayer
        AnimatedVisibility(
            visible = state.isVisible && !state.isLocked,
            enter   = exoEnterAnim,
            exit    = exoExitAnim,
        ) {
            PlayerControlsContent(
                isPlaying = isPlaying,
                isMuted = isMuted,
                currentPosition = currentPosition,
                duration = duration,
                bufferedPosition = bufferedPosition,
                channelName = channelName,
                showPipButton = showPipButton,
                showAspectRatioButton = showAspectRatioButton,
                isLandscape = isLandscape,
                isTvMode = isTvMode,
                onBackClick = onBackClick,
                onPipClick = onPipClick,
                onSettingsClick = onSettingsClick,
                onMuteClick = onMuteClick,
                onLockClick = {
                    state.lock()
                    onLockClick(true)
                },
                onPlayPauseClick = onPlayPauseClick,
                onSeek = onSeek,
                onRewindClick = onRewindClick,
                onForwardClick = onForwardClick,
                onAspectRatioClick = onAspectRatioClick,
                onFullscreenClick = onFullscreenClick,
                onChannelListClick = onChannelListClick,
                isChannelListAvailable = isChannelListAvailable,
                onInteraction = { state.show(scope) }
            )
        }

        // Lock overlay — same 150ms linear fade
        AnimatedVisibility(
            visible = state.isLockOverlayVisible,
            enter   = exoEnterAnim,
            exit    = exoExitAnim,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.5f))
                    .pointerInput(Unit) {
                        detectTapGestures(onTap = { state.toggle(scope) })
                    }
            ) {
                val lockInteractionSource = remember { MutableInteractionSource() }
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(start = 4.dp, top = 4.dp)
                        .size(40.dp)
                        .clickable(
                            interactionSource = lockInteractionSource,
                            indication = ripple(bounded = true, color = Color.White.copy(alpha = 0.25f)),
                            onClick = {
                                state.unlock(scope)
                                onLockClick(false)
                            }
                        )
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_lock_closed),
                        contentDescription = "Unlock controls",
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }

        // Volume / brightness OSD (touch swipe)
        VolumeOsd(
            visible  = showVolumeOsd,
            volume   = gestureVolume,
            modifier = Modifier.align(Alignment.Center),
        )
        BrightnessOsd(
            visible    = showBrightnessOsd,
            brightness = gestureBrightness,
            modifier   = Modifier.align(Alignment.Center),
        )

        // ── Number-pad channel-number OSD (TV only) ──────────────────────────
        // Shown whenever the activity has accumulated digits in digitBuffer.
        // E.g. user presses "1" then "0" → shows "10 ▶" for 1.5 s then jumps.
        if (isTvMode) {
            DigitBufferOsd(
                digitBuffer = digitBuffer,
                modifier    = Modifier.align(Alignment.TopEnd).padding(top = 24.dp, end = 24.dp),
            )
        }
    }
}

// ─── Digit-buffer OSD ────────────────────────────────────────────────────────

@Composable
private fun DigitBufferOsd(
    digitBuffer: String,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible  = digitBuffer.isNotEmpty(),
        enter    = fadeIn(tween(120)) + scaleIn(tween(120), initialScale = 0.88f),
        exit     = fadeOut(tween(200)),
        modifier = modifier,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .background(Color(0xCC1A1A1A), RoundedCornerShape(12.dp))
                .padding(horizontal = 18.dp, vertical = 12.dp)
        ) {
            Text(
                text       = "CH  $digitBuffer",
                color      = Color.White,
                fontSize   = 28.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = BergenSans,
            )
            Spacer(Modifier.width(8.dp))
            // Pulsing "▶" to hint that the number will commit soon
            val alpha by rememberInfiniteTransition(label = "pulse").animateFloat(
                initialValue = 0.3f,
                targetValue  = 1f,
                animationSpec = infiniteRepeatable(tween(500), RepeatMode.Reverse),
                label        = "digitPulse"
            )
            Text(
                text       = "▶",
                color      = Color(0xFFCC0000).copy(alpha = alpha),
                fontSize   = 20.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = BergenSans,
            )
        }
    }
}

// ─── Font shortcut ───────────────────────────────────────────────────────────

private val BergenSans = androidx.compose.ui.text.font.FontFamily(
    androidx.compose.ui.text.font.Font(R.font.bergen_sans)
)

// ─── PlayerControlsContent ───────────────────────────────────────────────────

@Composable
private fun PlayerControlsContent(
    isPlaying: Boolean,
    isMuted: Boolean,
    currentPosition: Long,
    duration: Long,
    bufferedPosition: Long,
    channelName: String,
    showPipButton: Boolean,
    showAspectRatioButton: Boolean,
    isLandscape: Boolean,
    isTvMode: Boolean,
    onBackClick: () -> Unit,
    onPipClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onMuteClick: () -> Unit,
    onLockClick: () -> Unit,
    onPlayPauseClick: () -> Unit,
    onSeek: (Long) -> Unit,
    onRewindClick: () -> Unit,
    onForwardClick: () -> Unit,
    onAspectRatioClick: () -> Unit,
    onFullscreenClick: () -> Unit,
    onChannelListClick: () -> Unit,
    isChannelListAvailable: Boolean,
    onInteraction: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {

        // Top gradient scrim
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
                .align(Alignment.TopCenter)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent)
                    )
                )
        )

        // Bottom gradient scrim
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp)
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f))
                    )
                )
        )

        // ── Top bar ──────────────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .padding(start = 4.dp, end = 4.dp, top = 0.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start
        ) {
            PlayerIconButton(
                onClick = onBackClick,
                iconRes = R.drawable.ic_arrow_back,
                contentDescription = "Back",
                size = 40
            )

            Text(
                text = channelName,
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = BergenSans,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp)
            )

            if (showPipButton) {
                PlayerIconButton(
                    onClick = onPipClick,
                    iconRes = R.drawable.ic_pip,
                    contentDescription = "Picture in Picture",
                    size = 40
                )
            }

            PlayerIconButton(
                onClick = { onSettingsClick(); onInteraction() },
                iconRes = R.drawable.ic_settings,
                contentDescription = "Settings",
                size = 40
            )
            PlayerIconButton(
                onClick = { onMuteClick(); onInteraction() },
                iconRes = if (isMuted) R.drawable.ic_volume_off else R.drawable.ic_volume_up,
                contentDescription = if (isMuted) "Unmute" else "Mute",
                size = 40
            )
            // Lock button — hidden on TV (no touch so locking makes no sense)
            if (!isTvMode) {
                PlayerIconButton(
                    onClick = onLockClick,
                    iconRes = R.drawable.ic_lock_open,
                    contentDescription = "Lock controls",
                    size = 40,
                    modifier = Modifier.padding(start = 4.dp)
                )
            }
        }

        // ── Bottom controls ──────────────────────────────────────────────────
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(start = 8.dp, end = 8.dp, bottom = 0.dp)
        ) {
            ExoPlayerTimeBar(
                currentPosition  = currentPosition,
                duration         = duration,
                bufferedPosition = bufferedPosition,
                onSeek           = onSeek,
                modifier         = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 4.dp)
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (showAspectRatioButton) {
                    PlayerIconButton(
                        onClick = { onAspectRatioClick(); onInteraction() },
                        iconRes = R.drawable.ic_aspect_ratio,
                        contentDescription = "Aspect ratio",
                        size = 40,
                        modifier = Modifier.padding(end = 12.dp)
                    )
                } else {
                    Spacer(modifier = Modifier.width(52.dp))
                }

                PlayerIconButton(
                    onClick = { onRewindClick(); onInteraction() },
                    iconRes = R.drawable.ic_skip_backward,
                    contentDescription = "Rewind 10 seconds",
                    size = 48,
                    modifier = Modifier.padding(end = 16.dp)
                )
                PlayerIconButton(
                    onClick = { onPlayPauseClick(); onInteraction() },
                    iconRes = if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    size = 64,
                    modifier = Modifier.padding(end = 16.dp)
                )
                PlayerIconButton(
                    onClick = { onForwardClick(); onInteraction() },
                    iconRes = R.drawable.ic_skip_forward,
                    contentDescription = "Forward 10 seconds",
                    size = 48,
                    modifier = Modifier.padding(end = 12.dp)
                )

                if (isTvMode) {
                    // TV: always show channel list button; no fullscreen toggle
                    if (isChannelListAvailable) {
                        PlayerIconButton(
                            onClick = { onChannelListClick(); onInteraction() },
                            iconRes = R.drawable.ic_list,
                            contentDescription = "Channel list",
                            size = 40
                        )
                    } else {
                        Spacer(modifier = Modifier.width(40.dp))
                    }
                } else {
                    // Phone/Tablet: channel list only in landscape, fullscreen always
                    if (isLandscape && isChannelListAvailable) {
                        PlayerIconButton(
                            onClick = { onChannelListClick(); onInteraction() },
                            iconRes = R.drawable.ic_list,
                            contentDescription = "Channel list",
                            size = 48,
                            modifier = Modifier.padding(end = 4.dp)
                        )
                    }
                    PlayerIconButton(
                        onClick = onFullscreenClick,
                        iconRes = if (isLandscape) R.drawable.ic_fullscreen_exit else R.drawable.ic_fullscreen,
                        contentDescription = "Toggle fullscreen",
                        size = 40
                    )
                }
            }

            // ── TV remote hint bar ────────────────────────────────────────────
            // Shows at the very bottom on TV devices only, reminding users of
            // shortcuts: CH+/CH-, number pad, colored buttons.
            if (isTvMode) {
                TvRemoteHintBar()
            }
        }
    }
}

// ─── TV remote hint bar ──────────────────────────────────────────────────────

@Composable
private fun TvRemoteHintBar() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp, start = 4.dp, end = 4.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val hints = listOf(
            "CH+/CH-" to "Switch",
            "0-9" to "Jump to CH",
            "MENU" to "Channel list",
            "■□△" to "Mute / Settings",
        )
        hints.forEachIndexed { i, (key, label) ->
            if (i > 0) {
                Text("  ·  ", color = Color.White.copy(alpha = 0.3f), fontSize = 10.sp)
            }
            Text(
                text = key,
                color = Color(0xFFCC0000),
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = BergenSans,
            )
            Text(
                text = " $label",
                color = Color.White.copy(alpha = 0.55f),
                fontSize = 10.sp,
                fontFamily = BergenSans,
            )
        }
    }
}

// ─── PlayerIconButton ─────────────────────────────────────────────────────────

@Composable
internal fun PlayerIconButton(
    onClick: () -> Unit,
    iconRes: Int,
    contentDescription: String,
    modifier: Modifier = Modifier,
    size: Int = 40,
    tint: Color = Color.White,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(size.dp)
            .focusable(interactionSource = interactionSource)
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyUp &&
                    (event.key == Key.DirectionCenter || event.key == Key.Enter)
                ) {
                    onClick()
                    true
                } else false
            }
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(bounded = true, color = Color.White.copy(alpha = 0.25f)),
                onClick = onClick
            )
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size((size * 0.6f).toInt().dp)
        )
    }
}

// ─── Time bar ────────────────────────────────────────────────────────────────

@Composable
private fun ExoPlayerTimeBar(
    currentPosition: Long,
    duration: Long,
    bufferedPosition: Long,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .wrapContentHeight(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Text(
            text = formatTime(currentPosition),
            color = Color.White,
            fontSize = 14.sp,
            fontFamily = BergenSans,
            modifier = Modifier.widthIn(min = 60.dp),
            textAlign = TextAlign.End,
            maxLines = 1,
        )

        CustomTimeBar(
            currentPosition  = currentPosition,
            duration         = duration,
            bufferedPosition = bufferedPosition,
            onSeek           = onSeek,
            modifier         = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp)
                .height(20.dp)
        )

        Text(
            text = formatTime(duration),
            color = Color.White,
            fontSize = 14.sp,
            fontFamily = BergenSans,
            modifier = Modifier.widthIn(min = 60.dp),
            textAlign = TextAlign.Start,
            maxLines = 1,
        )
    }
}

@Composable
private fun CustomTimeBar(
    currentPosition: Long,
    duration: Long,
    bufferedPosition: Long,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    var isDragging   by remember { mutableStateOf(false) }
    var dragPosition by remember { mutableFloatStateOf(0f) }

    val progress = if (duration > 0)
        (currentPosition.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
    else 0f

    val bufferedProgress = if (duration > 0)
        (bufferedPosition.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
    else 0f

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { offset ->
                        isDragging = true
                        dragPosition = offset.x / size.width
                    },
                    onDrag = { change, _ ->
                        dragPosition = (change.position.x / size.width).coerceIn(0f, 1f)
                    },
                    onDragEnd = {
                        isDragging = false
                        onSeek((dragPosition * duration).toLong())
                    }
                )
            }
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    onSeek(((offset.x / size.width).coerceIn(0f, 1f) * duration).toLong())
                }
            }
    ) {
        val barHeight      = 4.dp.toPx()
        val scrubberRadius = 6.dp.toPx()
        val centerY        = size.height / 2f

        drawLine(
            color = Color.White.copy(alpha = 0.3f),
            start = Offset(0f, centerY),
            end   = Offset(size.width, centerY),
            strokeWidth = barHeight,
            cap   = StrokeCap.Round,
        )

        val bufferedWidth = size.width * bufferedProgress
        if (bufferedWidth > 0) {
            drawLine(
                color = Color.White.copy(alpha = 0.5f),
                start = Offset(0f, centerY),
                end   = Offset(bufferedWidth, centerY),
                strokeWidth = barHeight,
                cap   = StrokeCap.Round,
            )
        }

        val currentProgress = if (isDragging) dragPosition else progress
        val playedWidth     = size.width * currentProgress
        if (playedWidth > 0) {
            drawLine(
                color = Color.White,
                start = Offset(0f, centerY),
                end   = Offset(playedWidth, centerY),
                strokeWidth = barHeight,
                cap   = StrokeCap.Round,
            )
        }

        drawCircle(
            color  = Color.White,
            radius = scrubberRadius,
            center = Offset(playedWidth, centerY),
        )
    }
}

// ─── Utilities ───────────────────────────────────────────────────────────────

private fun formatTime(timeMs: Long): String {
    val formatter  = StringBuilder()
    val formatter2 = java.util.Formatter(formatter, java.util.Locale.getDefault())
    return androidx.media3.common.util.Util.getStringForTime(formatter, formatter2, timeMs)
}
