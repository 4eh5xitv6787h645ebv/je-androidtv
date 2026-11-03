package org.jellyfin.androidtv.enhanced.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The subset of `GET /JellyfinEnhanced/public-config` the TV client acts on. Field names are the plugin's
 * PascalCase property names. Defaults are "feature off" so a server without the plugin (or an older plugin)
 * leaves the stock client behaviour untouched.
 *
 * These are the plugin's *gates*; they say whether a feature is turned on for the server. Permissions are still
 * enforced by the server on each call, so a failed call must be reported, never turned into "no items".
 */
@Serializable
data class EnhancedPublicConfig(
	// Spoiler Guard
	@SerialName("SpoilerBlurEnabled") val spoilerBlurEnabled: Boolean = false,
	@SerialName("SpoilerStripSeriesOverview") val spoilerStripSeriesOverview: Boolean = true,
	@SerialName("SpoilerStripOverview") val spoilerStripOverview: Boolean = true,
	@SerialName("SpoilerStripTags") val spoilerStripTags: Boolean = true,
	@SerialName("SpoilerStripChapters") val spoilerStripChapters: Boolean = true,
	@SerialName("SpoilerStripTaglines") val spoilerStripTaglines: Boolean = true,
	@SerialName("SpoilerStripRatings") val spoilerStripRatings: Boolean = true,
	@SerialName("SpoilerStripPremiereDate") val spoilerStripPremiereDate: Boolean = true,
	@SerialName("SpoilerReplaceTitle") val spoilerReplaceTitle: Boolean = true,
	@SerialName("SpoilerStripCast") val spoilerStripCast: Boolean = true,
	@SerialName("SpoilerStripReviews") val spoilerStripReviews: Boolean = true,
	@SerialName("SpoilerBlurTrickplay") val spoilerBlurTrickplay: Boolean = true,
	@SerialName("SpoilerAdvancedMode") val spoilerAdvancedMode: Boolean = false,

	// Hidden Content / home removal
	@SerialName("HiddenContentEnabled") val hiddenContentEnabled: Boolean = false,
	@SerialName("RemoveContinueWatchingEnabled") val removeContinueWatchingEnabled: Boolean = false,

	// Seerr
	@SerialName("JellyseerrEnabled") val jellyseerrEnabled: Boolean = false,
	@SerialName("JellyseerrShowSearchResults") val jellyseerrShowSearchResults: Boolean = false,
	@SerialName("JellyseerrSearchExcludeLibraryItems") val jellyseerrSearchExcludeLibraryItems: Boolean = false,
	@SerialName("ShowCollectionsInSearch") val showCollectionsInSearch: Boolean = false,
	@SerialName("JellyseerrShowSimilar") val jellyseerrShowSimilar: Boolean = false,
	@SerialName("JellyseerrShowRecommended") val jellyseerrShowRecommended: Boolean = false,
	@SerialName("JellyseerrShowRequestMoreOnSeries") val jellyseerrShowRequestMoreOnSeries: Boolean = false,
	@SerialName("JellyseerrShowPersonDiscovery") val jellyseerrShowPersonDiscovery: Boolean = false,
	@SerialName("JellyseerrShowGenreDiscovery") val jellyseerrShowGenreDiscovery: Boolean = false,
	@SerialName("JellyseerrShowNetworkDiscovery") val jellyseerrShowNetworkDiscovery: Boolean = false,
	@SerialName("JellyseerrShowTagDiscovery") val jellyseerrShowTagDiscovery: Boolean = false,
	@SerialName("JellyseerrShowCollectionDiscovery") val jellyseerrShowCollectionDiscovery: Boolean = false,
	@SerialName("JellyseerrExcludeLibraryItems") val jellyseerrExcludeLibraryItems: Boolean = false,
	@SerialName("JellyseerrExcludeBlocklistedItems") val jellyseerrExcludeBlocklistedItems: Boolean = false,
	@SerialName("JellyseerrShowAdvanced") val jellyseerrShowAdvanced: Boolean = false,
	@SerialName("JellyseerrSingleSeasonRequestOnly") val jellyseerrSingleSeasonRequestOnly: Boolean = false,
	@SerialName("JellyseerrShowQuotaInfo") val jellyseerrShowQuotaInfo: Boolean = false,
	@SerialName("JellyseerrShowReportButton") val jellyseerrShowReportButton: Boolean = false,
	@SerialName("JellyseerrShowIssueIndicator") val jellyseerrShowIssueIndicator: Boolean = false,
	@SerialName("ShowElsewhereOnJellyseerr") val showElsewhereOnJellyseerr: Boolean = false,
	@SerialName("RecommendationsPageEnabled") val recommendationsPageEnabled: Boolean = false,

	// Requests / downloads / calendar pages
	@SerialName("DownloadsPageEnabled") val downloadsPageEnabled: Boolean = false,
	@SerialName("DownloadsPageShowIssues") val downloadsPageShowIssues: Boolean = false,
	@SerialName("DownloadsShowHistory") val downloadsShowHistory: Boolean = false,
	@SerialName("DownloadsHistoryAdminOnly") val downloadsHistoryAdminOnly: Boolean = false,
	@SerialName("DownloadsFilterByUserRequests") val downloadsFilterByUserRequests: Boolean = false,
	@SerialName("ShowDownloadsInRequests") val showDownloadsInRequests: Boolean = false,
	@SerialName("DownloadsPagePollingEnabled") val downloadsPagePollingEnabled: Boolean = false,
	@SerialName("DownloadsPollIntervalSeconds") val downloadsPollIntervalSeconds: Int = 30,
	@SerialName("CalendarPageEnabled") val calendarPageEnabled: Boolean = false,
	@SerialName("CalendarFirstDayOfWeek") val calendarFirstDayOfWeek: Int? = null,
	@SerialName("CalendarTimeFormat") val calendarTimeFormat: String? = null,
	@SerialName("SyncJellyseerrWatchlist") val syncJellyseerrWatchlist: Boolean = false,

	// Metadata / details enrichments
	@SerialName("TmdbEnabled") val tmdbEnabled: Boolean = false,
	@SerialName("MdblistEnabled") val mdblistEnabled: Boolean = false,
	@SerialName("MdblistRatingsEnabled") val mdblistRatingsEnabled: Boolean = false,
	@SerialName("MdblistRatingsShowOnItemDetails") val mdblistRatingsShowOnItemDetails: Boolean = false,
	@SerialName("MdblistRatingsShowPercentSymbol") val mdblistRatingsShowPercentSymbol: Boolean = false,
	@SerialName("ShowAwards") val showAwards: Boolean = false,
	@SerialName("ShowReleaseDates") val showReleaseDates: Boolean = false,
	@SerialName("ElsewhereEnabled") val elsewhereEnabled: Boolean = false,
	@SerialName("ElsewhereSeasonProviders") val elsewhereSeasonProviders: Boolean = false,
	@SerialName("DoesTheDogDieEnabled") val doesTheDogDieEnabled: Boolean = false,
	@SerialName("ShowUserReviews") val showUserReviews: Boolean = false,
	@SerialName("ShowUserRatingOnPosters") val showUserRatingOnPosters: Boolean = false,
	@SerialName("ShowReviews") val showReviews: Boolean = false,
	@SerialName("ShowAnilistReviews") val showAnilistReviews: Boolean = false,
	@SerialName("ReviewTranslationEnabled") val reviewTranslationEnabled: Boolean = false,
	@SerialName("WatchProgressDefaultMode") val watchProgressDefaultMode: String? = null,

	// Other pages
	@SerialName("BookmarksEnabled") val bookmarksEnabled: Boolean = false,
	@SerialName("ActivityFeedEnabled") val activityFeedEnabled: Boolean = false,
	@SerialName("ActiveStreamsEnabled") val activeStreamsEnabled: Boolean = false,
	@SerialName("ActiveStreamsAllUsers") val activeStreamsAllUsers: Boolean = false,
	@SerialName("CatchUpEnabled") val catchUpEnabled: Boolean = false,
	@SerialName("ShowPlaybackRatingBadge") val showPlaybackRatingBadge: Boolean = false,
	@SerialName("NativePosterTagsEnabled") val nativePosterTagsEnabled: Boolean = false,
	@SerialName("MaintenanceModeEnabled") val maintenanceModeEnabled: Boolean = false,
	@SerialName("MaintenanceModeMessage") val maintenanceModeMessage: String? = null,
	@SerialName("MaintenanceModeEndsAt") val maintenanceModeEndsAt: String? = null,
	@SerialName("LetterboxdEnabled") val letterboxdEnabled: Boolean = false,
)

