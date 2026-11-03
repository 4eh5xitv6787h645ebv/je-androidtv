package org.jellyfin.androidtv.enhanced.playback

import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.enhanced.calendar.attempt
import org.jellyfin.androidtv.ui.base.JellyfinTheme
import org.jellyfin.androidtv.ui.playback.PlaybackController
import org.jellyfin.androidtv.ui.playback.PlaybackControllerContainer
import org.jellyfin.androidtv.ui.playback.overlay.LeanbackOverlayFragment
import org.jellyfin.androidtv.ui.playback.overlay.SkipOverlayView
import org.jellyfin.androidtv.util.ImageHelper
import org.jellyfin.androidtv.util.apiclient.getUrl
import org.jellyfin.androidtv.util.apiclient.itemBackdropImages
import org.jellyfin.androidtv.util.apiclient.parentBackdropImages
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.model.api.BaseItemDto
import java.util.UUID

/**
 * The Jellyfin Enhanced additions to one video player screen: the pause screen, the playback-start rating badge and
 * the community/critic ratings in the OSD title area. Created by [EnhancedPlayback.attach] once the plugin is known
 * to be available, and bound to the player fragment's view lifecycle.
 *
 * It reads the player through [PlaybackController]'s public state on a short tick (faster while paused) instead of
 * adding hooks to every play/pause/seek path upstream. Its views never take focus, and remote keys are only observed:
 * the one exception is Back while the pause screen shows, which closes just the pause screen, the way Back closes a
 * visible OSD.
 */
