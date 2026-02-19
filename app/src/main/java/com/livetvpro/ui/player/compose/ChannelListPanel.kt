package com.livetvpro.ui.player.compose

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.livetvpro.R
import com.livetvpro.data.models.Channel

/**
 * An item in the channel list panel — either a group header or a channel row.
 */
sealed class ChannelListItem {
    data class GroupHeader(val title: String) : ChannelListItem()
    data class ChannelRow(
        val channel: Channel,
        val serialNumber: Int,   // serial number within its group
        val isCurrentlyPlaying: Boolean
    ) : ChannelListItem()
}

/**
 * Builds the flat list that the panel displays:
 *   1. "All Channels" header → all channels serialised 1…N
 *   2. Per-group headers → channels in that group serialised 1…N
 *
 * Channels with an empty groupTitle are listed under "All Channels" only.
 */
fun buildChannelListItems(
    channels: List<Channel>,
    currentChannelId: String
): List<ChannelListItem> {
    val items = mutableListOf<ChannelListItem>()

    // ── 1. ALL CHANNELS ──────────────────────────────────────────────────────
    items += ChannelListItem.GroupHeader("All Channels")
    channels.forEachIndexed { idx, ch ->
        items += ChannelListItem.ChannelRow(
            channel = ch,
            serialNumber = idx + 1,
            isCurrentlyPlaying = ch.id == currentChannelId
        )
    }

    // ── 2. PER-GROUP SECTIONS ─────────────────────────────────────────────────
    val groups = channels
        .mapNotNull { it.groupTitle.takeIf { g -> g.isNotBlank() } }
        .distinct()

    groups.forEach { group ->
        val groupChannels = channels.filter { it.groupTitle == group }
        items += ChannelListItem.GroupHeader(group)
        groupChannels.forEachIndexed { idx, ch ->
            items += ChannelListItem.ChannelRow(
                channel = ch,
                serialNumber = idx + 1,
                isCurrentlyPlaying = ch.id == currentChannelId
            )
        }
    }

    return items
}

/**
 * Sliding panel that appears from the right side in landscape / fullscreen mode.
 * Shows "All Channels" then per-group sections, each with their own serial numbers.
 */
@Composable
fun ChannelListPanel(
    visible: Boolean,
    channels: List<Channel>,
    currentChannelId: String,
    onChannelClick: (Channel) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = visible,
        enter = slideInHorizontally(
            initialOffsetX = { it },
            animationSpec = tween(300)
        ) + fadeIn(animationSpec = tween(300)),
        exit = slideOutHorizontally(
            targetOffsetX = { it },
            animationSpec = tween(250)
        ) + fadeOut(animationSpec = tween(250)),
        modifier = modifier
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Dim backdrop — tapping it closes the panel
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.5f))
                    .clickable(
                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss
                    )
            )

            // Panel itself — right-aligned, ~55% width
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(0.55f)
                    .align(Alignment.CenterEnd)
                    .background(Color(0xDD0A0A0A))
            ) {
                val listItems = remember(channels, currentChannelId) {
                    buildChannelListItems(channels, currentChannelId)
                }

                // Scroll to currently playing channel on open
                val listState = rememberLazyListState()
                val playingIndex = remember(listItems) {
                    listItems.indexOfFirst {
                        it is ChannelListItem.ChannelRow && it.isCurrentlyPlaying
                    }.takeIf { it >= 0 }
                }
                LaunchedEffect(visible, playingIndex) {
                    if (visible && playingIndex != null) {
                        listState.animateScrollToItem(playingIndex)
                    }
                }

                Column(modifier = Modifier.fillMaxSize()) {
                    // Header bar
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF1A1A1A))
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_list),
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "Channels List",
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.weight(1f))
                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_close),
                                contentDescription = "Close",
                                tint = Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    HorizontalDivider(color = Color.White.copy(alpha = 0.1f), thickness = 1.dp)

                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        itemsIndexed(listItems) { _, item ->
                            when (item) {
                                is ChannelListItem.GroupHeader -> {
                                    GroupHeaderRow(title = item.title)
                                }
                                is ChannelListItem.ChannelRow -> {
                                    ChannelItemRow(
                                        channel = item.channel,
                                        serialNumber = item.serialNumber,
                                        isPlaying = item.isCurrentlyPlaying,
                                        onClick = {
                                            onChannelClick(item.channel)
                                            onDismiss()
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GroupHeaderRow(title: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF1E1E1E))
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(16.dp)
                .background(Color(0xFFCC0000), RoundedCornerShape(2.dp))
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = title,
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.5.sp
        )
    }
}

@Composable
private fun ChannelItemRow(
    channel: Channel,
    serialNumber: Int,
    isPlaying: Boolean,
    onClick: () -> Unit
) {
    val bgColor = if (isPlaying) Color(0x33CC0000) else Color.Transparent

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bgColor)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Serial number
        Text(
            text = serialNumber.toString(),
            color = if (isPlaying) Color(0xFFCC0000) else Color.White.copy(alpha = 0.5f),
            fontSize = 12.sp,
            fontWeight = if (isPlaying) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.widthIn(min = 28.dp)
        )

        Spacer(Modifier.width(8.dp))

        // Channel logo
        AsyncImage(
            model = channel.logoUrl.takeIf { it.isNotBlank() },
            contentDescription = null,
            placeholder = painterResource(R.drawable.ic_tv_placeholder),
            error = painterResource(R.drawable.ic_tv_placeholder),
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(4.dp))
        )

        Spacer(Modifier.width(10.dp))

        // Channel name
        Text(
            text = channel.name,
            color = if (isPlaying) Color.White else Color.White.copy(alpha = 0.85f),
            fontSize = 13.sp,
            fontWeight = if (isPlaying) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )

        // Now playing indicator
        if (isPlaying) {
            Spacer(Modifier.width(6.dp))
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .background(Color(0xFFCC0000), shape = RoundedCornerShape(50))
            )
        }
    }

    HorizontalDivider(
        color = Color.White.copy(alpha = 0.05f),
        thickness = 0.5.dp,
        modifier = Modifier.padding(start = 56.dp)
    )
}
