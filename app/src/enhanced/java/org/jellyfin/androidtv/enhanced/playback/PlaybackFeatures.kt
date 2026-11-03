package org.jellyfin.androidtv.enhanced.playback

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.decodeFromJsonElement
import org.jellyfin.androidtv.enhanced.api.EnhancedApi
import org.jellyfin.androidtv.enhanced.calendar.attempt
import org.jellyfin.androidtv.enhanced.data.EnhancedRepository
import org.jellyfin.androidtv.enhanced.data.EnhancedState
import timber.log.Timber

/**
 * The `public-config` fields the player features need that the shared [org.jellyfin.androidtv.enhanced.data.EnhancedPublicConfig]
 * doesn't decode. The first three are the server-wide defaults of user settings, so null means "not sent" and the web
 * client's built-in default applies.
 */
@Serializable
data class PlaybackPublicConfig(
	@SerialName("PauseScreenEnabled") val pauseScreenEnabled: Boolean? = null,
	@SerialName("PauseScreenDelaySeconds") val pauseScreenDelaySeconds: Int? = null,
	@SerialName("ShowRatingInPlayer") val showRatingInPlayer: Boolean? = null,
	@SerialName("ColoredRatingsEnabled") val coloredRatingsEnabled: Boolean = false,
)

/**
 * Which player features are on for the signed-in user. Settings resolve like the web's `loadSettings`: the user's
 * stored value, else the server default from public-config, else the web client's built-in default.
 */
data class PlaybackFeatures(
	val pauseScreen: Boolean = false,
	val pauseDelaySeconds: Int = DEFAULT_PAUSE_DELAY_SECONDS,
	val ratingBadge: Boolean = false,
	val ratingInPlayer: Boolean = false,
	val bookmarks: Boolean = false,
	/** The plugin's "colored ratings" option: the badge uses the age-rating colours only when it's on, as on the web. */
	val coloredRatings: Boolean = false,
) {
	val any get() = pauseScreen || ratingBadge || ratingInPlayer || bookmarks

	companion object {
		const val DEFAULT_PAUSE_DELAY_SECONDS = 5

		/** The web settings panel accepts 1–60 seconds. */
		private val PAUSE_DELAY_RANGE = 1..60

		/** Everything off: servers without the plugin. */
		val NONE = PlaybackFeatures()

		fun resolve(state: EnhancedState, extras: PlaybackPublicConfig): PlaybackFeatures {
			if (!state.available) return NONE
			val user = state.userSettings
			return PlaybackFeatures(
				pauseScreen = user.pauseScreenEnabled ?: extras.pauseScreenEnabled ?: true,
				pauseDelaySeconds = (user.pauseScreenDelaySeconds ?: extras.pauseScreenDelaySeconds ?: DEFAULT_PAUSE_DELAY_SECONDS)
					.coerceIn(PAUSE_DELAY_RANGE),
				ratingBadge = state.config.showPlaybackRatingBadge,
				ratingInPlayer = user.showRatingInPlayer ?: extras.showRatingInPlayer ?: true,
				bookmarks = state.bookmarks,
				coloredRatings = extras.coloredRatingsEnabled,
			)
		}
	}
}

/**
 * Resolves [PlaybackFeatures] for the player. The extra public-config fields are fetched once per session token; the
 * user's settings are read from the shared state on every call, so changes on the Playback settings page apply live.
 */
class PlaybackFeaturesRepository(
	private val enhancedApi: EnhancedApi,
	private val enhancedRepository: EnhancedRepository,
) {
	private val mutex = Mutex()
	private var cachedToken: String? = null
	private var cachedExtras: PlaybackPublicConfig? = null

	/** Whether the server has the plugin (as last loaded). */
	val available: Boolean get() = enhancedRepository.state.value.available

	/** The last known features, without any network call (extras default until [load] has run once). */
	val current: PlaybackFeatures
		get() = PlaybackFeatures.resolve(
			enhancedRepository.state.value,
			cachedExtras?.takeIf { cachedToken == enhancedApi.accessToken } ?: PlaybackPublicConfig(),
		)

	/** Loads the plugin state and the extra public-config fields if needed, then returns the features. */
	suspend fun load(): PlaybackFeatures {
		val state = enhancedRepository.ensureLoaded()
		if (!state.available) return PlaybackFeatures.NONE
		return PlaybackFeatures.resolve(state, extras())
	}

	private suspend fun extras(): PlaybackPublicConfig = mutex.withLock {
		val token = enhancedApi.accessToken
		cachedExtras?.takeIf { cachedToken == token }?.let { return@withLock it }
		attempt { EnhancedApi.json.decodeFromJsonElement<PlaybackPublicConfig>(enhancedApi.get("public-config")) }
			.onFailure { Timber.w(it, "Could not read the player settings from public-config") }
			.onSuccess {
				cachedToken = token
				cachedExtras = it
			}
			// Not cached on failure, so the next player session tries again
			.getOrDefault(PlaybackPublicConfig())
	}
}
