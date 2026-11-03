package org.jellyfin.androidtv.enhanced.playback

import android.content.Context
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.enhanced.calendar.attempt
import org.jellyfin.androidtv.enhanced.data.EnhancedRepository
import org.jellyfin.androidtv.ui.playback.PlaybackControllerContainer
import org.jellyfin.androidtv.ui.playback.overlay.CustomPlaybackTransportControlGlue
import org.koin.core.context.GlobalContext

/**
 * Entry points for the one-line hooks in the upstream player (listed in INTEGRATION.md). Each is a no-op on servers
 * without Jellyfin Enhanced, when the feature is off, and when the playback module isn't registered.
 *
 * Nothing here keeps a player object: the [PlaybackEnhancer] and the [BookmarkAction] are stored as tags on the
 * player's own views, so they go away with the screen they belong to.
 */
object EnhancedPlayback {
	private inline fun <reified T : Any> koin(): T? = GlobalContext.getOrNull()?.getOrNull<T>()

	/** From `CustomPlaybackOverlayFragment.onViewCreated`: adds the pause screen, rating badge and OSD ratings. */
	@JvmStatic
	fun attach(fragment: Fragment, view: View) {
		val root = view as? ViewGroup ?: return
		val featuresRepository = koin<PlaybackFeaturesRepository>() ?: return
		val owner = fragment.viewLifecycleOwner
		owner.lifecycleScope.launch {
			// Nothing is added (and nothing runs) unless the server has the plugin. With the plugin, attach even when every
			// feature is off: the overlays read the settings on every tick, so a change saved meanwhile still applies.
			attempt { featuresRepository.load() }
			if (!featuresRepository.available || fragment.view !== view || root.getTag(R.id.je_playback_enhancer) != null) return@launch
			val enhancer = PlaybackEnhancer(
				fragment = fragment,
				root = root,
				featuresRepository = featuresRepository,
				playbackBookmarks = koin() ?: return@launch,
				playbackControllerContainer = koin() ?: return@launch,
				imageHelper = koin() ?: return@launch,
				api = koin() ?: return@launch,
			)
			root.setTag(R.id.je_playback_enhancer, enhancer)
			enhancer.attach()
		}
	}

	/**
	 * From the player's key intercept listener, before upstream handles the key. Only observes, except that Back while
	 * the pause screen is showing closes just the pause screen (returns true).
	 */
	@JvmStatic
	fun onKey(fragment: Fragment, keyCode: Int, event: KeyEvent): Boolean =
		(fragment.view?.getTag(R.id.je_playback_enhancer) as? PlaybackEnhancer)?.onKey(keyCode, event) ?: false

	/** From `CustomPlaybackTransportControlGlue.addMediaActions`: adds "Bookmark" to the secondary actions. */
	@JvmStatic
	fun addSecondaryActions(context: Context, glue: CustomPlaybackTransportControlGlue, secondaryActions: ArrayObjectAdapter) {
		val state = koin<EnhancedRepository>()?.current ?: return
		if (!state.bookmarks) return
		val playbackBookmarks = koin<PlaybackBookmarks>() ?: return
		val controller = koin<PlaybackControllerContainer>()?.playbackController ?: return
		if (controller.isLiveTv || !controller.canSeek() || !playbackBookmarks.supports(controller.currentlyPlayingItem)) return

		secondaryActions.add(bookmarkAction(context, glue, playbackBookmarks))
	}

	/**
	 * The glue's Bookmark action. `addMediaActions` runs again for every item, so the action is kept (with its open
	 * menu) on the view of the overlay fragment that owns the glue rather than created anew each time.
	 */
	private fun bookmarkAction(context: Context, glue: CustomPlaybackTransportControlGlue, playbackBookmarks: PlaybackBookmarks): BookmarkAction {
		val holder = glue.playerAdapter?.leanbackOverlayFragment?.view
		(holder?.getTag(R.id.je_playback_bookmark_action) as? BookmarkAction)?.takeIf { it.glue === glue }?.let { return it }
		return BookmarkAction(context, glue, playbackBookmarks).also { holder?.setTag(R.id.je_playback_bookmark_action, it) }
	}
}
