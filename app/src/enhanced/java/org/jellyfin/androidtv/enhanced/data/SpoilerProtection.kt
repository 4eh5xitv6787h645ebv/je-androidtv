package org.jellyfin.androidtv.enhanced.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
 * Guard is on (or may be) but its lists couldn't be read; [collectionMovieIds] null means a guarded collection's
 * movies couldn't be listed. Both fail closed.
 */
class SpoilerSnapshot(
	/** Spoiler Guard is on for this server. When false everything is [Protection.NONE]. */
	val enabled: Boolean,
	val state: SpoilerGuardState?,
	val policy: SpoilerPolicy,
	/**
	 * Movies of the guarded collections (dash-less, lower case); the server protects them like listed movies. May
	 * include movies that aren't members (see [SpoilerProtection]), never misses one.
	 */
	val collectionMovieIds: Set<String>?,
	/**
	 * The plugin's configuration couldn't be read, so even whether Spoiler Guard is on is unknown: everything is
	 * [Protection.FULL]. Pages should offer a retry rather than show external data with everything withheld.
	 */
	val configUnavailable: Boolean = false,
) {
	/** False when protection is on but some of it couldn't be resolved, so some titles are withheld blindly. */
	val complete get() = !enabled || (state != null && collectionMovieIds != null)

	/**
	 * Whether anything could be protected: the lists have an entry (pending opt-ins included) or couldn't be read.
	 * False means every title is [Protection.NONE].
	 */
	val guardsAnything: Boolean
		get() = enabled && state.let {
			it == null || it.series.isNotEmpty() || it.movies.isNotEmpty() || it.collections.isNotEmpty() || it.pendingTmdb.isNotEmpty()
		}

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

		/** Configuration unknown: protect everything. */
		val UNKNOWN = SpoilerSnapshot(
			enabled = true,
			state = null,
			policy = SpoilerPolicy.STRICT,
			collectionMovieIds = null,
			configUnavailable = true,
		)

		fun normalize(id: String) = id.replace("-", "").lowercase()
	}
}

/**
 * Resolves Spoiler Guard protection for content the plugin doesn't filter itself. Membership comes from the user's
 * Spoiler Guard document, the policy from the public config and the document's `Prefs`. Every failure fails closed,
 * including an unreadable plugin configuration ([EnhancedState.configUnavailable]).
 *
 * A complete snapshot is reused for [CACHE_TTL_MS] so the contributors of one details screen share it. It is only
 * cached when no Spoiler Guard write in this client overlapped its load, and only reused while
 * [SpoilerGuardRepository.generation] hasn't moved since and the repository still holds the same state object, so a
 * title the user just guarded is never checked against an older list (not even one read while the write was in flight).
 */
