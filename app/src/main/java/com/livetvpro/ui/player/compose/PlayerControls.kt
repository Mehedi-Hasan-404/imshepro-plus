package com.livetvpro.ui.player.compose

import android.content.res.Configuration
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
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
        private set
    
    private var hideJob: kotlinx.coroutines.Job? = null
    
    fun show(coroutineScope: kotlinx.coroutines.CoroutineScope) {
        hideJob?.cancel()
        isVisible = true
        
        // Auto-hide after delay if not locked
        if (!isLocked) {
            hideJob = coroutineScope.launch {
                delay(autoHideDelay)
                hide()
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
        if (isVisible) {
            hide()
        } else {
            show(coroutineScope)
        }
    }
    
    fun lock() {
        isLocked = true
        hide()
    }
    
    fun unlock(coroutineScope: kotlinx.coroutines.CoroutineScope) {
        isLocked = false
        show(coroutineScope)
    }
}

/**
 * Main player controls composable
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
    
    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = {
                        if (!state.isLocked) {
                            state.toggle(scope)
                        }
                    }
                )
            }
    ) {
        // Lock overlay and unlock button
        AnimatedVisibility(
            visible = state.isLocked,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.5f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        state.unlock(scope)
                    }
            ) {
                IconButton(
                    onClick = { state.unlock(scope) },
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(4.dp)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_lock_closed),
                        contentDescription = "Unlock",
                        tint = Color.White
                    )
                }
            }
        }
        
        // Main controls
        AnimatedVisibility(
            visible = state.isVisible && !state.isLocked,
            enter = fadeIn() + slideInVertically(),
            exit = fadeOut() + slideOutVertically()
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
        // Top gradient
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
        
        // Bottom gradient
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
        
        // Top bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .padding(horizontal = 4.dp, vertical = 0.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PlayerIconButton(
                onClick = onBackClick,
                iconRes = R.drawable.ic_arrow_back,
                contentDescription = "Back"
            )
            
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
            
            if (showPipButton) {
                PlayerIconButton(
                    onClick = onPipClick,
                    iconRes = R.drawable.ic_pip,
                    contentDescription = "Picture in Picture"
                )
            }
            
            PlayerIconButton(
                onClick = onSettingsClick,
                iconRes = R.drawable.ic_settings,
                contentDescription = "Settings"
            )
            
            PlayerIconButton(
                onClick = onMuteClick,
                iconRes = if (isMuted) R.drawable.ic_volume_off else R.drawable.ic_volume_up,
                contentDescription = if (isMuted) "Unmute" else "Mute"
            )
            
            PlayerIconButton(
                onClick = onLockClick,
                iconRes = R.drawable.ic_lock_open,
                contentDescription = "Lock controls",
                modifier = Modifier.padding(start = 4.dp)
            )
        }
        
        // Center playback buttons
        Row(
            modifier = Modifier.align(Alignment.Center),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PlayerIconButton(
                onClick = {
                    onRewindClick()
                    onInteraction()
                },
                iconRes = R.drawable.ic_skip_backward,
                contentDescription = "Rewind",
                size = 48.dp
            )
            
            PlayerIconButton(
                onClick = {
                    onPlayPauseClick()
                    onInteraction()
                },
                iconRes = if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play,
                contentDescription = if (isPlaying) "Pause" else "Play",
                size = 64.dp
            )
            
            PlayerIconButton(
                onClick = {
                    onForwardClick()
                    onInteraction()
                },
                iconRes = R.drawable.ic_skip_forward,
                contentDescription = "Forward",
                size = 48.dp
            )
        }
        
        // Bottom controls
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(horizontal = 8.dp)
        ) {
            // Progress bar
            PlayerProgressBar(
                currentPosition = currentPosition,
                duration = duration,
                bufferedPosition = bufferedPosition,
                onSeek = { 
                    onSeek(it)
                    onInteraction()
                }
            )
            
            // Bottom buttons
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (showAspectRatioButton) {
                    PlayerIconButton(
                        onClick = onAspectRatioClick,
                        iconRes = R.drawable.ic_aspect_ratio,
                        contentDescription = "Aspect ratio",
                        modifier = Modifier.padding(end = 12.dp)
                    )
                }
                
                Spacer(modifier = Modifier.weight(1f))
                
                PlayerIconButton(
                    onClick = onFullscreenClick,
                    iconRes = if (isLandscape) R.drawable.ic_fullscreen_exit else R.drawable.ic_fullscreen,
                    contentDescription = "Toggle fullscreen"
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
            modifier = Modifier.size((size * 0.6f).dp)
        )
    }
}

@Composable
private fun PlayerProgressBar(
    currentPosition: Long,
    duration: Long,
    bufferedPosition: Long,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = formatTime(currentPosition),
            color = Color.White,
            fontSize = 12.sp,
            modifier = Modifier.width(48.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        
        var sliderValue by remember(currentPosition) { 
            mutableFloatStateOf(currentPosition.toFloat()) 
        }
        
        Slider(
            value = sliderValue,
            onValueChange = { sliderValue = it },
            onValueChangeFinished = { onSeek(sliderValue.toLong()) },
            valueRange = 0f..duration.toFloat().coerceAtLeast(1f),
            modifier = Modifier.weight(1f),
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = Color.Red,
                inactiveTrackColor = Color.White.copy(alpha = 0.3f)
            )
        )
        
        Text(
            text = formatTime(duration),
            color = Color.White,
            fontSize = 12.sp,
            modifier = Modifier.width(48.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}

private fun formatTime(timeMs: Long): String {
    val totalSeconds = timeMs / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    
    return if (hours > 0) {
        String.format("%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format("%d:%02d", minutes, seconds)
    }
}
