package org.jellyfin.androidtv.enhanced.bookmarks

import org.jellyfin.androidtv.enhanced.ui.secondaryTextColor
import android.app.AlertDialog
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.Fragment
import androidx.fragment.compose.content
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.enhanced.calendar.PageImage
import org.jellyfin.androidtv.enhanced.calendar.PageListRow
import org.jellyfin.androidtv.enhanced.calendar.PageNotice
import org.jellyfin.androidtv.enhanced.calendar.attempt
import org.jellyfin.androidtv.enhanced.calendar.serverMessage
import org.jellyfin.androidtv.enhanced.data.SpoilerProtection
import org.jellyfin.androidtv.enhanced.ui.hub.EnhancedScreen
import org.jellyfin.androidtv.enhanced.ui.hub.LoadState
import org.jellyfin.androidtv.enhanced.ui.hub.LoadStateContent
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.navigation.Destinations
import org.jellyfin.androidtv.ui.navigation.NavigationRepository
import org.jellyfin.androidtv.util.PlaybackHelper
import org.jellyfin.design.Tokens
import org.jellyfin.sdk.model.serializer.toUUIDOrNull
import org.koin.android.ext.android.inject
import timber.log.Timber
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.UUID

/**
 * The user's JE timestamp bookmarks, newest first. OK plays the item from the saved moment; long-press OK (or Menu)
 * offers Open details and Delete. Bookmarks whose item is gone are marked and offer deletion. Episode names follow
 * Spoiler Guard ([BookmarkLogic.name]): the name saved with a bookmark is never shown for a possibly guarded episode.
 */
class BookmarksFragment : Fragment() {
	private val bookmarksRepository by inject<BookmarksRepository>()
	private val navigationRepository by inject<NavigationRepository>()
	private val playbackHelper by inject<PlaybackHelper>()
	private val spoilerProtection by inject<SpoilerProtection>()

	private class BookmarkRow(
		val entry: BookmarkEntry,
		val itemId: UUID?,
		/** The item as the server returns it for this user; null when stale or not checked. */
		val item: BookmarkedItem?,
		/** True only when the server answered and the item wasn't there. */
		val stale: Boolean,
		val name: BookmarkName,
	)

	private class Loaded(val rows: List<BookmarkRow>, val resolveFailed: Boolean)

