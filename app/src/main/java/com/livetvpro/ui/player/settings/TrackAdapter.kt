package com.livetvpro.ui.player.settings

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.size
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
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
            val isSelected = item == selectedItem

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
                    if (isRadio) {
                        val dotScale by animateFloatAsState(
                            targetValue = if (selected) 1f else 0.7f,
                            animationSpec = spring(dampingRatio = 0.45f, stiffness = 650f),
                            label = "radio_scale"
                        )
                        val activeColor by animateColorAsState(
                            targetValue = if (selected) Color(0xFFFF0000) else Color(0xFF8A8A8A),
                            animationSpec = tween(180),
                            label = "radio_color"
                        )
                        RadioButton(
                            selected = selected,
                            onClick = null,
                            interactionSource = interactionSource,
                            colors = RadioButtonDefaults.colors(
                                selectedColor = activeColor,
                                unselectedColor = Color(0xFF8A8A8A)
                            ),
                            modifier = Modifier.scale(dotScale)
                        )
                    } else {
                        val checkmarkProgress = remember { Animatable(if (selected) 1f else 0f) }
                        val fillProgress     = remember { Animatable(if (selected) 1f else 0f) }
                        val borderColor by animateColorAsState(
                            targetValue = if (selected) Color(0xFFFF0000) else Color(0xFF8A8A8A),
                            animationSpec = tween(120),
                            label = "border_color"
                        )
                        LaunchedEffect(selected) {
                            if (selected) {
                                fillProgress.animateTo(1f, animationSpec = tween(140))
                                checkmarkProgress.animateTo(
                                    1f,
                                    animationSpec = spring(dampingRatio = 0.5f, stiffness = 600f)
                                )
                            } else {
                                checkmarkProgress.animateTo(0f, animationSpec = tween(100))
                                fillProgress.animateTo(0f, animationSpec = tween(120))
                            }
                        }

                        Canvas(
                            modifier = Modifier.size(22.dp)
                        ) {
                            drawAnimatedCheckbox(
                                fillProgress = fillProgress.value,
                                checkmarkProgress = checkmarkProgress.value,
                                borderColor = borderColor,
                                checkedFillColor = Color(0xFFFF0000),
                                checkmarkColor = Color.White,
                                strokeWidth = 2.2.dp.toPx(),
                                cornerRadius = 4.dp.toPx()
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
            currentItem = item
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

private fun DrawScope.drawAnimatedCheckbox(
    fillProgress: Float,
    checkmarkProgress: Float,
    borderColor: Color,
    checkedFillColor: Color,
    checkmarkColor: Color,
    strokeWidth: Float,
    cornerRadius: Float
) {
    val inset = strokeWidth / 2f
    val boxSize = Size(size.width - strokeWidth, size.height - strokeWidth)
    val topLeft = Offset(inset, inset)
    val radius = CornerRadius(cornerRadius, cornerRadius)

    if (fillProgress > 0f) {
        drawRoundRect(
            color = checkedFillColor.copy(alpha = fillProgress),
            topLeft = topLeft,
            size = boxSize,
            cornerRadius = radius
        )
    }

    drawRoundRect(
        color = borderColor,
        topLeft = topLeft,
        size = boxSize,
        cornerRadius = radius,
        style = Stroke(width = strokeWidth)
    )

    if (checkmarkProgress > 0f) {
        val w = size.width
        val h = size.height
        val p1 = Offset(w * 0.18f, h * 0.50f)
        val p2 = Offset(w * 0.40f, h * 0.72f)
        val p3 = Offset(w * 0.78f, h * 0.28f)

        val path = Path()
        if (checkmarkProgress <= 0.5f) {
            val t = checkmarkProgress / 0.5f
            path.moveTo(p1.x, p1.y)
            path.lineTo(
                p1.x + (p2.x - p1.x) * t,
                p1.y + (p2.y - p1.y) * t
            )
        } else {
            val t = (checkmarkProgress - 0.5f) / 0.5f
            path.moveTo(p1.x, p1.y)
            path.lineTo(p2.x, p2.y)
            path.lineTo(
                p2.x + (p3.x - p2.x) * t,
                p2.y + (p3.y - p2.y) * t
            )
        }

        drawPath(
            path = path,
            color = checkmarkColor,
            style = Stroke(
                width = strokeWidth * 1.15f,
                cap = StrokeCap.Round,
                join = StrokeJoin.Round
            )
        )
    }
}
