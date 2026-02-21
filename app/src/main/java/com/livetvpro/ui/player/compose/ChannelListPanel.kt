package com.livetvpro.ui.player.compose

import android.widget.ImageView
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.livetvpro.R
import com.livetvpro.utils.GlideExtensions
import com.livetvpro.data.models.Channel

private val RED            = Color(0xFFCC0000)
private val PANEL_BG       = Color(0x990A0A0A)
private val SIDEBAR_BG     = Color(0x99141414)
private val HEADER_BG      = Color(0xAA1A1A1A)
private val BergenSans     = androidx.compose.ui.text.font.FontFamily(
    androidx.compose.ui.text.font.Font(R.font.bergen_sans)
)

data class ChannelGroup(val title: String, val channels: List<Channel>)

fun buildChannelGroups(channels: List<Channel>): List<ChannelGroup> {
    val result = mutableListOf<ChannelGroup>()
    result += ChannelGroup("All Channels", channels)
    channels.mapNotNull { it.groupTitle.takeIf { g -> g.isNotBlank() } }
            .distinct()
            .forEach { g -> result += ChannelGroup(g, channels.filter { it.groupTitle == g }) }
    return result
}

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
        enter = slideInHorizontally(initialOffsetX = { it }, animationSpec = tween(300)) +
                fadeIn(animationSpec = tween(300)),
        exit  = slideOutHorizontally(targetOffsetX = { it }, animationSpec = tween(250)) +
                fadeOut(animationSpec = tween(250)),
        modifier = modifier
    ) {
        Box(modifier = Modifier.fillMaxSize()) {

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.25f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss
                    )
            )

            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(0.60f)
                    .align(Alignment.CenterEnd)
                    .background(PANEL_BG)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {}
                    )
            ) {
                val groups = remember(channels) { buildChannelGroups(channels) }
                var selectedGroupIndex by remember { mutableIntStateOf(0) }
                val selectedGroup = groups.getOrNull(selectedGroupIndex)
                val channelListState = rememberLazyListState()

                LaunchedEffect(selectedGroupIndex) { channelListState.scrollToItem(0) }
                LaunchedEffect(visible, selectedGroupIndex) {
                    if (visible && selectedGroup != null) {
                        val idx = selectedGroup.channels.indexOfFirst { it.id == currentChannelId }
                        if (idx >= 0) channelListState.animateScrollToItem(idx)
                    }
                }

                Column(modifier = Modifier.fillMaxSize()) {

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(HEADER_BG)
                            .padding(horizontal = 14.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .background(Color(0xFF2DB233), RoundedCornerShape(50))
                                .clickable(onClick = onDismiss),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_arrow_back),
                                contentDescription = "Close",
                                tint = Color.White,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = "Channels List",
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = BergenSans
                        )
                    }

                    HorizontalDivider(color = Color.White.copy(alpha = 0.08f), thickness = 1.dp)

                    Row(modifier = Modifier.fillMaxSize()) {

                        LazyColumn(
                            modifier = Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(0.28f)
                                .background(SIDEBAR_BG)
                        ) {
                            itemsIndexed(groups) { index, group ->
                                GroupSidebarItem(
                                    title = group.title,
                                    isSelected = index == selectedGroupIndex,
                                    channelCount = group.channels.size,
                                    onClick = { selectedGroupIndex = index }
                                )
                            }
                        }

                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .width(1.dp)
                                .background(Color.White.copy(alpha = 0.08f))
                        )

                        LazyColumn(
                            state = channelListState,
                            modifier = Modifier.fillMaxSize().background(PANEL_BG)
                        ) {
                            val chList = selectedGroup?.channels ?: emptyList()
                            itemsIndexed(chList) { index, channel ->
                                ChannelItemRow(
                                    channel = channel,
                                    serialNumber = index + 1,
                                    isPlaying = channel.id == currentChannelId,
                                    onClick = { onChannelClick(channel); onDismiss() }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GroupSidebarItem(
    title: String,
    isSelected: Boolean,
    channelCount: Int,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (isSelected) RED.copy(alpha = 0.85f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = title,
            color = if (isSelected) Color.White else Color.White.copy(alpha = 0.65f),
            fontSize = 11.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
            fontFamily = BergenSans,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            lineHeight = 14.sp
        )
        Spacer(Modifier.height(3.dp))
        Text(
            text = "$channelCount",
            color = if (isSelected) Color.White.copy(alpha = 0.85f) else Color.White.copy(alpha = 0.4f),
            fontSize = 10.sp,
            fontFamily = BergenSans
        )
    }
    HorizontalDivider(color = Color.White.copy(alpha = 0.06f), thickness = 0.5.dp)
}

@Composable
private fun ChannelItemRow(
    channel: Channel,
    serialNumber: Int,
    isPlaying: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (isPlaying) RED.copy(alpha = 0.85f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = serialNumber.toString(),
            color = if (isPlaying) Color.White else Color(0xFFB8E44A),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = BergenSans,
            modifier = Modifier.widthIn(min = 30.dp),
            textAlign = TextAlign.Start
        )
        Spacer(Modifier.width(6.dp))
        AndroidView(
            factory = { ctx -> ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP } },
            update = { iv ->
                GlideExtensions.loadImage(iv, channel.logoUrl.takeIf { it.isNotBlank() }, R.mipmap.ic_launcher_round, R.mipmap.ic_launcher_round, isCircular = true)
            },
            modifier = Modifier.size(32.dp).clip(CircleShape)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = channel.name,
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = if (isPlaying) FontWeight.Bold else FontWeight.Normal,
            fontFamily = BergenSans,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (isPlaying) {
            Spacer(Modifier.width(4.dp))
            Box(modifier = Modifier.size(6.dp).background(Color.White, RoundedCornerShape(50)))
        }
    }
    HorizontalDivider(
        color = Color.White.copy(alpha = 0.05f),
        thickness = 0.5.dp,
        modifier = Modifier.padding(start = 48.dp)
    )
}