	private val loadState = MutableStateFlow<LoadState<Loaded>>(LoadState.Loading)
	private var loadJob: Job? = null

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		reload()
	}

	private fun reload() {
		loadJob?.cancel()
		loadState.value = LoadState.Loading
		loadJob = lifecycleScope.launch {
			attempt {
				val entries = bookmarksRepository.list()
				val ids = entries.mapNotNull { it.bookmark.itemId.toUUIDOrNull() }.distinct()
				val resolved = attempt { bookmarksRepository.resolve(ids) }
					.onFailure { Timber.w(it, "Failed to look up bookmarked items") }
					.getOrNull()
				val spoiler = spoilerProtection.snapshot()
				// Every name would be withheld: offer a retry instead of a list of neutral labels
				if (spoiler.configUnavailable) error("Jellyfin Enhanced configuration unavailable")
				Loaded(
					rows = entries.map { entry ->
						val itemId = entry.bookmark.itemId.toUUIDOrNull()
						val item = itemId?.let { resolved?.get(it) }
						BookmarkRow(entry, itemId, item, stale = resolved != null && item == null, name = BookmarkLogic.name(entry.bookmark, item, spoiler))
					},
					resolveFailed = resolved == null,
				)
			}.onSuccess {
				loadState.value = LoadState.Ready(it)
			}.onFailure { err ->
				Timber.w(err, "Failed to load bookmarks")
				loadState.value = LoadState.Error(err.serverMessage) { reload() }
			}
		}
	}

	override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?) = content {
		val state by loadState.collectAsState()
		val count = (state as? LoadState.Ready)?.value?.rows?.size

		EnhancedScreen(
			title = stringResource(R.string.je_bookmarks_title),
			subtitle = count?.takeIf { it > 0 }?.let { stringResource(R.string.je_bookmarks_count, it) },
		) {
			LoadStateContent(
				state = state,
				empty = { it.rows.isEmpty() },
				emptyMessage = stringResource(R.string.je_bookmarks_empty),
			) { loaded ->
				val firstFocus = remember { FocusRequester() }
				Column(modifier = Modifier.fillMaxSize()) {
					if (loaded.resolveFailed) PageNotice(stringResource(R.string.je_bookmarks_unresolved), Modifier.padding(horizontal = 48.dp))
					LazyColumn(
						contentPadding = PaddingValues(start = 48.dp, end = 48.dp, top = 8.dp, bottom = 48.dp),
						verticalArrangement = Arrangement.spacedBy(8.dp),
					) {
						items(loaded.rows, key = { it.entry.bookmarkId }) { row ->
							BookmarkItem(row, if (row === loaded.rows.first()) Modifier.focusRequester(firstFocus) else Modifier)
						}
					}
				}
				LaunchedEffect(loaded) { runCatching { firstFocus.requestFocus() } }
			}
		}
	}

	@androidx.compose.runtime.Composable
	private fun BookmarkItem(row: BookmarkRow, modifier: Modifier) {
		val bookmark = row.entry.bookmark
		val name = displayName(row)
		val label = bookmark.label?.takeIf { it.isNotBlank() }
		val savedOn = remember(bookmark) { savedDate(bookmark) }
		val details = listOfNotNull(
			stringResource(R.string.je_bookmarks_at, BookmarkLogic.formatTimestamp(bookmark.timestampSeconds)),
			savedOn?.let { stringResource(R.string.je_bookmarks_saved_on, it) },
		).joinToString("  ·  ")

		PageListRow(
			onClick = { select(row) },
			onLongClick = { showMenu(row) },
			modifier = modifier.onPreviewKeyEvent { event ->
				// The remote's Menu key opens the same menu as a long press
				if (event.key.nativeKeyCode != KeyEvent.KEYCODE_MENU) return@onPreviewKeyEvent false
				if (event.type == KeyEventType.KeyUp) showMenu(row)
				true
			},
		) {
			PageImage(url = row.item?.posterUrl, modifier = Modifier.size(56.dp, 84.dp))
			Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
				Text(text = label ?: name, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
				if (label != null) Text(text = name, fontSize = 15.sp, color = secondaryTextColor(), maxLines = 1, overflow = TextOverflow.Ellipsis)
				Text(text = details, fontSize = 13.sp, color = secondaryTextColor(), maxLines = 1)
				if (row.stale) Text(stringResource(R.string.je_bookmarks_stale), fontSize = 13.sp, color = Tokens.Color.colorRed300)
			}
		}
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
		// The item was loaded as this user when the list was built, so it's accessible
		playbackHelper.retrieveAndPlay(listOf(itemId), false, BookmarkLogic.ticks(row.entry.bookmark.timestampSeconds), 0, context)
	}

	private fun showMenu(row: BookmarkRow) {
		val context = context ?: return
		val actions = buildList<Pair<String, () -> Unit>> {
			if (!row.stale && row.itemId != null) {
				add(getString(R.string.je_bookmarks_play_from, BookmarkLogic.formatTimestamp(row.entry.bookmark.timestampSeconds)) to { play(row) })
				add(getString(R.string.je_bookmarks_open_details) to { navigationRepository.navigate(Destinations.itemDetails(row.itemId)) })
			}
			add(getString(R.string.je_bookmarks_delete) to { delete(row) })
		}
		AlertDialog.Builder(context)
			.setTitle(row.entry.bookmark.label?.takeIf { it.isNotBlank() } ?: displayName(row))
			.setItems(actions.map { it.first }.toTypedArray()) { _, which -> actions[which].second() }
			.setNegativeButton(android.R.string.cancel, null)
			.show()
	}

	private fun confirmStaleDelete(row: BookmarkRow) {
		val context = context ?: return
		AlertDialog.Builder(context)
			.setTitle(R.string.je_bookmarks_stale_title)
			.setMessage(R.string.je_bookmarks_stale_message)
			.setPositiveButton(R.string.je_bookmarks_delete) { _, _ -> delete(row) }
			.setNegativeButton(android.R.string.cancel, null)
			.show()
	}

	private fun delete(row: BookmarkRow) {
		lifecycleScope.launch {
			val result = attempt { bookmarksRepository.delete(row.entry.bookmarkId) }
			val context = context ?: return@launch
			result.onSuccess {
				Toast.makeText(context, R.string.je_bookmarks_deleted, Toast.LENGTH_SHORT).show()
			}.onFailure { err ->
				Timber.w(err, "Failed to delete bookmark")
				val reason = err.serverMessage ?: getString(R.string.je_error_generic)
				Toast.makeText(context, getString(R.string.je_bookmarks_delete_failed, reason), Toast.LENGTH_LONG).show()
			}
			// Reload either way: a 404 means it was already removed elsewhere
			reload()
		}
	}
}
