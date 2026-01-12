package com.livetvpro.ui.player.dialogs

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.RadioButton
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.livetvpro.R
import com.livetvpro.data.models.LiveEventLink

class LinkSelectionDialog(
    private val context: Context,
    private val links: List<LiveEventLink>,
    private val currentLink: String?,
    private val onLinkSelected: (LiveEventLink) -> Unit
) {

    fun show() {
        // We use a RecyclerView inside the dialog to list the links
        val recyclerView = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context)
            overScrollMode = View.OVER_SCROLL_NEVER
            setPadding(0, 16, 0, 0) // Add a little top padding
        }

        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle("Select Stream") // Standard Dialog Title
            .setView(recyclerView)     // The list of links
            .setNegativeButton("Cancel", null) // Standard Cancel button
            .create()

        val adapter = LinkAdapter(links, currentLink) { selectedLink ->
            onLinkSelected(selectedLink)
            dialog.dismiss()
        }
        recyclerView.adapter = adapter

        dialog.show()
    }

    private class LinkAdapter(
        private val links: List<LiveEventLink>,
        private val currentLink: String?,
        private val onLinkClick: (LiveEventLink) -> Unit
    ) : RecyclerView.Adapter<LinkAdapter.ViewHolder>() {

        inner class ViewHolder(val view: View) : RecyclerView.ViewHolder(view) {
            val title: TextView = view.findViewById(R.id.linkTitle)
            val radioButton: RadioButton = view.findViewById(R.id.linkRadioButton)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_link_option, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val link = links[position]
            holder.title.text = link.label

            // If a link is currently playing, check the radio button
            // If currentLink is null (first load), check the first one optionally, 
            // or leave unchecked. Here we leave unchecked unless it matches.
            val isSelected = currentLink == link.url
            holder.radioButton.isChecked = isSelected

            holder.view.setOnClickListener {
                onLinkClick(link)
            }
        }

        override fun getItemCount() = links.size
    }
}

