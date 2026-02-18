package com.livetvpro.ui.player.settings

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.recyclerview.widget.RecyclerView
import com.livetvpro.databinding.ItemTrackOptionBinding

private val Red       = Color(0xFFE53935)
private val Gray      = Color(0xFF8A8A8A)
private val StateOnSurface = Color(0xFFFFFFFF)

class TrackAdapter<T : TrackUiModel>(
    private val onSelect: (T) -> Unit
) : RecyclerView.Adapter<TrackAdapter<T>.VH>() {

    private val items = mutableListOf<T>()

    fun submit(list: List<T>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    fun updateSelection(selectedItem: T) {
        items.forEachIndexed { index, item ->
            val wasSelected = item.isSelected
            val isSelected  = item == selectedItem

            @Suppress("UNCHECKED_CAST")
            val updatedItem = when (item) {
                is TrackUiModel.Video -> (item as TrackUiModel.Video).copy(isSelected = isSelected)
                is TrackUiModel.Audio -> (item as TrackUiModel.Audio).copy(isSelected = isSelected)
                is TrackUiModel.Text  -> (item as TrackUiModel.Text).copy(isSelected = isSelected)
                is TrackUiModel.Speed -> (item as TrackUiModel.Speed).copy(isSelected = isSelected)
                else -> item
            } as T

            items[index] = updatedItem
            if (wasSelected != isSelected) notifyItemChanged(index)
        }
    }

    inner class VH(val binding: ItemTrackOptionBinding) :
        RecyclerView.ViewHolder(binding.root) {

        private val selectedState = mutableStateOf(false)
        private val isRadioState  = mutableStateOf(true)
        private var currentItem: T? = null

        init {
            binding.composeToggle.setViewCompositionStrategy(
                ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool
            )
            binding.composeToggle.setContent {
                val selected by selectedState
                val isRadio  by isRadioState
                val interactionSource = remember { MutableInteractionSource() }

                @OptIn(ExperimentalMaterial3Api::class)
                CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier         = Modifier.fillMaxSize()
                    ) {
                        if (isRadio) {
                            M3RadioButton(
                                selected          = selected,
                                interactionSource = interactionSource
                            )
                        } else {
                            M3Checkbox(
                                checked           = selected,
                                interactionSource = interactionSource
                            )
                        }
                    }
                }
            }

            binding.root.setOnClickListener {
                val item = currentItem ?: return@setOnClickListener
                if (isRadioState.value) {
                    selectedState.value = true
                } else {
                    selectedState.value = !selectedState.value
                }
                onSelect(item)
            }
        }

        fun bind(item: T) {
            currentItem         = item
            selectedState.value = item.isSelected
            isRadioState.value  = item.isRadio
            binding.tvSecondary.visibility = View.VISIBLE

            when (item) {
                is TrackUiModel.Video -> {
                    when {
                        item.groupIndex == -2 -> {
                            binding.tvPrimary.text = "None"
                            binding.tvSecondary.visibility = View.GONE
                        }
                        item.groupIndex == -1 -> {
                            binding.tvPrimary.text = "Auto"
                            binding.tvSecondary.visibility = View.GONE
                        }
                        else -> {
                            val quality = if (item.width > 0 && item.height > 0)
                                "${item.width} × ${item.height}" else "Unknown quality"
                            val bitrate = if (item.bitrate > 0)
                                "${"%.2f".format(item.bitrate / 1_000_000f)} Mbps" else "Unknown bitrate"
                            binding.tvPrimary.text = "$quality • $bitrate"
                            binding.tvSecondary.visibility = View.GONE
                        }
                    }
                }
                is TrackUiModel.Audio -> {
                    when {
                        item.groupIndex == -2 -> {
                            binding.tvPrimary.text = "None"
                            binding.tvSecondary.text = "No audio"
                        }
                        item.groupIndex == -1 -> {
                            binding.tvPrimary.text = "Auto"
                            binding.tvSecondary.text = "Automatic audio"
                        }
                        else -> {
                            val language = if (item.language.isNotEmpty() && item.language != "und")
                                item.language.uppercase() else "Unknown"
                            val channels = when (item.channels) {
                                1 -> " • Mono"; 2 -> " • Stereo"
                                6 -> " • Surround 5.1"; 8 -> " • Surround 7.1"
                                else -> if (item.channels > 0) " • ${item.channels}ch" else ""
                            }
                            val bitrate = if (item.bitrate > 0) "${item.bitrate / 1000} kbps"
                            else "Unknown bitrate"
                            binding.tvPrimary.text = "$language$channels"
                            binding.tvSecondary.text = bitrate
                        }
                    }
                }
                is TrackUiModel.Text -> {
                    when {
                        item.groupIndex == -2 -> {
                            binding.tvPrimary.text = "None"
                            binding.tvSecondary.text = "No subtitles"
                        }
                        item.groupIndex == -1 -> {
                            binding.tvPrimary.text = "Auto"
                            binding.tvSecondary.text = "Automatic subtitles"
                        }
                        else -> {
                            val language = if (item.language.isNotEmpty() && item.language != "und")
                                item.language.uppercase() else "Unknown"
                            binding.tvPrimary.text = language
                            binding.tvSecondary.text = "Subtitles"
                        }
                    }
                }
                is TrackUiModel.Speed -> {
                    binding.tvPrimary.text = "${item.speed}x"
                    binding.tvSecondary.text = "Playback speed"
                }
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemTrackOptionBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])
    override fun getItemCount() = items.size
}