internal class PlaybackEnhancer(
	private val fragment: Fragment,
	private val root: ViewGroup,
	private val featuresRepository: PlaybackFeaturesRepository,
	private val playbackBookmarks: PlaybackBookmarks,
	private val playbackControllerContainer: PlaybackControllerContainer,
	private val imageHelper: ImageHelper,
	private val api: ApiClient,
) {
	private val pauseTimer = PauseScreenTimer()
	private var pause by mutableStateOf<PauseScreenModel?>(null)
	private var badge by mutableStateOf<RatingBadgeModel?>(null)
	private var osdRating by mutableStateOf<OsdRatingModel?>(null)

	private var lastBadgeItemId: UUID? = null
	private var badgeJob: Job? = null
	private var prefetchedItemId: UUID? = null
	private var swallowBackUp = false

	private val leanbackFragment
		get() = fragment.childFragmentManager.findFragmentById(R.id.leanback_fragment) as? LeanbackOverlayFragment

	fun attach() {
		val context = root.context
		root.addView(nonFocusable(ComposeView(context).apply {
			setContent { JellyfinTheme { PlaybackOverlay(pause, badge) } }
		}), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

		// The OSD title area (logo or title, then the episode line); the ratings go on a line of their own below it
		(root.findViewById<View>(R.id.topPanel) as? ConstraintLayout)?.let { topPanel ->
			val params = ConstraintLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
				startToStart = ConstraintLayout.LayoutParams.PARENT_ID
				topToBottom = R.id.item_subtitle
				topMargin = (4 * context.resources.displayMetrics.density).toInt()
			}
			topPanel.addView(nonFocusable(ComposeView(context).apply {
				setContent { JellyfinTheme { OsdRating(osdRating) } }
			}), params)
		}

		val owner = fragment.viewLifecycleOwner
		owner.lifecycleScope.launch {
			owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
				while (true) {
					val paused = tick()
					delay(if (paused) TICK_PAUSED_MS else TICK_PLAYING_MS)
				}
			}
		}
	}

	/** Observes a remote key; returns true only for the Back press that closes the pause screen. */
	fun onKey(keyCode: Int, event: KeyEvent): Boolean {
		val isBack = keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_BUTTON_B || keyCode == KeyEvent.KEYCODE_ESCAPE
		if (event.action == KeyEvent.ACTION_UP) {
			if (isBack && swallowBackUp) {
				swallowBackUp = false
				return true
			}
			return false
		}
		if (event.action != KeyEvent.ACTION_DOWN) return false

		val wasVisible = pauseTimer.onInput(SystemClock.elapsedRealtime(), dismiss = isBack)
		if (!wasVisible) return false
		pause = null
		// Any other key goes on to the player as usual (OK resumes, the D-pad brings up the OSD)
		if (isBack) swallowBackUp = true
		return isBack
	}

	/** One look at the player. Returns whether it is paused, to pick the next tick's delay. */
	private fun tick(): Boolean {
		val controller = playbackControllerContainer.playbackController
		val features = featuresRepository.current
		// Only the session this screen belongs to (a stale fragment may still be around during transitions)
		if (controller == null || controller.fragment !== fragment || !features.any) {
			hideAll()
			return false
		}

		// Live TV has no item progress to show; everything here is for videos
		val item = controller.currentlyPlayingItem?.takeIf { !controller.isLiveTv }

		osdRating = if (item != null && features.ratingInPlayer) item.osdRating() else null
		if (item != null && features.ratingBadge && controller.isPlaying && item.id != lastBadgeItemId) {
			lastBadgeItemId = item.id
			showBadge(item, features)
		}
		if (item != null && features.bookmarks && playbackBookmarks.supports(item) && item.id != prefetchedItemId) {
			prefetchedItemId = item.id
			// Read once per item so the player's Bookmark action can offer the list without waiting
			fragment.viewLifecycleOwner.lifecycleScope.launch { attempt { playbackBookmarks.forItem(item.id) } }
		}

		updatePauseScreen(controller, item, features)
		return controller.isPaused
	}

	private fun updatePauseScreen(controller: PlaybackController, item: BaseItemDto?, features: PlaybackFeatures) {
		val now = SystemClock.elapsedRealtime()
		val leanback = leanbackFragment
		// The OSD was brought up some other way (a key we didn't see): it wins
		if (pauseTimer.visible && leanback?.isControlsOverlayVisible == true) pauseTimer.onInput(now, dismiss = false)

		val wasVisible = pauseTimer.visible
		val visible = pauseTimer.update(
			PauseScreenTimer.Input(
				enabled = item != null && features.pauseScreen,
				delayMs = features.pauseDelaySeconds * 1000L,
				itemId = item?.id,
				paused = controller.isPaused,
				positionMs = controller.currentPosition,
				blocked = isBlocked(),
			),
			now,
		)
		if (!visible || item == null) {
			pause = null
			return
		}
		// Upstream keeps the OSD up while paused; after the idle delay the pause screen takes its place, as on the web
		if (!wasVisible) leanback?.hideOverlay()
		pause = item.pauseScreenModel(controller)
	}

	/** Something else is on top of the player: a dialog or popup menu (the window loses focus), the chapter panel, the skip button. */
	private fun isBlocked(): Boolean = !fragment.isResumed ||
		!root.hasWindowFocus() ||
		root.findViewById<View>(R.id.popupArea)?.isVisible == true ||
		root.findViewById<SkipOverlayView>(R.id.skip_overlay)?.visible == true

	private fun showBadge(item: BaseItemDto, features: PlaybackFeatures) {
		val rating = PlaybackItemInfo.officialRating(item) ?: return
		badge = RatingBadgeModel(item.id, rating, PlaybackItemInfo.badgeGenres(item), features.coloredRatings)
		badgeJob?.cancel()
		badgeJob = fragment.viewLifecycleOwner.lifecycleScope.launch {
			delay(BADGE_SHOW_MS)
			badge = null
		}
	}

	private fun hideAll() {
		pause = null
		badge = null
		osdRating = null
	}

	private fun BaseItemDto.osdRating(): OsdRatingModel? {
		val community = communityRating
		val critic = PlaybackItemInfo.criticPercent(this)
		return if (community == null && critic == null) null else OsdRatingModel(community, critic)
	}

	private fun BaseItemDto.pauseScreenModel(controller: PlaybackController): PauseScreenModel {
		// Keep the server's image tags: the plugin encodes its Spoiler Guard state in them
		val backdrop = (itemBackdropImages.firstOrNull() ?: parentBackdropImages.firstOrNull())
			?.getUrl(api, maxWidth = BACKDROP_WIDTH)
			?: imageHelper.getPrimaryImageUrl(this, width = BACKDROP_WIDTH)
		return PauseScreenModel(
			itemId = id,
			title = PlaybackItemInfo.title(this),
			logoUrl = imageHelper.getLogoImageUrl(this, LOGO_WIDTH),
			backdropUrl = backdrop,
			episodeLine = PlaybackItemInfo.episodeLine(this),
			year = productionYear,
			officialRating = PlaybackItemInfo.officialRating(this),
			runtime = PlaybackItemInfo.runtime(runTimeTicks),
			overview = overview,
			positionMs = controller.currentPosition,
			durationMs = controller.duration,
			speed = controller.playbackSpeed,
		)
	}

	/** Wraps [view] so neither it nor anything inside can take focus from the player controls. */
	private fun nonFocusable(view: View) = FrameLayout(view.context).apply {
		isFocusable = false
		descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
		addView(view)
	}

	private companion object {
		const val TICK_PAUSED_MS = 250L
		const val TICK_PLAYING_MS = 500L
		/** `SHOW_MS` of playback-rating-badge.js; the fade adds its 400 ms. */
		const val BADGE_SHOW_MS = 5_000L
		const val BACKDROP_WIDTH = 1280
		const val LOGO_WIDTH = 760
	}
}
