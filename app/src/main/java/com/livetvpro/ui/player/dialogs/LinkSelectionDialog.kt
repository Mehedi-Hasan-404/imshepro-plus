package com.livetvpro.ui.player.dialogs

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
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
        // Create a RecyclerView for the list of options
        val recyclerView = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context)
            overScrollMode = View.OVER_SCROLL_NEVER
            // Minimal padding top/bottom to look like a standard list dialog
            setPadding(0, 8, 0, 8) 
        }

        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle("Select Stream")
            .setView(recyclerView)
            .setNegativeButton("Cancel", null)
            .create()

        val adapter = LinkAdapter(links) { selectedLink ->
            onLinkSelected(selectedLink)
            dialog.dismiss()
        }
        recyclerView.adapter = adapter

        dialog.show()
    }

    private class LinkAdapter(
        private val links: List<LiveEventLink>,
        private val onLinkClick: (LiveEventLink) -> Unit
    ) : RecyclerView.Adapter<LinkAdapter.ViewHolder>() {

        inner class ViewHolder(val view: View) : RecyclerView.ViewHolder(view) {
            val title: TextView = view.findViewById(R.id.linkTitle)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_link_option, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val link = links[position]
            holder.title.text = link.label

            holder.view.setOnClickListener {
                onLinkClick(link)
            }
        }

        override fun getItemCount() = links.size
    }
}


