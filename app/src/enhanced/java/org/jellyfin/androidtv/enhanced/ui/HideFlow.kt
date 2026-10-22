package org.jellyfin.androidtv.enhanced.ui

import android.app.AlertDialog
import android.content.Context
import org.jellyfin.androidtv.R
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind

/** The confirmation step before hiding an item, shared by the details button and the card menu. */
object HideFlow {
	/**
	 * Ask the user to confirm hiding [item]. Episodes and seasons always ask whether to hide just that item or the
	 * whole show; other items only ask when [askConfirmation] (the user's "show hide confirmation" setting) is on.
	 * [onConfirmed] receives true when the whole show should be hidden.
	 */
	fun confirm(context: Context, item: BaseItemDto, askConfirmation: Boolean, onConfirmed: (wholeSeries: Boolean) -> Unit) {
		val title = context.getString(R.string.je_hide_confirm_title, item.name.orEmpty())
		val hasSeries = (item.type == BaseItemKind.EPISODE || item.type == BaseItemKind.SEASON) && item.seriesId != null

		when {
			hasSeries -> {
				val thisLabel = context.getString(if (item.type == BaseItemKind.EPISODE) R.string.je_hide_this_episode else R.string.je_hide_this_season)
				AlertDialog.Builder(context)
					.setTitle(title)
					.setItems(arrayOf(thisLabel, context.getString(R.string.je_hide_whole_show))) { _, which -> onConfirmed(which == 1) }
					.setNegativeButton(android.R.string.cancel, null)
					.show()
			}

			askConfirmation -> AlertDialog.Builder(context)
				.setTitle(title)
				.setMessage(R.string.je_hide_confirm_message)
				.setPositiveButton(R.string.je_hide) { _, _ -> onConfirmed(false) }
				.setNegativeButton(android.R.string.cancel, null)
				.show()

			else -> onConfirmed(false)
		}
	}
}
