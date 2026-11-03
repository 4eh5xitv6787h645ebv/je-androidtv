package org.jellyfin.androidtv.enhanced.playback

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.PopupMenu
import android.widget.Toast
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.enhanced.bookmarks.BookmarkEntry
import org.jellyfin.androidtv.enhanced.bookmarks.BookmarkLogic
import org.jellyfin.androidtv.enhanced.calendar.attempt
import org.jellyfin.androidtv.enhanced.calendar.serverMessage
import org.jellyfin.androidtv.ui.playback.PlaybackController
import org.jellyfin.androidtv.ui.playback.overlay.CustomPlaybackTransportControlGlue
import org.jellyfin.androidtv.ui.playback.overlay.VideoPlayerAdapter
import org.jellyfin.androidtv.ui.playback.overlay.action.CustomAction
import org.jellyfin.sdk.model.api.BaseItemDto
import timber.log.Timber

/**
 * "Bookmark" in the player's secondary actions. Without bookmarks on the item, pressing it saves one at the current
 * position right away (labelled with the time, like "20:00"). Once the item has bookmarks it opens a list instead:
 * "Add bookmark at …" first, then the saved ones, which seek to their position.
 */
class BookmarkAction(
	context: Context,
	/** The glue this action belongs to; it's reused only for that glue. */
	internal val glue: CustomPlaybackTransportControlGlue,
	private val playbackBookmarks: PlaybackBookmarks,
) : CustomAction(context, glue) {
	private var popup: PopupMenu? = null
	private var saving = false
	private var observing = false

	init {
		initializeWithIcon(R.drawable.je_ic_bookmarks_add)
		setLabels(arrayOf(context.getString(R.string.je_playback_bookmark)))
	}

	override fun handleClickAction(
		playbackController: PlaybackController,
		videoPlayerAdapter: VideoPlayerAdapter,
		context: Context,
		view: View,
	) {
		val item = playbackController.currentlyPlayingItem ?: return
		val positionMs = playbackController.currentPosition
		val owner = videoPlayerAdapter.masterOverlayFragment.viewLifecycleOwner
		owner.lifecycleScope.launch {
			// Cached since the item started; a failed read still lets the user save
			val existing = attempt { playbackBookmarks.forItem(item.id) }
				.onFailure { Timber.w(it, "Could not read bookmarks") }
				.getOrDefault(emptyList())
			if (existing.isEmpty()) save(videoPlayerAdapter, context, item, positionMs)
			else showList(owner, playbackController, videoPlayerAdapter, context, view, item, positionMs, existing)
		}
	}

	private fun showList(
		owner: LifecycleOwner,
		playbackController: PlaybackController,
		videoPlayerAdapter: VideoPlayerAdapter,
		context: Context,
		view: View,
		item: BaseItemDto,
		positionMs: Long,
		entries: List<BookmarkEntry>,
	) {
		dismissPopup()
		videoPlayerAdapter.leanbackOverlayFragment.setFading(false)
		val menu = PopupMenu(context, view, Gravity.END).apply {
			menu.add(0, ADD_ID, 0, context.getString(R.string.je_playback_bookmark_add_at, BookmarkLogic.formatTimestamp(PlaybackBookmarkLogic.seconds(positionMs))))
			entries.forEachIndexed { index, entry ->
				menu.add(0, index + 1, index + 1, PlaybackBookmarkLogic.menuLabel(entry.bookmark))
			}
			setOnMenuItemClickListener { menuItem ->
				if (menuItem.itemId == ADD_ID) owner.lifecycleScope.launch { save(videoPlayerAdapter, context, item, positionMs) }
				else entries.getOrNull(menuItem.itemId - 1)?.let {
					playbackController.seek(PlaybackBookmarkLogic.positionMs(it.bookmark.timestampSeconds))
				}
				true
			}
			setOnDismissListener {
				videoPlayerAdapter.leanbackOverlayFragment.setFading(true)
				popup = null
			}
		}
		popup = menu
		if (!observing) {
			observing = true
			// The player screen can close under the menu (end of item, Next Up)
			owner.lifecycle.addObserver(object : DefaultLifecycleObserver {
				override fun onDestroy(owner: LifecycleOwner) = dismissPopup()
			})
		}
		menu.show()
	}

	private suspend fun save(videoPlayerAdapter: VideoPlayerAdapter, context: Context, item: BaseItemDto, positionMs: Long) {
		if (saving) return
		saving = true
		val time = BookmarkLogic.formatTimestamp(PlaybackBookmarkLogic.seconds(positionMs))
		val result = try {
			attempt { playbackBookmarks.add(item, positionMs, label = null) }
		} finally {
			saving = false
		}
		result.onSuccess {
			notify(videoPlayerAdapter, context, context.getString(R.string.je_playback_bookmark_saved, time), error = false)
		}.onFailure { err ->
			Timber.w(err, "Failed to save bookmark")
			val reason = err.serverMessage ?: context.getString(R.string.je_error_generic)
			notify(videoPlayerAdapter, context, context.getString(R.string.je_playback_bookmark_failed, reason), error = true)
		}
	}

	/** The player's own overlay toast when the enhancer is attached to this screen, a system toast otherwise. */
	private fun notify(videoPlayerAdapter: VideoPlayerAdapter, context: Context, text: String, error: Boolean) {
		val enhancer = videoPlayerAdapter.masterOverlayFragment.view?.getTag(R.id.je_playback_enhancer) as? PlaybackEnhancer
		if (enhancer != null) enhancer.showToast(text, error)
		else Toast.makeText(context, text, if (error) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
	}

	fun dismissPopup() {
		popup?.dismiss()
		popup = null
	}

	private companion object {
		/** The add entry; bookmarks follow with their list position (from 1) as id. */
		const val ADD_ID = 0
	}
}
