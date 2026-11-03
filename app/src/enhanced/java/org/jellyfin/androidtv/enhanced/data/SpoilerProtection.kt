package org.jellyfin.androidtv.enhanced.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.jellyfin.androidtv.enhanced.api.EnhancedApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import timber.log.Timber

/**
 * The Spoiler Guard categories in effect for the signed-in user, from the admin's `SpoilerStrip*` flags and the
 * user's `Prefs`. Mirrors the plugin's `ShouldStrip(adminOn, userOverride)`: the admin flag is the cap, and a user
 * override of `false` opts out of that category (null or true follow the admin).
 */
data class SpoilerPolicy(
	val hideSeriesOverview: Boolean,
	val hideOverview: Boolean,
	val hideTags: Boolean,
	val hideChapters: Boolean,
	val hideTaglines: Boolean,
	val hideRatings: Boolean,
	val hideAirDate: Boolean,
	val hideTitles: Boolean,
	val hideCast: Boolean,
	val hideReviews: Boolean,
	val hideTrickplay: Boolean,
	/**
	 * Whether the server may reveal some fields of the next unwatched episode and its season (admin
	 * `SpoilerAdvancedMode` and the user's `UseAdvancedCategories` not false). The client can't tell which episodes
	 * that covers (the boundary and the per-category masks aren't public), so it never relaxes anything for it: with or
	 * without it, client-side protection is the uniform full policy.
	 */
	val advancedReveals: Boolean,
) {
	companion object {
		/** Every category on: what applies when the policy itself is unknown. */
		val STRICT = SpoilerPolicy(
			hideSeriesOverview = true,
			hideOverview = true,
			hideTags = true,
			hideChapters = true,
			hideTaglines = true,
			hideRatings = true,
			hideAirDate = true,
			hideTitles = true,
			hideCast = true,
			hideReviews = true,
			hideTrickplay = true,
			advancedReveals = false,
		)

		fun strip(adminOn: Boolean, userOverride: Boolean?) = adminOn && (userOverride ?: true)

		/** The policy for [config] and the user's [prefs]. Unknown prefs follow the admin, the strictest reading. */
		fun of(config: EnhancedPublicConfig, prefs: SpoilerGuardPrefs?) = SpoilerPolicy(
			hideSeriesOverview = strip(config.spoilerStripSeriesOverview, prefs?.hideSeriesDescriptions),
			hideOverview = strip(config.spoilerStripOverview, prefs?.hideEpisodeDescriptions),
			hideTags = strip(config.spoilerStripTags, prefs?.hideTags),
			hideChapters = strip(config.spoilerStripChapters, prefs?.hideChapterNames),
			hideTaglines = strip(config.spoilerStripTaglines, prefs?.hideTaglines),
			hideRatings = strip(config.spoilerStripRatings, prefs?.hideRatings),
			hideAirDate = strip(config.spoilerStripPremiereDate, prefs?.hideAirDate),
			hideTitles = strip(config.spoilerReplaceTitle, prefs?.replaceEpisodeTitles),
			hideCast = strip(config.spoilerStripCast, prefs?.hideCast),
			hideReviews = strip(config.spoilerStripReviews, prefs?.hideReviews),
			hideTrickplay = strip(config.spoilerBlurTrickplay, prefs?.hideTrickplay),
			advancedReveals = config.spoilerAdvancedMode && prefs?.useAdvancedCategories != false,
		)
	}
}

/**
 * What to withhold from external sources (TMDB, MDBList, Seerr, Sonarr/Radarr, reviews) for one title. Library DTOs
 * are already filtered by the plugin; this is for everything that isn't.
 */
