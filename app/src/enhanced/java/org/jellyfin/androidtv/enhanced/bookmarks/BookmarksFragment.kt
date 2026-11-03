package org.jellyfin.androidtv.enhanced.bookmarks

import android.app.AlertDialog
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import androidx.fragment.compose.content
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.enhanced.calendar.attempt
import org.jellyfin.androidtv.enhanced.calendar.serverMessage
import org.jellyfin.androidtv.enhanced.data.EnhancedRepository
import org.jellyfin.androidtv.enhanced.data.SpoilerGuardRepository
import org.jellyfin.androidtv.enhanced.data.SpoilerProtection
import org.jellyfin.androidtv.enhanced.data.SpoilerSnapshot
import org.jellyfin.androidtv.enhanced.ui.design.JeSpace
import org.jellyfin.androidtv.enhanced.ui.design.ListRow
import org.jellyfin.androidtv.enhanced.ui.design.LoadStateContent
import org.jellyfin.androidtv.enhanced.ui.design.Notice
import org.jellyfin.androidtv.enhanced.ui.design.NoticeKind
import org.jellyfin.androidtv.enhanced.ui.design.PageScaffold
import org.jellyfin.androidtv.enhanced.ui.design.RowAction
import org.jellyfin.androidtv.enhanced.ui.design.RowDensity
import org.jellyfin.androidtv.enhanced.ui.design.RowLeading
import org.jellyfin.androidtv.enhanced.ui.design.SkeletonShape
import org.jellyfin.androidtv.enhanced.ui.hub.ListFocus
import org.jellyfin.androidtv.enhanced.ui.hub.LoadState
import org.jellyfin.androidtv.ui.navigation.Destinations
import org.jellyfin.androidtv.ui.navigation.NavigationRepository
import org.jellyfin.androidtv.util.PlaybackHelper
import org.jellyfin.sdk.model.serializer.toUUIDOrNull
import org.koin.android.ext.android.inject
import timber.log.Timber
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.UUID

/**
 * The user's JE timestamp bookmarks, newest first. OK plays the item from the saved moment; long-press OK, Menu or
 * the row's "⋯" button (for remotes without long-press) offers Play from here, Open details, Rename and Delete.
 * Bookmarks whose item is gone are marked and offer deletion. Episode names follow Spoiler Guard
 * ([BookmarkLogic.name]): the name saved with a bookmark is never shown for a possibly guarded episode.
 *
 * Renames and deletes change the loaded list in place, so the focus stays where it was; after a delete it moves to
 * the next row (or the previous one, or the empty state's Back), never to the toolbar.
 */
class BookmarksFragment : Fragment() {
	private val bookmarksRepository by inject<BookmarksRepository>()
	private val navigationRepository by inject<NavigationRepository>()
	private val playbackHelper by inject<PlaybackHelper>()
	private val spoilerProtection by inject<SpoilerProtection>()
	private val enhancedRepository by inject<EnhancedRepository>()
	private val spoilerGuardRepository by inject<SpoilerGuardRepository>()

	private class BookmarkRow(
		val entry: BookmarkEntry,
		val itemId: UUID?,
		/** The item as the server returns it for this user; null when stale or not checked. */
		val item: BookmarkedItem?,
		/** True only when the server answered and the item wasn't there. */
		val stale: Boolean,
		val name: BookmarkName,
	) {
		val key get() = entry.bookmarkId

		fun withBookmark(bookmark: Bookmark) = BookmarkRow(BookmarkEntry(entry.bookmarkId, bookmark), itemId, item, stale, name)

		/** After the item lookup: [found] is the item, or null when the server answered without it. */
		fun resolved(found: BookmarkedItem?, spoiler: SpoilerSnapshot) =
			BookmarkRow(entry, itemId, found, stale = found == null, name = BookmarkLogic.name(entry.bookmark, found, spoiler))

		/**
		 * Everything that may have changed with the user's protection withheld at once, before the list is read again:
		 * episode names become neutral and artwork, whose image tags predate the change, goes away.
		 */
		fun neutralized() = BookmarkRow(
			entry,
			itemId,
			item?.copy(posterUrl = null),
			stale,
			BookmarkLogic.name(entry.bookmark, item, SpoilerSnapshot.UNKNOWN),
		)
	}

