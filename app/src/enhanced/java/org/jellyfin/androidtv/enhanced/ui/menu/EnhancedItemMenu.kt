package org.jellyfin.androidtv.enhanced.ui.menu

import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.compose.ui.res.stringResource
import androidx.fragment.app.FragmentActivity
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.Row
import androidx.lifecycle.lifecycleScope
import java.lang.ref.WeakReference
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.constant.QueryType
import org.jellyfin.androidtv.enhanced.data.EnhancedRepository
import org.jellyfin.androidtv.enhanced.data.HiddenContentRepository
import org.jellyfin.androidtv.enhanced.data.HomeSurface
import org.jellyfin.androidtv.enhanced.data.SpoilerGuardRepository
import org.jellyfin.androidtv.enhanced.management.errorMessage
import org.jellyfin.androidtv.enhanced.metadata.showDialogHost
import org.jellyfin.androidtv.enhanced.ui.HideFlow
import org.jellyfin.androidtv.enhanced.ui.design.JeConfirmDialog
import org.jellyfin.androidtv.ui.itemhandling.BaseRowItem
import org.jellyfin.androidtv.ui.itemhandling.ItemRowAdapter
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.koin.java.KoinJavaComponent.inject
import timber.log.Timber

/**
 * Jellyfin Enhanced entries for the card context menu (long-press OK or the menu key):
 * "Remove from Continue Watching" / "Remove from Next Up" on those rows, "Hide" everywhere, and Spoiler Guard on
 * collections (which open in the collection browser, so they have no details button).
 */
object EnhancedItemMenu {
	private val enhancedRepository by inject<EnhancedRepository>(EnhancedRepository::class.java)
	private val hiddenContentRepository by inject<HiddenContentRepository>(HiddenContentRepository::class.java)
	private val spoilerGuardRepository by inject<SpoilerGuardRepository>(SpoilerGuardRepository::class.java)

	// Menu ids well clear of KeyProcessor's MENU_* constants
	private const val MENU_REMOVE_CONTINUE_WATCHING = 9001
	private const val MENU_REMOVE_NEXT_UP = 9002
	private const val MENU_HIDE = 9003
	private const val MENU_SPOILER_GUARD = 9004

	/**
	 * Items hidden from the menu, for rows that don't use an [ItemRowAdapter] (the Jellyfin Enhanced home rows) and
	 * drop the matching cards themselves. Main thread only; nobody collecting means nobody needs it.
	 */
	private val _hidden = MutableSharedFlow<HiddenItem>(extraBufferCapacity = 8)
	val hidden: SharedFlow<HiddenItem> = _hidden.asSharedFlow()

	/** An item just hidden everywhere: [id] is the item, or its show when the whole series was hidden. */
	data class HiddenItem(val id: UUID)

	private var selectedItem = WeakReference<BaseRowItem>(null)
	private var selectedRow = WeakReference<ListRow>(null)

	/** The collection Spoiler Guard entry's label as shown: the state it offers to switch to, null when unknown. */
	private var spoilerMenuItem = WeakReference<BaseRowItem>(null)
	private var spoilerTarget: Boolean? = null

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

		val item = rowItem.baseItem
		// Collections can only be hidden with the user's experimental "hide collections" option, as on the web
		val hideable = item?.type != BaseItemKind.BOX_SET || state.hiddenSettings?.experimentalHideCollections == true
		if (state.hideControls && hideable) menu.add(0, MENU_HIDE, position++, R.string.je_hide)