data class Protection(
	/** The title is in the user's Spoiler Guard lists (or its protection couldn't be ruled out). */
	val guarded: Boolean,
	val hideOverview: Boolean,
	/** Episode titles (`ReplaceEpisodeTitles`). Series and movie names are never hidden. */
	val hideTitles: Boolean,
	val hideRatings: Boolean,
	val hideAirDate: Boolean,
	val hideCast: Boolean,
	val hideReviews: Boolean,
	/** Tags and other spoiler phrases (content warnings). */
	val hideTags: Boolean,
	/** External artwork; the server's own images carry its blur. Not user-overridable. */
	val hideArtwork: Boolean,
) {
	companion object {
		val NONE = Protection(
			guarded = false,
			hideOverview = false,
			hideTitles = false,
			hideRatings = false,
			hideAirDate = false,
			hideCast = false,
			hideReviews = false,
			hideTags = false,
			hideArtwork = false,
		)

		/** Fail closed: protection is on but membership or policy couldn't be established. */
		val FULL = Protection(
			guarded = true,
			hideOverview = true,
			hideTitles = true,
			hideRatings = true,
			hideAirDate = true,
			hideCast = true,
			hideReviews = true,
			hideTags = true,
			hideArtwork = true,
		)

		/**
		 * A guarded title under [policy]. [series] picks the series description policy. A [played] episode or movie is
		 * passed through by the server, so only reviews stay withheld (the web suppresses them per title).
		 */
		fun guarded(policy: SpoilerPolicy, series: Boolean = false, played: Boolean = false): Protection =
			if (played) NONE.copy(guarded = true, hideReviews = policy.hideReviews)
			else Protection(
				guarded = true,
				hideOverview = if (series) policy.hideSeriesOverview else policy.hideOverview,
				hideTitles = policy.hideTitles,
				hideRatings = policy.hideRatings,
				hideAirDate = policy.hideAirDate,
				hideCast = policy.hideCast,
				hideReviews = policy.hideReviews,
				hideTags = policy.hideTags,
				hideArtwork = true,
			)
	}
}

/**
 * The user's Spoiler Guard lists and policy at one moment, for checking many titles. [state] null means Spoiler
 * Guard is on but its lists couldn't be read; [collectionMovieIds] null means a guarded collection's movies couldn't
 * be listed. Both fail closed.
 */
class SpoilerSnapshot(
	/** Spoiler Guard is on for this server. When false everything is [Protection.NONE]. */
	val enabled: Boolean,
	val state: SpoilerGuardState?,
	val policy: SpoilerPolicy,
	/** Movies of the guarded collections (dash-less, lower case); the server protects them like listed movies. */
	val collectionMovieIds: Set<String>?,
) {
	/** False when protection is on but some of it couldn't be resolved, so some titles are withheld blindly. */
	val complete get() = !enabled || (state != null && collectionMovieIds != null)

	private val series by lazy { state?.series?.keys?.map(::normalize)?.toSet().orEmpty() }
	private val movies by lazy { state?.movies?.keys?.map(::normalize)?.toSet().orEmpty() }
	private val collections by lazy { state?.collections?.keys?.map(::normalize)?.toSet().orEmpty() }

	/** A series and everything in it (seasons, episodes, extras), by the series' Jellyfin id. */
	fun forSeries(seriesId: String?, played: Boolean = false, series: Boolean = false): Protection = when {
		!enabled -> Protection.NONE
		state == null -> Protection.FULL
		// An episode or season without its series can't be checked
		seriesId.isNullOrBlank() -> Protection.FULL
		normalize(seriesId) in this.series -> Protection.guarded(policy, series = series, played = played)
		else -> Protection.NONE
	}

	fun forMovie(movieId: String?, played: Boolean = false): Protection {
		if (!enabled) return Protection.NONE
		if (state == null || movieId.isNullOrBlank()) return Protection.FULL
		val key = normalize(movieId)
		if (key in movies) return Protection.guarded(policy, played = played)
		if (state.collections.isEmpty()) return Protection.NONE
		val members = collectionMovieIds ?: return Protection.FULL
		return if (key in members) Protection.guarded(policy, played = played) else Protection.NONE
	}

	fun forCollection(collectionId: String?): Protection = when {
		!enabled -> Protection.NONE
		state == null || collectionId.isNullOrBlank() -> Protection.FULL
		normalize(collectionId) in collections -> Protection.guarded(policy)
		else -> Protection.NONE
	}

	/** A title the user opted into before it reached the library (`PendingTmdb`, `tv:123` / `movie:456`). */
	fun isPending(mediaType: String, tmdbId: Int?): Boolean =
		tmdbId != null && state?.pendingTmdb?.containsKey("$mediaType:$tmdbId") == true

	/**
	 * A title known by its library id (series or movie, possibly absent) and TMDB id, as calendar and request
	 * entries carry them.
	 */
	fun forTitle(isMovie: Boolean, libraryId: String?, tmdbId: Int?): Protection {
		if (!enabled) return Protection.NONE
		if (state == null) return Protection.FULL
		val library = if (libraryId.isNullOrBlank()) Protection.NONE else if (isMovie) forMovie(libraryId) else forSeries(libraryId)
		if (library.guarded) return library
		return if (isPending(if (isMovie) "movie" else "tv", tmdbId)) Protection.guarded(policy) else Protection.NONE
	}

	/** Protection for a library item, as the server applies it: episodes and seasons follow their series. */
	fun forItem(item: BaseItemDto): Protection {
		if (!enabled) return Protection.NONE
		val played = item.userData?.played == true
		return when (item.type) {
			BaseItemKind.SERIES -> forSeries(item.id.toString(), series = true)
			// Seasons pass through on the server once any episode is watched; the client can't tell cheaply, so they
			// stay protected
			BaseItemKind.SEASON -> forSeries(item.seriesId?.toString())
			BaseItemKind.EPISODE -> forSeries(item.seriesId?.toString(), played = played)
			BaseItemKind.MOVIE -> forMovie(item.id.toString(), played = played)
			BaseItemKind.BOX_SET -> forCollection(item.id.toString())
			// Extras of a guarded series are protected; nothing else is guardable
			else -> item.seriesId?.let { forSeries(it.toString()) } ?: Protection.NONE
		}
	}

	companion object {
		val DISABLED = SpoilerSnapshot(enabled = false, state = null, policy = SpoilerPolicy.STRICT, collectionMovieIds = emptySet())

		fun normalize(id: String) = id.replace("-", "").lowercase()
	}
}

