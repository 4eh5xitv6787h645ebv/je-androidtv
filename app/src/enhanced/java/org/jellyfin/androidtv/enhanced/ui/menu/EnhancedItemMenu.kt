package org.jellyfin.androidtv.enhanced.ui.menu

import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.Row
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.constant.QueryType
import org.jellyfin.androidtv.enhanced.data.EnhancedRepository
import org.jellyfin.androidtv.enhanced.data.HiddenContentRepository
import org.jellyfin.androidtv.enhanced.data.HomeSurface
import org.jellyfin.androidtv.enhanced.ui.HideFlow
import org.jellyfin.androidtv.ui.itemhandling.BaseRowItem
import org.jellyfin.androidtv.ui.itemhandling.ItemRowAdapter
import org.koin.java.KoinJavaComponent.inject
import timber.log.Timber
import java.lang.ref.WeakReference

/**
 * Jellyfin Enhanced entries for the card context menu (long-press OK or the menu key):
 * "Remove from Continue Watching" / "Remove from Next Up" on those rows, and "Hide" everywhere.
 */
object EnhancedItemMenu {
	private val enhancedRepository by inject<EnhancedRepository>(EnhancedRepository::class.java)
	private val hiddenContentRepository by inject<HiddenContentRepository>(HiddenContentRepository::class.java)

	// Menu ids well clear of KeyProcessor's MENU_* constants
	private const val MENU_REMOVE_CONTINUE_WATCHING = 9001
	private const val MENU_REMOVE_NEXT_UP = 9002
	private const val MENU_HIDE = 9003

	private var selectedItem = WeakReference<BaseRowItem>(null)
	private var selectedRow = WeakReference<ListRow>(null)

	/** Remember which row the focused card belongs to, so the menu knows whether it's Continue Watching or Next Up. */
	@JvmStatic
	fun onItemSelected(item: Any?, row: Row?) {
		selectedItem = WeakReference(item as? BaseRowItem)
		selectedRow = WeakReference(row as? ListRow)
	}

	private fun surfaceFor(rowItem: BaseRowItem): HomeSurface? {
		if (selectedItem.get() !== rowItem) return null
		return when ((selectedRow.get()?.adapter as? ItemRowAdapter)?.queryType) {
			QueryType.Resume -> HomeSurface.CONTINUE_WATCHING
			QueryType.NextUp -> HomeSurface.NEXT_UP
			else -> null
		}
	}

	/** Add the Jellyfin Enhanced entries to a card menu built by KeyProcessor. */
	@JvmStatic
	fun contribute(menu: Menu, rowItem: BaseRowItem, order: Int) {
		val state = enhancedRepository.current
		if (!state.available || rowItem.baseItem == null) return
		var position = order

		when (surfaceFor(rowItem)) {
			HomeSurface.CONTINUE_WATCHING -> if (state.removeFromHome) menu.add(0, MENU_REMOVE_CONTINUE_WATCHING, position++, R.string.je_remove_continue_watching)
			HomeSurface.NEXT_UP -> if (state.removeFromHome) menu.add(0, MENU_REMOVE_NEXT_UP, position++, R.string.je_remove_next_up)
			null -> Unit
		}

		if (state.hideControls) menu.add(0, MENU_HIDE, position, R.string.je_hide)
	}

	/** Handle a click on one of our entries. Returns false for entries that aren't ours. */
	@JvmStatic
	fun onMenuItemClick(menuItem: MenuItem, rowItem: BaseRowItem, activity: FragmentActivity): Boolean {
		val item = rowItem.baseItem ?: return false
		val row = selectedRow.get().takeIf { selectedItem.get() === rowItem }

		when (menuItem.itemId) {
			MENU_REMOVE_CONTINUE_WATCHING, MENU_REMOVE_NEXT_UP -> {
				val surface = if (menuItem.itemId == MENU_REMOVE_CONTINUE_WATCHING) HomeSurface.CONTINUE_WATCHING else HomeSurface.NEXT_UP
				activity.lifecycleScope.launch {
					runCatching { hiddenContentRepository.removeFromHome(item.id, surface) }
						.onSuccess {
							removeFromRow(row, rowItem)
							Toast.makeText(
								activity,
								if (surface == HomeSurface.CONTINUE_WATCHING) R.string.je_removed_continue_watching else R.string.je_removed_next_up,
								Toast.LENGTH_SHORT,
							).show()
						}
						.onFailure {
							Timber.w(it, "Failed to remove %s from %s", item.id, surface)
							Toast.makeText(activity, R.string.je_error_generic, Toast.LENGTH_SHORT).show()
						}
				}
			}

			MENU_HIDE -> {
				val askConfirmation = enhancedRepository.current.hiddenSettings?.showHideConfirmation ?: true
				HideFlow.confirm(activity, item, askConfirmation) { wholeSeries ->
					activity.lifecycleScope.launch {
						runCatching { hiddenContentRepository.hide(item, wholeSeries) }
							.onSuccess {
								removeFromRow(row, rowItem)
								val name = if (wholeSeries) item.seriesName ?: item.name else item.name
								Toast.makeText(activity, activity.getString(R.string.je_hidden_toast, name.orEmpty()), Toast.LENGTH_SHORT).show()
							}
							.onFailure {
								Timber.w(it, "Failed to hide %s", item.id)
								Toast.makeText(activity, R.string.je_error_generic, Toast.LENGTH_SHORT).show()
							}
					}
				}
			}

			else -> return false
		}
		return true
	}

	private fun removeFromRow(row: ListRow?, rowItem: BaseRowItem) {
		val adapter = row?.adapter as? ItemRowAdapter ?: return
		val index = adapter.indexOf(rowItem)
		if (index >= 0) adapter.removeAt(index)
	}
}