		if (state.spoilerGuard && item?.type == BaseItemKind.BOX_SET) {
			// The menu is built synchronously, so the label comes from the last state read; the click re-checks it
			val guarded = spoilerGuardRepository.cachedState()?.let { spoilerGuardRepository.isGuarded(it, SpoilerGuardRepository.Kind.COLLECTION, item.id) }
			spoilerMenuItem = WeakReference(rowItem)
			spoilerTarget = guarded?.not()
			menu.add(0, MENU_SPOILER_GUARD, position, when (guarded) {
				true -> R.string.je_management_sg_menu_off
				false -> R.string.je_management_sg_menu_on
				null -> R.string.je_management_sg_menu
			})
		}
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
							// A toast can't hold an Undo button on TV (no Snackbar here), so point to where it can be restored
							Toast.makeText(
								activity,
								if (surface == HomeSurface.CONTINUE_WATCHING) R.string.je_management_removed_continue_watching else R.string.je_management_removed_next_up,
								Toast.LENGTH_LONG,
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
								_hidden.tryEmit(HiddenItem(if (wholeSeries) item.seriesId ?: item.id else item.id))
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

			MENU_SPOILER_GUARD -> {
				val target = spoilerTarget.takeIf { spoilerMenuItem.get() === rowItem }
				toggleCollectionGuard(activity, item, target)
			}

			else -> return false
		}
		return true
	}

	/**
	 * Switch Spoiler Guard for a collection to [target] (what the menu offered), after re-reading the current state.
	 * With an unknown [target] the current state is shown first and the user picks. Turning protection off asks for
	 * confirmation unless the user chose to skip it.
	 */
	private fun toggleCollectionGuard(activity: FragmentActivity, item: BaseItemDto, target: Boolean?) {
		activity.lifecycleScope.launch {
			val state = try {
				spoilerGuardRepository.getState()
			} catch (err: CancellationException) {
				throw err
			} catch (err: Exception) {
				Timber.w(err, "Failed to load Spoiler Guard state")
				Toast.makeText(activity, errorMessage(activity, err), Toast.LENGTH_LONG).show()
				return@launch
			}
			val guarded = spoilerGuardRepository.isGuarded(state, SpoilerGuardRepository.Kind.COLLECTION, item.id)

			fun apply(enable: Boolean) = activity.lifecycleScope.launch {
				try {
					spoilerGuardRepository.setGuarded(SpoilerGuardRepository.Kind.COLLECTION, item.id, item.name, enable)
					Toast.makeText(activity, if (enable) R.string.je_spoiler_guard_on else R.string.je_spoiler_guard_off, Toast.LENGTH_SHORT).show()
					// Warm the cache so the next menu shows the right label
					runCatching { spoilerGuardRepository.getState() }
				} catch (err: CancellationException) {
					throw err
				} catch (err: Exception) {
					Timber.w(err, "Failed to toggle Spoiler Guard for collection %s", item.id)
					Toast.makeText(activity, errorMessage(activity, err), Toast.LENGTH_LONG).show()
				}
			}

			when {
				target == null -> showDialogHost(activity) { visible, dismiss ->
					JeConfirmDialog(
						visible = visible,
						title = item.name.orEmpty(),
						body = stringResource(if (guarded) R.string.je_management_sg_collection_on else R.string.je_management_sg_collection_off),
						confirmLabel = stringResource(if (guarded) R.string.je_management_sg_turn_off else R.string.je_management_sg_turn_on),
						// Turning protection off is the risky direction: focus starts on Cancel then
						destructive = guarded,
						onConfirm = { apply(!guarded) },
						onDismissRequest = dismiss,
					)
				}

				// Already there (changed elsewhere since the label was built)
				target == guarded -> Toast.makeText(activity, if (guarded) R.string.je_spoiler_guard_on else R.string.je_spoiler_guard_off, Toast.LENGTH_SHORT).show()

				target -> apply(true)

				state.prefs.skipDisableConfirm -> apply(false)

				else -> showDialogHost(activity) { visible, dismiss ->
					JeConfirmDialog(
						visible = visible,
						title = stringResource(R.string.je_spoiler_disable_title),
						body = stringResource(R.string.je_spoiler_disable_message),
						confirmLabel = stringResource(R.string.je_spoiler_disable_confirm),
						destructive = true,
						onConfirm = { apply(false) },
						onDismissRequest = dismiss,
					)
				}
			}
		}
	}

	private fun removeFromRow(row: ListRow?, rowItem: BaseRowItem) {
		val adapter = row?.adapter as? ItemRowAdapter ?: return
		val index = adapter.indexOf(rowItem)
		if (index >= 0) adapter.removeAt(index)
	}
}
