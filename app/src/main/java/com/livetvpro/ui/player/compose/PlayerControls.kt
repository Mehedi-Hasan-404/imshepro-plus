package com.livetvpro.ui.player.compose

import android.content.res.Configuration
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.livetvpro.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Player controls state holder
 */
class PlayerControlsState(
    initialVisible: Boolean = true,
    val autoHideDelay: Long = 5000L
) {
    var isVisible by mutableStateOf(initialVisible)
        private set

    var isLocked by mutableStateOf(false)
        // Made publicly settable for compatibility with PlayerActivity

    // When locked, tracks whether the lock-icon overlay is currently visible.
    // Tapping the screen toggles it; it auto-hides after autoHideDelay.
    var isLockOverlayVisible by mutableStateOf(false)
        private set

    private var hideJob: kotlinx.coroutines.Job? = null
    private var lockOverlayHideJob: kotlinx.coroutines.Job? = null

    fun show(coroutineScope: kotlinx.coroutines.CoroutineScope) {
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

    fun toggle(coroutineScope: kotlinx.coroutines.CoroutineScope) {
        if (isLocked) {
            // Tapping while locked toggles only the lock-icon overlay
            toggleLockOverlay(coroutineScope)
        } else {
            if (isVisible) hide() else show(coroutineScope)
        }
    }

    fun lock() {
        isLocked = true
        // Forcibly hide main controls (bypass the isLocked guard in hide())
        hideJob?.cancel()
        isVisible = false
        isLockOverlayVisible = false
    }

    fun unlock(coroutineScope: kotlinx.coroutines.CoroutineScope) {
        lockOverlayHideJob?.cancel()
        isLocked = false
        isLockOverlayVisible = false
        show(coroutineScope)
    }

    private fun toggleLockOverlay(coroutineScope: kotlinx.coroutines.CoroutineScope) {
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

/**
 * Main player controls composable - matches the original XML design
 * FIXED: Now properly allows link chips in landscape mode to be clickable
 */
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
    onFullscreenClick: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val configuration = LocalConfiguration.current
    
    // NO EXCLUSION ZONE NEEDED!
    // Link chips RecyclerView is now positioned on the right side with maxWidth
    // It doesn't overlap with top control buttons anymore
    // All touch events can be handled normally
    
    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = {
                        if (state.isLocked) {
                            state.toggle(scope)
                        } else {
                            state.toggle(scope)
                        }
                    }
                )
            }
    ) {
        // Lock overlay — shown/hidden independently via isLockOverlayVisible
        // (toggled by tapping the screen while locked; auto-hides after delay)
        AnimatedVisibility(
            visible = state.isLockOverlayVisible,
            enter = fadeIn(
                animationSpec = tween(
                    durationMillis = 250,
                    easing = FastOutSlowInEasing
                )
            ),
            exit = fadeOut(
                animationSpec = tween(
                    durationMillis = 250,
                    easing = FastOutSlowInEasing
                )
            )
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = {
                                // Tapping anywhere toggles visibility
                                state.toggle(scope)
                            }
                        )
                    }
            ) {
                // Completely transparent - only unlock button visible
                // Unlock button
                IconButton(
                    onClick = { 
                        state.unlock(scope)
                        onLockClick(false)
                    },
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(start = 4.dp, top = 4.dp)
                        .size(40.dp)
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
        
        // Main controls - ExoPlayer's exact animation (250ms alpha fade)
        AnimatedVisibility(
            visible = state.isVisible && !state.isLocked,
            enter = fadeIn(
                animationSpec = tween(
                    durationMillis = 250,
                    easing = FastOutSlowInEasing
                )
            ),
            exit = fadeOut(
                animationSpec = tween(
                    durationMillis = 250,
                    easing = FastOutSlowInEasing
                )
            )
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
                onInteraction = { state.show(scope) }
            )
        }
    }
}

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
    onInteraction: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        // Top gradient overlay
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
                .align(Alignment.TopCenter)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = 0.7f),
                            Color.Transparent
                        )
                    )
                )
        )
        
        // Bottom gradient overlay
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp)
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color.Black.copy(alpha = 0.7f)
                        )
                    )
                )
        )
        
        // Top bar with controls (back, channel name, pip, settings, mute, lock)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .padding(start = 4.dp, end = 4.dp, top = 0.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start
        ) {
            // Back button
            PlayerIconButton(
                onClick = onBackClick,
                iconRes = R.drawable.ic_arrow_back,
                contentDescription = "Back",
                size = 40
            )
            
            // Channel name
            Text(
                text = channelName,
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp)
            )
            
            // PIP button
            if (showPipButton) {
                PlayerIconButton(
                    onClick = onPipClick,
                    iconRes = R.drawable.ic_pip,
                    contentDescription = "Picture in Picture",
                    size = 40
                )
            }
            
            // Settings button
            PlayerIconButton(
                onClick = {
                    onSettingsClick()
                    onInteraction()
                },
                iconRes = R.drawable.ic_settings,
                contentDescription = "Settings",
                size = 40
            )
            
            // Mute button
            PlayerIconButton(
                onClick = {
                    onMuteClick()
                    onInteraction()
                },
                iconRes = if (isMuted) R.drawable.ic_volume_off else R.drawable.ic_volume_up,
                contentDescription = if (isMuted) "Unmute" else "Mute",
                size = 40
            )
            
            // Lock button
            PlayerIconButton(
                onClick = onLockClick,
                iconRes = R.drawable.ic_lock_open,
                contentDescription = "Lock controls",
                size = 40,
                modifier = Modifier.padding(start = 4.dp)
            )
        }
        
        // Bottom bar with playback controls
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(start = 8.dp, end = 8.dp, bottom = 0.dp)
        ) {
            // Time bar with position and duration
            ExoPlayerTimeBar(
                currentPosition = currentPosition,
                duration = duration,
                bufferedPosition = bufferedPosition,
                onSeek = onSeek,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 4.dp)
            )
            
            // Playback control buttons
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Aspect ratio button (40dp, left side)
                if (showAspectRatioButton) {
                    PlayerIconButton(
                        onClick = {
                            onAspectRatioClick()
                            onInteraction()
                        },
                        iconRes = R.drawable.ic_aspect_ratio,
                        contentDescription = "Aspect ratio",
                        size = 40,
                        modifier = Modifier.padding(end = 12.dp)
                    )
                } else {
                    Spacer(modifier = Modifier.width(52.dp))
                }
                
                // Rewind button (48dp)
                PlayerIconButton(
                    onClick = {
                        onRewindClick()
                        onInteraction()
                    },
                    iconRes = R.drawable.ic_skip_backward,
                    contentDescription = "Rewind 10 seconds",
                    size = 48,
                    modifier = Modifier.padding(end = 16.dp)
                )
                
                // Play/Pause button (64dp - largest)
                PlayerIconButton(
                    onClick = {
                        onPlayPauseClick()
                        onInteraction()
                    },
                    iconRes = if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    size = 64,
                    modifier = Modifier.padding(end = 16.dp)
                )
                
                // Forward button (48dp)
                PlayerIconButton(
                    onClick = {
                        onForwardClick()
                        onInteraction()
                    },
                    iconRes = R.drawable.ic_skip_forward,
                    contentDescription = "Forward 10 seconds",
                    size = 48,
                    modifier = Modifier.padding(end = 12.dp)
                )
                
                // Fullscreen button (40dp, right side)
                PlayerIconButton(
                    onClick = onFullscreenClick,
                    iconRes = if (isLandscape) R.drawable.ic_fullscreen_exit else R.drawable.ic_fullscreen,
                    contentDescription = "Toggle fullscreen",
                    size = 40
                )
            }
        }
    }
}