/**
 * The fields of `user-settings/{userId}/settings.json` the TV client reads. Null means "not stored for this
 * user"; the public config's default applies then. Writes go through [EnhancedSettingsRepository], which keeps
 * the whole document intact.
 */
@Serializable
data class EnhancedUserSettings(
	@SerialName("RemoveContinueWatchingEnabled") val removeContinueWatchingEnabled: Boolean? = null,
	@SerialName("ShowFileSizes") val showFileSizes: Boolean? = null,
	@SerialName("ShowWatchProgress") val showWatchProgress: Boolean? = null,
	@SerialName("WatchProgressMode") val watchProgressMode: String? = null,
	@SerialName("WatchProgressTimeFormat") val watchProgressTimeFormat: String? = null,
	@SerialName("ShowAudioLanguages") val showAudioLanguages: Boolean? = null,
	@SerialName("PeopleTagsEnabled") val peopleTagsEnabled: Boolean? = null,
	@SerialName("UseNativePosterTags") val useNativePosterTags: Boolean? = null,
	@SerialName("RandomButtonEnabled") val randomButtonEnabled: Boolean? = null,
	@SerialName("RandomUnwatchedOnly") val randomUnwatchedOnly: Boolean? = null,
	@SerialName("RandomIncludeMovies") val randomIncludeMovies: Boolean? = null,
	@SerialName("RandomIncludeShows") val randomIncludeShows: Boolean? = null,
	@SerialName("ReviewsExpandedByDefault") val reviewsExpandedByDefault: Boolean? = null,
	@SerialName("PauseScreenEnabled") val pauseScreenEnabled: Boolean? = null,
	@SerialName("PauseScreenDelaySeconds") val pauseScreenDelaySeconds: Int? = null,
	@SerialName("ShowRatingInPlayer") val showRatingInPlayer: Boolean? = null,
	@SerialName("AutoSkipIntro") val autoSkipIntro: Boolean? = null,
	@SerialName("AutoSkipOutro") val autoSkipOutro: Boolean? = null,
	@SerialName("CalendarDefaultViewMode") val calendarDefaultViewMode: String? = null,
	@SerialName("DisplayLanguage") val displayLanguage: String? = null,
	/** Only present on GET; derived by the server from the caller's policy. Never written back. */
	@SerialName("IsAdmin") val isAdmin: Boolean? = null,
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
	/** The plugin version string from `GET version`, when known. */
	val pluginVersion: String? = null,
	/** Whether the signed-in user is a Jellyfin administrator, from the user's policy. */
	val isAdmin: Boolean = false,
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

	// Page gates
	val discover get() = seerrUsable && config.recommendationsPageEnabled
	val requests get() = seerrUsable && config.downloadsPageEnabled
	val calendar get() = available && config.calendarPageEnabled
	val bookmarks get() = available && config.bookmarksEnabled
	val activity get() = available && config.activityFeedEnabled
	val activeStreams get() = available && config.activeStreamsEnabled && (isAdmin || config.activeStreamsAllUsers)
}