@Composable
private fun M3RadioButton(
    selected: Boolean,
    interactionSource: MutableInteractionSource
) {
    val isPressed by interactionSource.collectIsPressedAsState()
    val isHovered by interactionSource.collectIsHoveredAsState()

    val dotScale by animateFloatAsState(
        targetValue   = if (selected) 1f else 0.9f,
        animationSpec = spring(dampingRatio = 0.45f, stiffness = 650f),
        label         = "radio_scale"
    )
    val activeColor by animateColorAsState(
        targetValue   = if (selected) Red else Gray,
        animationSpec = tween(180),
        label         = "radio_color"
    )

    val stateLayerAlpha by animateFloatAsState(
        targetValue = when {
            isPressed -> 0.12f
            isHovered -> 0.08f
            else      -> 0f
        },
        animationSpec = tween(if (isPressed) 0 else 150),
        label         = "radio_state_alpha"
    )
    val stateLayerColor = if (selected) Red else StateOnSurface

    RadioButton(
        selected          = selected,
        onClick           = null,
        interactionSource = interactionSource,
        colors            = RadioButtonDefaults.colors(
            selectedColor   = activeColor,
            unselectedColor = Gray
        ),
        modifier = Modifier
            .scale(dotScale)
            .drawBehind {
                if (stateLayerAlpha > 0f) {
                    drawCircle(
                        color  = stateLayerColor.copy(alpha = stateLayerAlpha),
                        radius = 20.dp.toPx()
                    )
                }
            }
    )
}

@Composable
private fun M3Checkbox(
    checked: Boolean,
    interactionSource: MutableInteractionSource
) {
    val isPressed by interactionSource.collectIsPressedAsState()
    val isHovered by interactionSource.collectIsHoveredAsState()

    val checkmarkProgress = remember { Animatable(if (checked) 1f else 0f) }
    val fillProgress      = remember { Animatable(if (checked) 1f else 0f) }

    val containerColor by animateColorAsState(
        targetValue   = if (checked) Red else Color.Transparent,
        animationSpec = tween(90),
        label         = "container_fill"
    )
    val borderColor by animateColorAsState(
        targetValue   = if (checked) Red else Gray,
        animationSpec = tween(100),
        label         = "border_color"
    )
    val iconAlpha by animateFloatAsState(
        targetValue   = if (checked) 1f else 0f,
        animationSpec = tween(80),
        label         = "icon_alpha"
    )
    val stateLayerAlpha by animateFloatAsState(
        targetValue = when {
            isPressed -> 0.12f
            isHovered -> 0.08f
            else      -> 0f
        },
        animationSpec = tween(if (isPressed) 0 else 150),
        label         = "state_layer_alpha"
    )
    val stateLayerColor = if (checked) Red else StateOnSurface

    LaunchedEffect(checked) {
        if (checked) {
            fillProgress.animateTo(1f, tween(90))
            checkmarkProgress.snapTo(0f)
            checkmarkProgress.animateTo(1f, tween(150))
        } else {
            checkmarkProgress.animateTo(0f, tween(80))
            fillProgress.animateTo(0f, tween(100))
        }
    }

    Box(
        modifier = Modifier
            .size(20.dp)
            .drawBehind {
                val stateRadius = 20.dp.toPx()
                if (stateLayerAlpha > 0f) {
                    drawCircle(
                        color  = stateLayerColor.copy(alpha = stateLayerAlpha),
                        radius = stateRadius,
                        center = center
                    )
                }

                drawContainer(
                    fillColor    = containerColor,
                    borderColor  = borderColor,
                    strokeWidth  = 2.dp.toPx(),
                    cornerRadius = 2.dp.toPx()
                )

                if (checkmarkProgress.value > 0f) {
                    drawIcon(
                        progress = checkmarkProgress.value,
                        color    = Color.White,
                        alpha    = iconAlpha
                    )
                }
            }
    )
}

private fun DrawScope.drawContainer(
    fillColor: Color,
    borderColor: Color,
    strokeWidth: Float,
    cornerRadius: Float
) {
    val inset   = strokeWidth / 2f
    val boxSize = Size(size.width - strokeWidth, size.height - strokeWidth)
    val topLeft = Offset(inset, inset)
    val radius  = CornerRadius(cornerRadius)

    if (fillColor.alpha > 0f) {
        drawRoundRect(
            color        = fillColor,
            topLeft      = topLeft,
            size         = boxSize,
            cornerRadius = radius
        )
    }

    drawRoundRect(
        color        = borderColor,
        topLeft      = topLeft,
        size         = boxSize,
        cornerRadius = radius,
        style        = Stroke(width = strokeWidth)
    )
}

private fun DrawScope.drawIcon(
    progress: Float,
    color: Color,
    alpha: Float
) {
    val w  = size.width
    val h  = size.height
    val p1 = Offset(w * 0.20f, h * 0.50f)
    val p2 = Offset(w * 0.42f, h * 0.72f)
    val p3 = Offset(w * 0.80f, h * 0.28f)

    val path = Path().apply {
        moveTo(p1.x, p1.y)
        lineTo(p2.x, p2.y)
        lineTo(p3.x, p3.y)
    }

    val measure  = PathMeasure()
    measure.setPath(path, false)
    val totalLen = measure.length
    val drawn    = totalLen * progress

    val trimmed = Path()
    measure.getSegment(0f, drawn, trimmed, true)

    drawPath(
        path  = trimmed,
        color = color.copy(alpha = alpha),
        style = Stroke(
            width = 2.dp.toPx(),
            cap   = StrokeCap.Round,
            join  = StrokeJoin.Round
        )
    )
}