@Composable
private fun PlayerIconButton(
    onClick: () -> Unit,
    iconRes: Int,
    contentDescription: String,
    modifier: Modifier = Modifier,
    size: Int = 40,
    tint: Color = Color.White
) {
    IconButton(
        onClick = onClick,
        modifier = modifier.size(size.dp)
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size((size * 0.6f).toInt().dp)
        )
    }
}

/**
 * ExoPlayer-style TimeBar that closely matches DefaultTimeBar appearance
 * This replaces the Material3 Slider with a custom implementation
 */
@Composable
private fun ExoPlayerTimeBar(
    currentPosition: Long,
    duration: Long,
    bufferedPosition: Long,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .wrapContentHeight(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        // Position text
        Text(
            text = formatTime(currentPosition),
            color = Color.White,
            fontSize = 14.sp,
            fontFamily = androidx.compose.ui.text.font.FontFamily(
                androidx.compose.ui.text.font.Font(R.font.bergen_sans)
            ),
            modifier = Modifier.widthIn(min = 60.dp),
            textAlign = TextAlign.End,
            maxLines = 1
        )
        
        // Custom TimeBar
        CustomTimeBar(
            currentPosition = currentPosition,
            duration = duration,
            bufferedPosition = bufferedPosition,
            onSeek = onSeek,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp)
                .height(20.dp)
        )
        
        // Duration text
        Text(
            text = formatTime(duration),
            color = Color.White,
            fontSize = 14.sp,
            fontFamily = androidx.compose.ui.text.font.FontFamily(
                androidx.compose.ui.text.font.Font(R.font.bergen_sans)
            ),
            modifier = Modifier.widthIn(min = 60.dp),
            textAlign = TextAlign.Start,
            maxLines = 1
        )
    }
}