/**
 * Resolves Spoiler Guard protection for content the plugin doesn't filter itself. Membership comes from the user's
 * Spoiler Guard document, the policy from the public config and the document's `Prefs`. Every failure fails closed.
 */
class SpoilerProtection(
	private val enhancedRepository: EnhancedRepository,
	private val spoilerGuardRepository: SpoilerGuardRepository,
	private val enhancedApi: EnhancedApi,
) {
	/** The user's lists and policy now. Never throws (except for cancellation): failures give a fail-closed snapshot. */
	suspend fun snapshot(): SpoilerSnapshot {
		val enhanced = enhancedRepository.ensureLoaded()
		if (!enhanced.spoilerGuard) return SpoilerSnapshot.DISABLED
		val state = try {
			spoilerGuardRepository.getState()
		} catch (err: CancellationException) {
			throw err
		} catch (err: Exception) {
			Timber.w(err, "Failed to read Spoiler Guard state; withholding protected data")
			null
		}
		val members = if (state == null || state.collections.isEmpty()) emptySet() else collectionMovieIds(state)
		return SpoilerSnapshot(
			enabled = true,
			state = state,
			// The prefs are part of the document; without it, follow the admin policy (users can only opt out)
			policy = SpoilerPolicy.of(enhanced.config, state?.prefs),
			collectionMovieIds = members,
		)
	}

	/** The effective protection of one library item. */
	suspend fun effectiveFor(item: BaseItemDto): Protection = snapshot().forItem(item)

	/** The movies in the user's guarded collections, or null when any of them couldn't be listed. */
	private suspend fun collectionMovieIds(state: SpoilerGuardState): Set<String>? = try {
		val api = enhancedApi.pinned()
		val userId = api.currentUserId()
		val ids = SpoilerGuardRepository.entries(state)
			.filter { it.kind == SpoilerGuardRepository.Kind.COLLECTION }
			.map { entry -> entry.itemId?.toString() ?: entry.key }
		coroutineScope {
			ids.map { collectionId ->
				async {
					val response = api.get("/Items", mapOf("parentId" to collectionId, "userId" to userId, "enableImages" to false, "enableUserData" to false))
					((response as? JsonObject)?.get("Items") as? JsonArray).orEmpty().mapNotNull { element ->
						((element as? JsonObject)?.get("Id") as? JsonPrimitive)?.contentOrNull?.let(SpoilerSnapshot::normalize)
					}
				}
			}.awaitAll().flatten().toSet()
		}
	} catch (err: CancellationException) {
		throw err
	} catch (err: Exception) {
		Timber.w(err, "Failed to list guarded collections; withholding data for every movie")
		null
	}
}

private fun normalize(id: String) = SpoilerSnapshot.normalize(id)
