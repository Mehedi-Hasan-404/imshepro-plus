package com.livetvpro.ui.player.settings

import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.HoverInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsFocusedAsState
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.recyclerview.widget.RecyclerView
import com.livetvpro.databinding.ItemTrackOptionBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

// M3 spec colours
private val ColorPrimary   = Color(0xFFE53935) // our red as colorPrimary
private val ColorOnSurface = Color(0xFFFFFFFF) // white on dark background
private val ColorGray      = Color(0xFF8A8A8A) // unselected border

// M3 spec state-layer opacities (from m3_checkbox_button_tint.xml / M3 motion spec)
private const val ALPHA_PRESSED  = 0.12f
private const val ALPHA_FOCUSED  = 0.12f
private const val ALPHA_HOVERED  = 0.08f
private const val ALPHA_DISABLED = 0.38f

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

        // Shared InteractionSource — bridged from Android View touch events below
        private val interactionSource = MutableInteractionSource()
        private val scope = CoroutineScope(Dispatchers.Main.immediate)

        // Track the active press interaction so we can emit Release correctly
        private var activePress: PressInteraction.Press? = null
        private var activeHover: HoverInteraction.Enter? = null

        init {
            binding.composeToggle.setViewCompositionStrategy(
                ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool
            )
            binding.composeToggle.setContent {
                val selected by selectedState
                val isRadio  by isRadioState

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

            // ── Bridge Android View touch → Compose InteractionSource ──────────
            // The ComposeView is clickable=false so the ROW handles the click,
            // but we still need to forward touch DOWN/UP to drive the state layer.
            binding.root.setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        val press = PressInteraction.Press(
                            Offset(event.x, event.y)
                        )
                        activePress = press
                        scope.launch { interactionSource.emit(press) }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        activePress?.let { press ->
                            val release = if (event.actionMasked == MotionEvent.ACTION_UP)
                                PressInteraction.Release(press)
                            else
                                PressInteraction.Cancel(press)
                            scope.launch { interactionSource.emit(release) }
                            activePress = null
                        }
                    }
                    // Hover events — fired by mouse/stylus (tablets, foldables)
                    MotionEvent.ACTION_HOVER_ENTER -> {
                        val hover = HoverInteraction.Enter()
                        activeHover = hover
                        scope.launch { interactionSource.emit(hover) }
                    }
                    MotionEvent.ACTION_HOVER_EXIT -> {
                        activeHover?.let { hover ->
                            scope.launch { interactionSource.emit(HoverInteraction.Exit(hover)) }
                            activeHover = null
                        }
                    }
                }
                // Return false so the click listener still fires
                false
            }

            // Focus changes — when the row gains/loses focus (keyboard navigation)
            binding.root.setOnFocusChangeListener { _, hasFocus ->
                scope.launch {
                    if (hasFocus) {
                        interactionSource.emit(FocusInteraction.Focus())
                    } else {
                        // FocusInteraction.Unfocus requires the original Focus ref;
                        // emitting a new Focus then Unfocus resets state cleanly
                        val f = FocusInteraction.Focus()
                        interactionSource.emit(FocusInteraction.Unfocus(f))
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
                                "${item.width} × ${item.height}" else "Unknown"
                            val bitrate = if (item.bitrate > 0)
                                "${"%.2f".format(item.bitrate / 1_000_000f)} Mbps" else ""
                            binding.tvPrimary.text = if (bitrate.isNotEmpty()) "$quality • $bitrate" else quality
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
                            val channels = when (item.channels) {
                                1 -> " • Mono"; 2 -> " • Stereo"
                                6 -> " • Surround 5.1"; 8 -> " • Surround 7.1"
                                else -> if (item.channels > 0) " • ${item.channels}ch" else ""
                            }
                            binding.tvPrimary.text = "${item.language}$channels"
                            binding.tvSecondary.text = if (item.bitrate > 0) "${item.bitrate / 1000} kbps" else ""
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
                            binding.tvPrimary.text = item.language
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

// ── M3 Radio Button ──────────────────────────────────────────────────────────

@Composable
private fun M3RadioButton(
    selected: Boolean,
    interactionSource: MutableInteractionSource
) {
    // Collect all interaction states
    val interactions = rememberInteractionState(interactionSource)

    // M3 spec: colorPrimary when selected, colorOnSurface (gray) when not
    val ringColor by animateColorAsState(
        targetValue   = if (selected) ColorPrimary else ColorGray,
        animationSpec = tween(180),
        label         = "radio_ring_color"
    )

    // M3 state layer: circle behind, colour = primary if selected, onSurface if not
    val stateLayerColor = if (selected) ColorPrimary else ColorOnSurface
    val stateLayerAlpha by animateFloatAsState(
        targetValue   = interactions.stateLayerAlpha,
        animationSpec = tween(durationMillis = if (interactions.isPressed) 0 else 150),
        label         = "radio_state_alpha"
    )

    // M3 spec: slight scale spring on selection change
    val scale by animateFloatAsState(
        targetValue   = if (selected) 1f else 0.9f,
        animationSpec = spring(dampingRatio = 0.45f, stiffness = 650f),
        label         = "radio_scale"
    )

    RadioButton(
        selected          = selected,
        onClick           = null,         // row handles click
        interactionSource = interactionSource,
        colors            = RadioButtonDefaults.colors(
            selectedColor   = ringColor,
            unselectedColor = ColorGray
        ),
        modifier = Modifier
            .scale(scale)
            .drawBehind {
                if (stateLayerAlpha > 0f) {
                    // State layer circle — 20dp radius per M3 spec (40dp touch target / 2)
                    drawCircle(
                        color  = stateLayerColor.copy(alpha = stateLayerAlpha),
                        radius = 20.dp.toPx(),
                        center = center
                    )
                }
            }
    )
}

// ── M3 Checkbox ──────────────────────────────────────────────────────────────

@Composable
private fun M3Checkbox(
    checked: Boolean,
    interactionSource: MutableInteractionSource
) {
    val interactions = rememberInteractionState(interactionSource)

    // ── Container (buttonTint) ───────────────────────────────────────────────
    // MD button_tint.xml: disabled=onSurface@38%, checked=colorPrimary, else=colorOnSurface
    val containerFill by animateColorAsState(
        targetValue   = if (checked) ColorPrimary else Color.Transparent,
        animationSpec = tween(90),
        label         = "cb_fill"
    )
    val borderColor by animateColorAsState(
        targetValue   = if (checked) ColorPrimary else ColorGray,
        animationSpec = tween(100),
        label         = "cb_border"
    )

    // ── Icon (buttonIconTint) ────────────────────────────────────────────────
    // MD button_icon_tint.xml: disabled=colorSurface, checked=colorOnPrimary (white)
    // Animatable for path-trim draw animation
    val checkProgress = remember { Animatable(if (checked) 1f else 0f) }

    LaunchedEffect(checked) {
        if (checked) {
            // Fill comes from animateColorAsState (instant via tween 90ms)
            // Then draw checkmark via path trim
            checkProgress.snapTo(0f)
            checkProgress.animateTo(
                targetValue    = 1f,
                animationSpec  = tween(durationMillis = 150)
            )
        } else {
            // Erase checkmark first, then fill drains via animateColorAsState
            checkProgress.animateTo(
                targetValue   = 0f,
                animationSpec = tween(durationMillis = 80)
            )
        }
    }

    // ── State layer ──────────────────────────────────────────────────────────
    // M3 spec: circle behind container, 20dp radius
    // Colour = colorPrimary if checked, colorOnSurface if unchecked
    val stateLayerColor = if (checked) ColorPrimary else ColorOnSurface
    val stateLayerAlpha by animateFloatAsState(
        targetValue   = interactions.stateLayerAlpha,
        animationSpec = tween(durationMillis = if (interactions.isPressed) 0 else 150),
        label         = "cb_state_alpha"
    )

    Box(
        modifier = Modifier
            .size(20.dp)
            .drawBehind {
                // Layer 1 — state layer (drawn first, behind everything)
                if (stateLayerAlpha > 0f) {
                    drawCircle(
                        color  = stateLayerColor.copy(alpha = stateLayerAlpha),
                        radius = 20.dp.toPx(),
                        center = center
                    )
                }

                // Layer 2 — container (buttonCompat)
                drawContainer(
                    fillColor    = containerFill,
                    borderColor  = borderColor,
                    strokeWidth  = 2.dp.toPx(),
                    cornerRadius = 2.dp.toPx()
                )

                // Layer 3 — icon (buttonIcon), drawn on top
                if (checkProgress.value > 0f) {
                    drawIcon(progress = checkProgress.value)
                }
            }
    )
}

// ── Interaction state helper ──────────────────────────────────────────────────

private data class InteractionState(
    val isPressed: Boolean,
    val isHovered: Boolean,
    val isFocused: Boolean
) {
    // M3 spec priority: pressed=12%, focused=12%, hovered=8%, else=0%
    val stateLayerAlpha: Float get() = when {
        isPressed -> ALPHA_PRESSED
        isFocused -> ALPHA_FOCUSED
        isHovered -> ALPHA_HOVERED
        else      -> 0f
    }
}

@Composable
private fun rememberInteractionState(
    source: MutableInteractionSource
): InteractionState {
    val isPressed by source.collectIsPressedAsState()
    val isHovered by source.collectIsHoveredAsState()
    val isFocused by source.collectIsFocusedAsState()
    return InteractionState(isPressed, isHovered, isFocused)
}

// ── Canvas draw helpers ───────────────────────────────────────────────────────

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
        drawRoundRect(color = fillColor, topLeft = topLeft, size = boxSize, cornerRadius = radius)
    }
    drawRoundRect(
        color        = borderColor,
        topLeft      = topLeft,
        size         = boxSize,
        cornerRadius = radius,
        style        = Stroke(width = strokeWidth)
    )
}

private fun DrawScope.drawIcon(progress: Float) {
    val w = size.width
    val h = size.height

    // M3 checkmark path: left-bottom knee then up-right
    val path = Path().apply {
        moveTo(w * 0.20f, h * 0.50f)
        lineTo(w * 0.42f, h * 0.72f)
        lineTo(w * 0.80f, h * 0.28f)
    }

    // PathMeasure trim: draws 0→progress of the total path length
    val measure = PathMeasure().also { it.setPath(path, false) }
    val trimmed = Path()
    measure.getSegment(0f, measure.length * progress, trimmed, true)

    drawPath(
        path  = trimmed,
        color = Color.White,   // colorOnPrimary per MD button_icon_tint.xml
        style = Stroke(
            width = 2.dp.toPx(),
            cap   = StrokeCap.Round,
            join  = StrokeJoin.Round
        )
    )
}