/**
 * Custom TimeBar that looks like ExoPlayer's DefaultTimeBar
 */
@Composable
private fun CustomTimeBar(
    currentPosition: Long,
    duration: Long,
    bufferedPosition: Long,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    var isDragging by remember { mutableStateOf(false) }
    var dragPosition by remember { mutableFloatStateOf(0f) }
    
    val progress = if (duration > 0) {
        (currentPosition.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
    } else 0f
    
    val bufferedProgress = if (duration > 0) {
        (bufferedPosition.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
    } else 0f
    
    val density = LocalDensity.current
    
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
                        val seekPosition = (dragPosition * duration).toLong()
                        onSeek(seekPosition)
                    }
                )
            }
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    val tapPosition = (offset.x / size.width).coerceIn(0f, 1f)
                    val seekPosition = (tapPosition * duration).toLong()
                    onSeek(seekPosition)
                }
            }
    ) {
        val barHeight = 4.dp.toPx()
        val scrubberRadius = 6.dp.toPx()
        val centerY = size.height / 2f
        
        // Draw unplayed track (light gray/white with transparency)
        drawLine(
            color = Color.White.copy(alpha = 0.3f),
            start = Offset(0f, centerY),
            end = Offset(size.width, centerY),
            strokeWidth = barHeight,
            cap = StrokeCap.Round
        )
        
        // Draw buffered track (slightly lighter)
        val bufferedWidth = size.width * bufferedProgress
        if (bufferedWidth > 0) {
            drawLine(
                color = Color.White.copy(alpha = 0.5f),
                start = Offset(0f, centerY),
                end = Offset(bufferedWidth, centerY),
                strokeWidth = barHeight,
                cap = StrokeCap.Round
            )
        }
        
        // Draw played track (red, like YouTube/ExoPlayer default)
        val currentProgress = if (isDragging) dragPosition else progress
        val playedWidth = size.width * currentProgress
        if (playedWidth > 0) {
            drawLine(
                color = Color(0xFFFF0000), // Bright red like ExoPlayer
                start = Offset(0f, centerY),
                end = Offset(playedWidth, centerY),
                strokeWidth = barHeight,
                cap = StrokeCap.Round
            )
        }
        
        // Draw scrubber (white circle)
        drawCircle(
            color = Color.White,
            radius = scrubberRadius,
            center = Offset(playedWidth, centerY)
        )
    }
}

private fun formatTime(timeMs: Long): String {
    // Use ExoPlayer's default time formatter
    val formatter = StringBuilder()
    val formatter2 = java.util.Formatter(formatter, java.util.Locale.getDefault())
    return androidx.media3.common.util.Util.getStringForTime(formatter, formatter2, timeMs)
}
