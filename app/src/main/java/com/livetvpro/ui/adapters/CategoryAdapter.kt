package com.livetvpro.ui.adapters

import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.livetvpro.R
import com.livetvpro.utils.GlideExtensions
import com.livetvpro.data.models.Category
import com.livetvpro.databinding.ItemCategoryBinding

class CategoryAdapter(
    private val onCategoryClick: (Category) -> Unit
) : ListAdapter<Category, CategoryAdapter.CategoryViewHolder>(CategoryDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CategoryViewHolder {
        val binding = ItemCategoryBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return CategoryViewHolder(binding)
    }

    override fun onBindViewHolder(holder: CategoryViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class CategoryViewHolder(
        private val binding: ItemCategoryBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        init {
            // ── Click ────────────────────────────────────────────────────────
            binding.root.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) onCategoryClick(getItem(pos))
            }

            // ── D-pad / remote key handler ───────────────────────────────────
            // OK / Enter on a remote fires the same action as a tap.
            binding.root.setOnKeyListener { _, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                val pos = bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION) return@setOnKeyListener false
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_CENTER,
                    KeyEvent.KEYCODE_ENTER,
                    KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                        onCategoryClick(getItem(pos))
                        true
                    }
                    else -> false
                }
            }

            // ── TV focus ring ────────────────────────────────────────────────
            binding.root.isFocusable            = true
            binding.root.isFocusableInTouchMode = false

            binding.root.setOnFocusChangeListener { view, hasFocus ->
                view.animate()
                    .scaleX(if (hasFocus) 1.05f else 1f)
                    .scaleY(if (hasFocus) 1.05f else 1f)
                    .setDuration(120)
                    .start()
                view.elevation = if (hasFocus) 8f else 0f
            }
        }

        fun bind(category: Category) {
            binding.categoryName.text = category.name

            if (category.iconUrl.isNullOrEmpty()) {
                binding.categoryIcon.setImageResource(R.mipmap.ic_launcher_round)
            } else {
                GlideExtensions.loadImage(
                    binding.categoryIcon,
                    category.iconUrl,
                    R.mipmap.ic_launcher_round,
                    R.mipmap.ic_launcher_round
                )
            }
        }
    }

    private class CategoryDiffCallback : DiffUtil.ItemCallback<Category>() {
        override fun areItemsTheSame(oldItem: Category, newItem: Category) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: Category, newItem: Category) = oldItem == newItem
    }
}
