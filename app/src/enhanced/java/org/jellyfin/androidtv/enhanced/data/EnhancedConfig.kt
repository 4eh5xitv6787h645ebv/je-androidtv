package org.jellyfin.androidtv.enhanced.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The subset of `GET /JellyfinEnhanced/public-config` the TV client acts on. Field names are the plugin's
 * PascalCase property names. Defaults are "feature off" so a server without the plugin (or an older plugin)
 * leaves the stock client behaviour untouched.
 */
@Serializable
data class EnhancedPublicConfig(
	// Spoiler Guard
	@SerialName("SpoilerBlurEnabled") val spoilerBlurEnabled: Boolean = false,

	// Hidden Content / home removal
	@SerialName("HiddenContentEnabled") val hiddenContentEnabled: Boolean = false,
	@SerialName("RemoveContinueWatchingEnabled") val removeContinueWatchingEnabled: Boolean = false,

	// Seerr
	@SerialName("JellyseerrEnabled") val jellyseerrEnabled: Boolean = false,
	@SerialName("JellyseerrShowSimilar") val jellyseerrShowSimilar: Boolean = false,
	@SerialName("JellyseerrShowRecommended") val jellyseerrShowRecommended: Boolean = false,
	@SerialName("JellyseerrShowPersonDiscovery") val jellyseerrShowPersonDiscovery: Boolean = false,
	@SerialName("JellyseerrShowGenreDiscovery") val jellyseerrShowGenreDiscovery: Boolean = false,
	@SerialName("JellyseerrShowNetworkDiscovery") val jellyseerrShowNetworkDiscovery: Boolean = false,
	@SerialName("JellyseerrShowTagDiscovery") val jellyseerrShowTagDiscovery: Boolean = false,
	@SerialName("JellyseerrShowCollectionDiscovery") val jellyseerrShowCollectionDiscovery: Boolean = false,
	@SerialName("JellyseerrExcludeLibraryItems") val jellyseerrExcludeLibraryItems: Boolean = false,
	@SerialName("JellyseerrExcludeBlocklistedItems") val jellyseerrExcludeBlocklistedItems: Boolean = false,
	@SerialName("JellyseerrEnable4KRequests") val jellyseerrEnable4KRequests: Boolean = false,
	@SerialName("JellyseerrEnable4KTvRequests") val jellyseerrEnable4KTvRequests: Boolean = false,
	@SerialName("JellyseerrShowAdvanced") val jellyseerrShowAdvanced: Boolean = false,
	@SerialName("JellyseerrSingleSeasonRequestOnly") val jellyseerrSingleSeasonRequestOnly: Boolean = false,
	@SerialName("JellyseerrShowQuotaInfo") val jellyseerrShowQuotaInfo: Boolean = false,
	@SerialName("TmdbEnabled") val tmdbEnabled: Boolean = false,
)

/** The subset of `user-settings/{userId}/settings.json` the TV client reads. */
@Serializable
data class EnhancedUserSettings(
	@SerialName("RemoveContinueWatchingEnabled") val removeContinueWatchingEnabled: Boolean? = null,
)

/** `GET /JellyfinEnhanced/jellyseerr/user-status`. */
@Serializable
data class SeerrUserStatus(
	val active: Boolean = false,
	val userFound: Boolean = false,
	val reason: String? = null,
	val canRequestAdvanced: Boolean = false,
) {
	val linked get() = active && userFound
}

/**
 * Everything the UI needs to decide which Jellyfin Enhanced controls to show, resolved for the signed-in user.
 * [available] is false when the server has no (reachable) Jellyfin Enhanced plugin.
 */
data class EnhancedState(
	val available: Boolean = false,
	val config: EnhancedPublicConfig = EnhancedPublicConfig(),
	val userSettings: EnhancedUserSettings = EnhancedUserSettings(),
	val seerr: SeerrUserStatus = SeerrUserStatus(),
	/** The user's `hidden-content.json` settings, when Hidden Content is enabled. */
	val hiddenSettings: HiddenContentSettings? = null,
) {
	val spoilerGuard get() = available && config.spoilerBlurEnabled
	val hiddenContent get() = available && config.hiddenContentEnabled

	/** Whether to offer Hide buttons and menu entries, following the user's Hidden Content settings. */
	val hideControls get() = hiddenContent && hiddenSettings?.let { it.enabled && it.showHideButtons } == true

	/** The web shows "Remove from Continue Watching / Next Up" when the user's effective preference is on. */
	val removeFromHome
		get() = available && (userSettings.removeContinueWatchingEnabled ?: config.removeContinueWatchingEnabled)

	val seerrUsable get() = available && config.jellyseerrEnabled && seerr.linked
	val seerrAdvanced get() = seerrUsable && config.jellyseerrShowAdvanced && seerr.canRequestAdvanced
}
