package org.jellyfin.androidtv.enhanced.ui.seerr

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.PopupMenu
import androidx.fragment.app.FragmentActivity
import org.jellyfin.androidtv.enhanced.data.EnhancedState
import org.jellyfin.androidtv.enhanced.seerr.SeerrResult
import org.jellyfin.androidtv.util.getActivity

/** One entry of the long-press menu on a Seerr card. */
class SeerrCardMenuItem(val label: String, val onClick: () -> Unit)

/**
 * A feature's entries for the long-press (menu key) menu on Seerr cards: hide this title, report a problem, add a
 * bookmark… Registered with Koin as `SeerrCardMenuContributor`.
 */
interface SeerrCardMenuContributor {
	fun items(activity: FragmentActivity, result: SeerrResult, state: EnhancedState, onRemoved: () -> Unit): List<SeerrCardMenuItem>
}

/** Builds and shows the Seerr card menu from the registered contributors. */
object SeerrCardMenu {
	private val contributors: List<SeerrCardMenuContributor>
		get() = org.koin.java.KoinJavaComponent.getKoin().getAll()

	/**
	 * Show the menu for [result] anchored to [anchor]. [onRemoved] lets a contributor drop the card from its row
	 * (after hiding it, say). Returns false when no contributor had an entry.
	 */
	fun show(context: Context, anchor: View, result: SeerrResult, state: EnhancedState, onRemoved: () -> Unit): Boolean {
		val activity = context.getActivity() as? FragmentActivity ?: return false
		val items = contributors.flatMap { it.items(activity, result, state, onRemoved) }
		if (items.isEmpty()) return false
		PopupMenu(context, anchor, Gravity.END).apply {
			items.forEachIndexed { index, item -> menu.add(0, index, index, item.label) }
			setOnMenuItemClickListener { menuItem -> items[menuItem.itemId].onClick(); true }
		}.show()
		return true
	}
}