	/**
	 * [generation] counts full loads, so local edits and background refreshes (which keep it) don't re-run the initial
	 * focus.
	 */
	private data class Loaded(val rows: List<BookmarkRow>, val resolveFailed: Boolean, val generation: Int)

	private val loadState = MutableStateFlow<LoadState<Loaded>>(LoadState.Loading)
	private val busy = MutableStateFlow<Set<String>>(emptySet())
	private val listFocus = ListFocus()
	private var loadJob: Job? = null
	private var generation = 0
	/** [SpoilerGuardRepository.generation] when the shown list was loaded. */
	private var spoilerGeneration = -1L
	/** Whether the page has been started before: later starts are returns (from playback, details, or to the app). */
	private var started = false
	/** The bookmark that last had the focus, so returning to the page (or restoring it) lands on it again. */
	private var selectedKey: String? = null

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		selectedKey = savedInstanceState?.getString(STATE_SELECTED)
		reload()
	}

	override fun onStart() {
		super.onStart()
		if (!started) {
			started = true
			return
		}
		// Back from playback or details: a bookmark may have been added there, or its title guarded
		val protectionChanged = spoilerGuardRepository.generation != spoilerGeneration
		when (loadState.value) {
			is LoadState.Ready -> {
				if (protectionChanged) updateRows { rows -> rows.map { it.neutralized() } }
				reload(refresh = true, reuseItems = !protectionChanged)
			}
			LoadState.Loading -> if (protectionChanged) reload()
			else -> Unit
		}
	}

	override fun onSaveInstanceState(outState: Bundle) {
		super.onSaveInstanceState(outState)
		outState.putString(STATE_SELECTED, selectedKey)
	}

	/**
	 * Lists the bookmarks, shows them, then looks their items up in batches from the top so the first rows fill in
	 * first ([BookmarkLogic.lookupBatches]).
	 *
	 * [refresh] keeps the current list on screen (and the focus where it is) until the new one is ready; with
	 * [reuseItems] its rows keep their looked-up items meanwhile, which is only safe while Spoiler Guard hasn't changed.
	 */
	private fun reload(retry: Boolean = false, refresh: Boolean = false, reuseItems: Boolean = false) {
		loadJob?.cancel()
		val current = (loadState.value as? LoadState.Ready)?.value
		if (!refresh || current == null) loadState.value = LoadState.Loading
		spoilerGeneration = spoilerGuardRepository.generation
		val previous = if (reuseItems) current?.rows.orEmpty().associateBy { it.key } else emptyMap()
		loadJob = lifecycleScope.launch {
			val listed = attempt {
				// Retry must not wait out the unknown-config backoff
				if (retry && enhancedRepository.current.configUnavailable) enhancedRepository.ensureLoaded(force = true)
				val entries = bookmarksRepository.list()
				val spoiler = spoilerProtection.snapshot()
				// Every name would be withheld: offer a retry instead of a list of neutral labels
				if (spoiler.configUnavailable) error("Jellyfin Enhanced configuration unavailable")
				entries to spoiler
			}
			val (entries, spoiler) = listed.getOrElse { err ->
				Timber.w(err, "Failed to load bookmarks")
				loadState.value = LoadState.Error(err.serverMessage) { reload(retry = true) }
				return@launch
			}
			val rows = entries.map { entry ->
				val itemId = entry.bookmark.itemId.toUUIDOrNull()
				val known = previous[entry.bookmarkId]?.takeIf { it.itemId == itemId }
				BookmarkRow(entry, itemId, known?.item, stale = known?.stale == true, name = BookmarkLogic.name(entry.bookmark, known?.item, spoiler))
			}
			val shownGeneration = if (refresh && current != null) current.generation else ++generation
			loadState.value = LoadState.Ready(Loaded(rows, resolveFailed = false, generation = shownGeneration))

			for (batch in BookmarkLogic.lookupBatches(entries, BookmarksRepository.LOOKUP_CHUNK)) {
				val resolved = attempt { bookmarksRepository.resolve(batch) }
					.onFailure { Timber.w(it, "Failed to look up bookmarked items") }
					.getOrNull()
				val ids = batch.toSet()
				loadState.update { state ->
					if (state !is LoadState.Ready) return@update state
					val loaded = state.value
					LoadState.Ready(
						loaded.copy(
							// Rows renamed or deleted meanwhile keep those changes: only the looked-up fields are replaced
							rows = if (resolved == null) loaded.rows else loaded.rows.map { row ->
								if (row.itemId in ids) row.resolved(resolved[row.itemId], spoiler) else row
							},
							resolveFailed = loaded.resolveFailed || resolved == null,
						)
					)
				}
			}
		}
	}

	override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?) = content {
		val state by loadState.collectAsState()
		val busyKeys by busy.collectAsState()
		val count = (state as? LoadState.Ready)?.value?.rows?.size

		PageScaffold(
			title = stringResource(R.string.je_bookmarks_title),
			subtitle = count?.takeIf { it > 0 }?.let { stringResource(R.string.je_bookmarks_count, it) },
		) {
			LoadStateContent(
				state = state,
				empty = { it.rows.isEmpty() },
				emptyMessage = stringResource(R.string.je_bookmarks_empty),
				emptyIcon = R.drawable.je_ic_bookmarks_bookmark,
				loading = SkeletonShape.Rows(density = RowDensity.Large),
			) { loaded ->
				Column(modifier = Modifier.fillMaxSize()) {
					if (loaded.resolveFailed) {
						Notice(
							kind = NoticeKind.Warning,
							title = stringResource(R.string.je_bookmarks_unresolved),
							inline = true,
							modifier = Modifier.padding(start = JeSpace.pageHorizontal, end = JeSpace.pageHorizontal, bottom = 8.dp),
						)
					}
					LazyColumn(
						contentPadding = PaddingValues(start = JeSpace.pageHorizontal, end = JeSpace.pageHorizontal, top = 4.dp, bottom = JeSpace.pageBottom),
						verticalArrangement = Arrangement.spacedBy(JeSpace.rowGap),
					) {
						items(loaded.rows, key = { it.key }) { row ->
							BookmarkItem(row, busy = row.key in busyKeys)
						}
					}
				}
				listFocus.Effect()
				LaunchedEffect(loaded.generation) {
					val selected = selectedKey?.takeIf { key -> loaded.rows.any { it.key == key } }
					listFocus.moveTo(selected ?: loaded.rows.firstOrNull()?.key)
				}
			}
		}
	}

	@Composable
	private fun BookmarkItem(row: BookmarkRow, busy: Boolean) {
		val bookmark = row.entry.bookmark
		val name = displayName(row)
		val label = bookmark.label?.takeIf { it.isNotBlank() }
		val savedOn = remember(bookmark) { savedDate(bookmark) }
		val details = listOfNotNull(
			stringResource(R.string.je_bookmarks_at, BookmarkLogic.formatTimestamp(bookmark.timestampSeconds)),
			savedOn?.let { stringResource(R.string.je_bookmarks_saved_on, it) },
		).joinToString(" · ")
		// The remote's Menu key on the "⋯" button opens the same menu as on the row (ListRow handles the row itself)
		val menuKey = Modifier.onPreviewKeyEvent { event ->
			if (event.key.nativeKeyCode != KeyEvent.KEYCODE_MENU) return@onPreviewKeyEvent false
			if (event.type == KeyEventType.KeyUp) showMenu(row)
			true
		}

		ListRow(
			title = label ?: name,
			leading = RowLeading.Poster(row.item?.posterUrl, R.drawable.je_ic_bookmarks_bookmark),
			supporting = listOfNotNull(name.takeIf { label != null }, if (busy) stringResource(R.string.je_bookmarks_saving) else details),
			warning = if (row.stale) stringResource(R.string.je_bookmarks_stale) else null,
			onClick = { if (!busy) select(row) },
			onMenu = { if (!busy) showMenu(row) },
			// Not disabled while busy: disabling the focused button would throw the focus elsewhere
			actions = listOf(RowAction(R.drawable.ic_more, stringResource(R.string.je_bookmarks_more), { if (!busy) showMenu(row) }, modifier = menuKey)),
			mainModifier = Modifier
				.focusRequester(listFocus.requester(row.key))
				.onFocusChanged { if (it.isFocused) selectedKey = row.key },
			density = RowDensity.Large,
		)
	}

	private fun displayName(row: BookmarkRow): String {
		val neutral = row.name.neutral ?: return row.name.text ?: getString(R.string.je_bookmarks_untitled)
		val season = neutral.seasonNumber
		val episode = neutral.episodeNumber
		val numbers = when {
			season != null && episode != null -> getString(R.string.je_bookmarks_episode_numbered, season, episode)
			episode != null -> getString(R.string.je_bookmarks_episode_number, episode)
			else -> getString(R.string.je_bookmarks_episode)
		}
		return listOfNotNull(neutral.seriesName, numbers).joinToString(" · ")
	}

	private fun title(row: BookmarkRow) = row.entry.bookmark.label?.takeIf { it.isNotBlank() } ?: displayName(row)

	private fun savedDate(bookmark: Bookmark): String? = (bookmark.updatedAt ?: bookmark.createdAt)
		?.let { runCatching { Instant.parse(it) }.getOrNull() }
		?.atZone(ZoneId.systemDefault())
		?.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))

	private fun select(row: BookmarkRow) {
		if (row.stale) confirmStaleDelete(row) else play(row)
	}

	private fun play(row: BookmarkRow) {
		val context = context ?: return
		val itemId = row.itemId ?: return
		// Rows still being looked up play too: the server refuses an item the user can no longer access
		playbackHelper.retrieveAndPlay(listOf(itemId), false, BookmarkLogic.ticks(row.entry.bookmark.timestampSeconds), 0, context)
	}

	/** A closed dialog leaves nothing focused and the toolbar would take it: back to the row, if it is still listed. */
	private fun refocus(row: BookmarkRow) {
		val rows = (loadState.value as? LoadState.Ready)?.value?.rows ?: return
		if (rows.any { it.key == row.key }) listFocus.moveTo(row.key)
	}

	private fun showMenu(row: BookmarkRow) {
		val context = context ?: return
		if (row.key in busy.value) return
		val actions = buildList<Pair<String, () -> Unit>> {
			if (!row.stale && row.itemId != null) {
				add(getString(R.string.je_bookmarks_play_from, BookmarkLogic.formatTimestamp(row.entry.bookmark.timestampSeconds)) to { play(row) })
				add(getString(R.string.je_bookmarks_open_details) to { navigationRepository.navigate(Destinations.itemDetails(row.itemId)) })
			}
			add(getString(R.string.je_bookmarks_rename) to { askRename(row) })
			add(getString(R.string.je_bookmarks_delete) to { confirmDelete(row) })
		}
		AlertDialog.Builder(context)
			.setTitle(title(row))
			.setItems(actions.map { it.first }.toTypedArray()) { _, which -> actions[which].second() }
			.setNegativeButton(android.R.string.cancel, null)
			.setOnDismissListener { refocus(row) }
			.show()
	}

	private fun askRename(row: BookmarkRow) {
		val context = context ?: return
		val seconds = row.entry.bookmark.timestampSeconds
		val time = BookmarkLogic.formatTimestamp(seconds)
		val input = EditText(context).apply {
			setText(row.entry.bookmark.label.orEmpty())
			setSelection(text.length)
			// Left empty, the bookmark is labelled with its time
			hint = time
			inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
			isSingleLine = true
			filters = arrayOf(InputFilter.LengthFilter(BookmarkLogic.MAX_LABEL_LENGTH))
		}
		val padding = (24 * context.resources.displayMetrics.density).toInt()
		val frame = FrameLayout(context).apply {
			setPadding(padding, padding / 2, padding, 0)
			addView(input)
		}
		AlertDialog.Builder(context)
			.setTitle(getString(R.string.je_bookmarks_rename_title, time))
			.setMessage(R.string.je_bookmarks_rename_message)
			.setView(frame)
			.setPositiveButton(R.string.je_bookmarks_save) { _, _ -> rename(row, BookmarkLogic.labelForSave(input.text?.toString(), seconds)) }
			.setNegativeButton(android.R.string.cancel, null)
			.setOnDismissListener { refocus(row) }
			.show()
	}

	private fun rename(row: BookmarkRow, label: String) {
		if (row.entry.bookmark.label == label) return
		busy.update { it + row.key }
		lifecycleScope.launch {
			val result = attempt { bookmarksRepository.rename(row.key, label) }
			busy.update { it - row.key }
			val context = context ?: return@launch
			result.onSuccess {
				val bookmark = row.entry.bookmark.copy(label = label, updatedAt = Instant.now().toString())
				updateRows { rows -> rows.map { if (it.key == row.key) it.withBookmark(bookmark) else it } }
				Toast.makeText(context, R.string.je_bookmarks_renamed, Toast.LENGTH_SHORT).show()
			}.onFailure { err ->
				Timber.w(err, "Failed to rename bookmark")
				if (err is BookmarksRepository.BookmarkGoneException) {
					Toast.makeText(context, R.string.je_bookmarks_gone, Toast.LENGTH_LONG).show()
					removeRow(row.key)
				} else {
					val reason = err.serverMessage ?: getString(R.string.je_error_generic)
					Toast.makeText(context, getString(R.string.je_bookmarks_rename_failed, reason), Toast.LENGTH_LONG).show()
				}
			}
		}
	}

	private fun confirmDelete(row: BookmarkRow) {
		val context = context ?: return
		AlertDialog.Builder(context)
			.setTitle(R.string.je_bookmarks_delete_title)
			.setMessage(getString(R.string.je_bookmarks_delete_message, title(row), BookmarkLogic.formatTimestamp(row.entry.bookmark.timestampSeconds)))
			.setPositiveButton(R.string.je_bookmarks_delete) { _, _ -> delete(row) }
			.setNegativeButton(android.R.string.cancel, null)
			.setOnDismissListener { refocus(row) }
			.show()
	}

	private fun confirmStaleDelete(row: BookmarkRow) {
		val context = context ?: return
		AlertDialog.Builder(context)
			.setTitle(R.string.je_bookmarks_stale_title)
			.setMessage(R.string.je_bookmarks_stale_message)
			.setPositiveButton(R.string.je_bookmarks_delete) { _, _ -> delete(row) }
			.setNegativeButton(android.R.string.cancel, null)
			.setOnDismissListener { refocus(row) }
			.show()
	}

	private fun delete(row: BookmarkRow) {
		if (row.key in busy.value) return
		busy.update { it + row.key }
		lifecycleScope.launch {
			// A bookmark that was already gone (404) is removed from the list like a deleted one
			val result = attempt { bookmarksRepository.delete(row.key) }
			busy.update { it - row.key }
			val context = context ?: return@launch
			result.onSuccess {
				removeRow(row.key)
				Toast.makeText(context, R.string.je_bookmarks_deleted, Toast.LENGTH_SHORT).show()
			}.onFailure { err ->
				Timber.w(err, "Failed to delete bookmark")
				val reason = err.serverMessage ?: getString(R.string.je_error_generic)
				Toast.makeText(context, getString(R.string.je_bookmarks_delete_failed, reason), Toast.LENGTH_LONG).show()
			}
		}
	}

	/** Moves the focus off the row first (see [ListFocus]), then drops it from the list. */
	private fun removeRow(key: String) {
		val order = (loadState.value as? LoadState.Ready)?.value?.rows?.map { it.key } ?: return
		listFocus.moveTo(ListFocus.neighbour(order, listOf(key)))
		updateRows { rows -> rows.filterNot { it.key == key } }
	}

	private fun updateRows(change: (List<BookmarkRow>) -> List<BookmarkRow>) = loadState.update { current ->
		if (current !is LoadState.Ready) current
		else LoadState.Ready(current.value.copy(rows = change(current.value.rows)))
	}

	private companion object {
		const val STATE_SELECTED = "je_bookmarks_selected"
	}
}
