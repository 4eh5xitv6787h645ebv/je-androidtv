package org.jellyfin.androidtv.enhanced.playback

import android.content.Context
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.enhanced.calendar.attempt
import org.jellyfin.androidtv.enhanced.data.EnhancedRepository
import org.jellyfin.androidtv.ui.playback.PlaybackControllerContainer
import org.jellyfin.androidtv.ui.playback.overlay.CustomPlaybackTransportControlGlue
import org.koin.core.context.GlobalContext
import java.util.WeakHashMap

/**
 * Entry points for the one-line hooks in the upstream player (listed in INTEGRATION.md). Each is a no-op on servers
 * without Jellyfin Enhanced, when the feature is off, and when the playback module isn't registered.
 */
object EnhancedPlayback {
	private val enhancers = WeakHashMap<Fragment, PlaybackEnhancer>()
	private val bookmarkActions = WeakHashMap<CustomPlaybackTransportControlGlue, BookmarkAction>()

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
			if (!featuresRepository.available || fragment.view !== view || enhancers.containsKey(fragment)) return@launch
			val enhancer = PlaybackEnhancer(
				fragment = fragment,
				root = root,
				featuresRepository = featuresRepository,
				playbackBookmarks = koin() ?: return@launch,
				playbackControllerContainer = koin() ?: return@launch,
				imageHelper = koin() ?: return@launch,
				api = koin() ?: return@launch,
			)
			enhancers[fragment] = enhancer
			owner.lifecycle.addObserver(object : DefaultLifecycleObserver {
				override fun onDestroy(owner: LifecycleOwner) {
					enhancers.remove(fragment)
				}
			})
			enhancer.attach()
		}
	}

	/**
	 * From the player's key intercept listener, before upstream handles the key. Only observes, except that Back while
	 * the pause screen is showing closes just the pause screen (returns true).
	 */
	@JvmStatic
	fun onKey(fragment: Fragment, keyCode: Int, event: KeyEvent): Boolean =
		enhancers[fragment]?.onKey(keyCode, event) ?: false

	/** From `CustomPlaybackTransportControlGlue.addMediaActions`: adds "Bookmark" to the secondary actions. */
	@JvmStatic
	fun addSecondaryActions(context: Context, glue: CustomPlaybackTransportControlGlue, secondaryActions: ArrayObjectAdapter) {
		val state = koin<EnhancedRepository>()?.current ?: return
		if (!state.bookmarks) return
		val playbackBookmarks = koin<PlaybackBookmarks>() ?: return
		val controller = koin<PlaybackControllerContainer>()?.playbackController ?: return
		if (controller.isLiveTv || !controller.canSeek() || !playbackBookmarks.supports(controller.currentlyPlayingItem)) return

		val action = bookmarkActions.getOrPut(glue) { BookmarkAction(context, glue, playbackBookmarks) }
		secondaryActions.add(action)
	}
}
