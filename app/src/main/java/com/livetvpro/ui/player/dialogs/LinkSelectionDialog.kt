package com.livetvpro.ui.player.dialogs

import android.content.Context
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.livetvpro.data.models.LiveEventLink

class LinkSelectionDialog(
    private val context: Context,
    private val links: List<LiveEventLink>,
    private val currentLink: String?, // Kept for compatibility, though standard list doesn't show selection state
    private val onLinkSelected: (LiveEventLink) -> Unit
) {

    fun show() {
        // Convert the list of link objects to a simple array of Strings (labels)
        val labels = links.map { it.quality }.toTypedArray()

        MaterialAlertDialogBuilder(context)
            .setTitle("Select Stream")
            .setItems(labels) { dialog, which ->
                // 'which' is the index of the item clicked
                if (which in links.indices) {
                    onLinkSelected(links[which])
                }
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}