class SpoilerProtection(
	private val enhancedRepository: EnhancedRepository,
	private val spoilerGuardRepository: SpoilerGuardRepository,
	private val enhancedApi: EnhancedApi,
	private val clock: () -> Long = System::currentTimeMillis,
) {
	private class Cached(
		val token: String?,
		val enhanced: EnhancedState,
		val generation: Long,
		val at: Long,
		val snapshot: SpoilerSnapshot,
	)

	private val mutex = Mutex()
	private var cached: Cached? = null

	/** The user's lists and policy now. Never throws (except for cancellation): failures give a fail-closed snapshot. */
	suspend fun snapshot(): SpoilerSnapshot {
		val enhanced = enhancedRepository.ensureLoaded()
		if (enhanced.configUnavailable) return SpoilerSnapshot.UNKNOWN
		if (!enhanced.spoilerGuard) return SpoilerSnapshot.DISABLED
		// Concurrent callers (one details screen's contributors) wait for one load instead of each starting their own
		return mutex.withLock {
			val token = enhancedApi.accessToken
			cached?.takeIf {
				it.token == token &&
					it.enhanced == enhanced &&
					it.generation == spoilerGuardRepository.generation &&
					clock() - it.at in 0 until CACHE_TTL_MS &&
					spoilerGuardRepository.cachedState() === it.snapshot.state
			}?.let { return@withLock it.snapshot }

			val generation = spoilerGuardRepository.generation
			load(enhanced).also { snapshot ->
				// A write that started or ended during the load may not be in it: use it this once, don't keep it
				val unchanged = spoilerGuardRepository.generation == generation
				cached = if (snapshot.complete && unchanged) Cached(token, enhanced, generation, clock(), snapshot) else null
			}
		}
	}

	private suspend fun load(enhanced: EnhancedState): SpoilerSnapshot {
		val state = try {
			spoilerGuardRepository.getState()
		} catch (err: CancellationException) {
			throw err
		} catch (err: Exception) {
			Timber.w(err, "Failed to read Spoiler Guard state; withholding protected data")
			null
		}
		val members = if (state == null || state.collections.isEmpty()) emptySet() else collectionMovieIds(state, enhanced)
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

	/**
	 * The movies in the user's guarded collections, or null when membership couldn't be established.
	 *
	 * The collections are listed as the user, and the plugin's Hidden Content filter drops hidden items from that
	 * listing (`GET /Items` is a "library" surface, and the filter lowers `TotalRecordCount` to match), although the
	 * server's Spoiler Guard still protects them as members. No unfiltered route lists a collection's children, so every
	 * item in the user's Hidden Content document is counted as a possible member too. That over-protects hidden movies
	 * outside the collections, which the user doesn't see in the library anyway.
	 *
	 * The document alone can't prove the union complete: the plugin reads an empty, corrupt or unreadable file as an
	 * empty document (HTTP 200), while its filter keeps hiding with the copy it cached up to 30 s earlier. So each
	 * collection's own `ChildCount` (`GET /Items/{id}`, a route the filter doesn't touch, so it counts hidden members
	 * too) is compared with its listing: when members are missing from the listing and the document doesn't have at
	 * least that many other ids that could be them, membership is unknown and every movie fails closed.
	 */
	private suspend fun collectionMovieIds(state: SpoilerGuardState, enhanced: EnhancedState): Set<String>? = try {
		val api = enhancedApi.pinned()
		val userId = api.currentUserId() ?: error("No signed-in user")
		val ids = SpoilerGuardRepository.entries(state)
			.filter { it.kind == SpoilerGuardRepository.Kind.COLLECTION }
			.map { entry -> entry.itemId?.toString() ?: entry.key }
		coroutineScope {
			val hiddenCall = async { if (enhanced.hiddenContent) hiddenItemIds(api, userId) else emptySet() }
			val listings = ids.map { collectionId ->
				async {
					val childCount = if (enhanced.hiddenContent) async { childCount(api, collectionId, userId) } else null
					val response = api.get("/Items", mapOf("parentId" to collectionId, "userId" to userId, "enableImages" to false, "enableUserData" to false))
					val listed = ((response as? JsonObject)?.get("Items") as? JsonArray).orEmpty().mapNotNull { element ->
						((element as? JsonObject)?.get("Id") as? JsonPrimitive)?.contentOrNull?.let(SpoilerSnapshot::normalize)
					}.toSet()
					CollectionListing(listed, childCount?.await())
				}
			}.awaitAll()
			val hidden = hiddenCall.await()
			if (listings.any { !it.accountedFor(hidden) }) {
				Timber.w("A guarded collection has members that are neither listed nor hidden; withholding data for every movie")
				null
			} else {
				listings.flatMap { it.listed }.toSet() + hidden
			}
		}
	} catch (err: CancellationException) {
		throw err
	} catch (err: Exception) {
		Timber.w(err, "Failed to list guarded collections; withholding data for every movie")
		null
	}

	/** The number of items the server links to a collection for this user, hidden ones included. */
	private suspend fun childCount(api: EnhancedApi, collectionId: String, userId: String): Int {
		val item = api.get("/Items/$collectionId", mapOf("userId" to userId)) as? JsonObject
			?: error("Unexpected collection response")
		return (item["ChildCount"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: error("Collection without ChildCount")
	}

	/**
	 * Every item id in the user's Hidden Content document (any scope), or nothing when the user's settings don't filter
	 * library lists. A read failure is thrown: membership is then unknown.
	 */
	private suspend fun hiddenItemIds(api: EnhancedApi, userId: String): Set<String> {
		val document = api.get("user-settings/${normalize(userId)}/hidden-content.json") as? JsonObject
			?: error("Unexpected Hidden Content response")
		return hiddenLibraryItemIds(HiddenContentRepository.Snapshot(document))
	}

	/**
	 * One guarded collection as the user's listing shows it, and [childCount], the server's own count of its members
	 * (null when the listing can't be filtered, so nothing needs accounting for).
	 */
	class CollectionListing(val listed: Set<String>, val childCount: Int?) {
		/**
		 * Whether the members missing from the listing could all be among [hidden]: at least as many hidden ids that
		 * aren't listed as there are missing members. Fewer means a member is hidden by a document we can't see.
		 */
		fun accountedFor(hidden: Set<String>): Boolean {
			val missing = (childCount ?: return true) - listed.size
			return missing <= 0 || hidden.count { it !in listed } >= missing
		}
	}

	companion object {
		/** How long a complete snapshot is reused: about one details screen load. */
		const val CACHE_TTL_MS = 10_000L

		/**
		 * The ids the plugin's Hidden Content filter may drop from a collection listing: none when the user's settings
		 * leave library lists unfiltered, otherwise every entry's item id regardless of scope (the conservative
		 * superset of the `global` and `library` scopes the filter applies there).
		 */
		fun hiddenLibraryItemIds(snapshot: HiddenContentRepository.Snapshot): Set<String> {
			if (!snapshot.settings.enabled || !snapshot.settings.filterLibrary) return emptySet()
			return snapshot.entries
				.mapNotNull { (it["ItemId"] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) }
				.map(::normalize)
				.toSet()
		}
	}
}

private fun normalize(id: String) = SpoilerSnapshot.normalize(id)
