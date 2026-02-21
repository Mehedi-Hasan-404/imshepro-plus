package com.livetvpro.ui.adapters

import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.livetvpro.R
import com.livetvpro.utils.GlideExtensions
import com.livetvpro.data.models.Channel
import com.livetvpro.databinding.ItemChannelBinding

class ChannelAdapter(
    private val onChannelClick: (Channel) -> Unit,
    private val onFavoriteToggle: (Channel) -> Unit,
    private val isFavorite: (String) -> Boolean
) : ListAdapter<Channel, ChannelAdapter.ChannelViewHolder>(ChannelDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChannelViewHolder {
        val binding = ItemChannelBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ChannelViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ChannelViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ChannelViewHolder(
        private val binding: ItemChannelBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        init {
            // ── Click: open player ──────────────────────────────────────────
            binding.root.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) onChannelClick(getItem(pos))
            }

            // ── Long-click: favorites dialog (touch devices) ─────────────────
            binding.root.setOnLongClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) showFavoriteDialog(getItem(pos))
                true
            }

            // ── D-pad / remote key handler ───────────────────────────────────
            // KEYCODE_DPAD_CENTER / KEYCODE_ENTER  → open player  (same as click)
            // KEYCODE_MENU                         → favorites dialog (same as long-click)
            // Any other key is forwarded to the RecyclerView for D-pad navigation.
            binding.root.setOnKeyListener { _, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                val pos = bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION) return@setOnKeyListener false
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_CENTER,
                    KeyEvent.KEYCODE_ENTER,
                    KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                        onChannelClick(getItem(pos))
                        true
                    }
                    KeyEvent.KEYCODE_MENU -> {
                        showFavoriteDialog(getItem(pos))
                        true
                    }
                    else -> false
                }
            }

            // ── TV focus ring ────────────────────────────────────────────────
            // Make items focusable so D-pad can navigate the list on TV.
            binding.root.isFocusable     = true
            binding.root.isFocusableInTouchMode = false

            binding.root.setOnFocusChangeListener { view, hasFocus ->
                // Scale + elevation feedback so the focused item stands out
                view.animate()
                    .scaleX(if (hasFocus) 1.05f else 1f)
                    .scaleY(if (hasFocus) 1.05f else 1f)
                    .setDuration(120)
                    .start()
                view.elevation = if (hasFocus) 8f else 0f
            }
        }

        private fun showFavoriteDialog(channel: Channel) {
            val context  = binding.root.context
            val isFav    = isFavorite(channel.id)
            val title    = if (isFav) "Remove from Favorites?" else "Add to Favorites?"
            val message  = if (isFav)
                "Remove \"${channel.name}\" from favorites?"
            else
                "Add \"${channel.name}\" to favorites?"
            val posBtnLabel = if (isFav) "Remove" else "Add"

            MaterialAlertDialogBuilder(context)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(posBtnLabel) { dialog, _ ->
                    onFavoriteToggle(channel)
                    dialog.dismiss()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        fun bind(channel: Channel) {
            binding.channelName.text = channel.name

            // CRITICAL: Enable marquee scrolling
            binding.channelName.isSelected = true

            val isFav = isFavorite(channel.id)
            binding.favoriteIndicator.visibility =
                if (isFav) android.view.View.VISIBLE else android.view.View.GONE

            GlideExtensions.loadImage(
                binding.channelLogo,
                channel.logoUrl,
                R.mipmap.ic_launcher_round,
                R.mipmap.ic_launcher_round
            )
        }
    }

    private class ChannelDiffCallback : DiffUtil.ItemCallback<Channel>() {
        override fun areItemsTheSame(oldItem: Channel, newItem: Channel) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: Channel, newItem: Channel) = oldItem == newItem
    }

    fun refreshItem(channelId: String) {
        val position = currentList.indexOfFirst { it.id == channelId }
        if (position != -1) notifyItemChanged(position)
    }

    fun refreshAll() {
        notifyDataSetChanged()
    }
}
