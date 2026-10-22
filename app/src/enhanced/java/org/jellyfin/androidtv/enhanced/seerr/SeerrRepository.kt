package org.jellyfin.androidtv.enhanced.seerr

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.jellyfin.androidtv.enhanced.api.EnhancedApi
import org.jellyfin.androidtv.enhanced.data.EnhancedRepository
import org.jellyfin.androidtv.enhanced.data.HiddenContentRepository
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap

/**
 * Seerr discovery and requests through the Jellyfin Enhanced proxy. Mirrors the web's
 * discovery modules (`js/jellyseerr/discovery`): Jellyfin people/genres/studios are mapped to TMDB ids and the matching
 * movie and TV feeds are interleaved.
 */
class SeerrRepository(
	private val enhancedApi: EnhancedApi,
	private val enhancedRepository: EnhancedRepository,
	private val hiddenContentRepository: HiddenContentRepository,
) {
	companion object {
		const val MEDIA_MOVIE = "movie"
		const val MEDIA_TV = "tv"

		// Port of TV_NETWORKS in js/jellyseerr/discovery/network-discovery.js
		private val TV_NETWORKS = mapOf(
			"netflix" to 213, "hbo" to 49, "hbo max" to 3186, "max" to 3186, "amazon" to 1024,
			"amazon prime video" to 1024, "prime video" to 1024, "apple tv+" to 2552, "apple tv" to 2552,
			"disney+" to 2739, "disney plus" to 2739, "hulu" to 453, "paramount+" to 4330, "paramount plus" to 4330,
			"peacock" to 3353, "fx" to 88, "fx networks" to 88, "amc" to 174, "showtime" to 67, "starz" to 318,
			"abc" to 2, "nbc" to 6, "cbs" to 16, "fox" to 19, "the cw" to 71, "cw" to 71, "bbc" to 4, "bbc one" to 4,
			"bbc two" to 332, "itv" to 9, "channel 4" to 26, "sky" to 1063, "syfy" to 77, "usa network" to 30,
			"tnt" to 41, "tbs" to 68, "a&e" to 129, "history" to 65, "discovery" to 64, "national geographic" to 43,
			"nat geo" to 43, "adult swim" to 80, "cartoon network" to 56, "nickelodeon" to 13, "comedy central" to 47,
			"mtv" to 33, "bet" to 24, "espn" to 29, "crunchyroll" to 1112, "anime network" to 171, "funimation" to 102,
			"youtube" to 247, "youtube premium" to 1436,
		)

		private const val MAX_TMDB_PAGE = 500

		// TMDB TV genres: News, Reality, Talk
		private val NON_FICTION_TV_GENRES = setOf(10763, 10764, 10767)
		private val SELF_ROLE = Regex("^(self|himself|herself|themselves)\\b.*", RegexOption.IGNORE_CASE)
	}

	/** One paged list of results. Page numbers start at 1. */
	fun interface Feed {
		suspend fun load(page: Int): SeerrPage
	}

	private val genreCache = ConcurrentHashMap<String, List<SeerrGenre>>()

	private suspend inline fun <reified T> getAs(path: String, query: Map<String, Any?> = emptyMap()): T =
		EnhancedApi.json.decodeFromJsonElement(enhancedApi.get(path, query))

	private fun pageFeed(path: String, extra: Map<String, Any?> = emptyMap()) = Feed { page ->
		if (page > MAX_TMDB_PAGE) SeerrPage(page = page, totalPages = MAX_TMDB_PAGE)
		else getAs<SeerrPage>(path, extra + ("page" to page)).let { result ->
			// TV-by-company/network feeds can omit mediaType
			val mediaType = when {
				"/tv" in path -> MEDIA_TV
				"/movie" in path -> MEDIA_MOVIE
				else -> null
			}
			if (mediaType == null) result
			else result.copy(results = result.results.map { if (it.mediaType == null) it.copy(mediaType = mediaType) else it })
		}
	}

	// Feeds for each discovery source

	/** Resolve [source] into its feeds. An empty list means nothing could be matched on TMDB. */
	suspend fun feedsFor(source: SeerrSource): List<Feed> = when (source) {
		is SeerrSource.Recommended -> listOf(pageFeed("jellyseerr/${source.mediaType}/${source.tmdbId}/recommendations"))
		is SeerrSource.Similar -> listOf(pageFeed("jellyseerr/${source.mediaType}/${source.tmdbId}/similar"))
		is SeerrSource.Person -> personFeeds(source)
		is SeerrSource.Genre -> genreFeeds(source.name)
		is SeerrSource.Studio -> studioFeeds(source)
		is SeerrSource.Keyword -> keywordFeeds(source.name)
	}

	private suspend fun personFeeds(source: SeerrSource.Person): List<Feed> {
		val tmdbId = source.tmdbPersonId
			?: source.jellyfinPersonId?.let { id ->
				runCatching {
					(enhancedApi.get("person/$id").jsonObject["tmdbId"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
				}.getOrNull()
			}
			?: searchPerson(source.name)
			?: return emptyList()

		// Combined credits aren't paged server side; hand them out in pages of 40 like the web does
		var all: List<SeerrResult>? = null
		return listOf(Feed { page ->
			val credits = all ?: getAs<SeerrCredits>("jellyseerr/person/$tmdbId/combined_credits").let { credits ->
				(credits.cast + credits.crew)
					.filter { it.mediaType == MEDIA_MOVIE || it.mediaType == MEDIA_TV }
					// Talk show and award show guest spots drown out the actual filmography
					.filterNot { it.genreIds.any(NON_FICTION_TV_GENRES::contains) || SELF_ROLE.matches(it.character.orEmpty()) }
					.distinctBy { it.dedupeKey }
					.sortedByDescending { it.voteCount ?: 0 }
			}.also { all = it }
			val size = 40
			val pages = maxOf(1, (credits.size + size - 1) / size)
			SeerrPage(
				page = page,
				totalPages = pages,
				totalResults = credits.size,
				results = credits.drop((page - 1) * size).take(size),
			)
		})
	}

	// The lookups below return null for "no match" and throw when the lookup itself fails, so the screen can offer
	// Retry instead of reporting that nothing exists

	private suspend fun searchPerson(name: String): Int? {
		val people = getAs<SeerrPage>("tmdb/search/person", mapOf("query" to name)).results
			.filter { it.mediaType == "person" }
		val exact = people.filter { it.name.equals(name, ignoreCase = true) }
		return (exact.ifEmpty { people }).maxByOrNull { (if (it.profilePath != null) 2 else 0) }?.id
	}

	private suspend fun genres(mediaType: String) = genreCache.getOrPut(mediaType) {
		getAs<List<SeerrGenre>>("tmdb/genres/$mediaType")
	}

	private fun matchGenre(genres: List<SeerrGenre>, name: String): Int? =
		genres.firstOrNull { it.name.equals(name, ignoreCase = true) }?.id
			?: genres.firstOrNull {
				it.name.contains(name, ignoreCase = true) || name.contains(it.name, ignoreCase = true)
			}?.id

	private suspend fun genreFeeds(name: String): List<Feed> = coroutineScope {
		// Only successful catalogs are cached; a failure propagates so the whole lookup can be retried
		val movieGenres = async { genres(MEDIA_MOVIE) }
		val tvGenres = async { genres(MEDIA_TV) }
		listOfNotNull(
			matchGenre(movieGenres.await(), name)?.let { pageFeed("jellyseerr/discover/movies/genre/$it") },
			matchGenre(tvGenres.await(), name)?.let { pageFeed("jellyseerr/discover/tv/genre/$it") },
		)
	}

	private fun knownNetworkId(name: String): Int? {
		val key = name.lowercase().trim()
		return TV_NETWORKS[key] ?: TV_NETWORKS.entries.firstOrNull { (n, _) -> key.contains(n) || n.contains(key) }?.value
	}

	private suspend fun studioFeeds(source: SeerrSource.Studio): List<Feed> {
		val mappedCompanyId = source.jellyfinStudioId?.let { id ->
			runCatching {
				(enhancedApi.get("studio/$id").jsonObject["tmdbId"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
			}.getOrNull()
		}
		val networkId = knownNetworkId(source.name)
		val companyId = mappedCompanyId ?: try {
			searchCompany(source.name)
		} catch (err: CancellationException) {
			throw err
		} catch (err: Exception) {
			// A known network still gives a TV feed; without one there's nothing to show, so let the caller retry
			if (networkId == null) throw err
			Timber.w(err, "Company search failed for %s", source.name)
			null
		}
		val tmdbEnabled = enhancedRepository.current.config.tmdbEnabled

		return listOfNotNull(
			companyId?.let { pageFeed("jellyseerr/discover/movies/studio/$it") },
			when {
				networkId != null -> pageFeed("jellyseerr/discover/tv/network/$networkId")
				companyId != null && tmdbEnabled -> pageFeed("jellyseerr/discover/tv/studio/$companyId")
				else -> null
			},
		)
	}

	private suspend fun searchCompany(name: String): Int? = run {
		val results = enhancedApi.get("tmdb/search/company", mapOf("query" to name)).jsonObject["results"]
			?.jsonArray.orEmpty().mapNotNull { it as? JsonObject }
		val exact = results.firstOrNull { (it["name"] as? JsonPrimitive)?.contentOrNull.equals(name, ignoreCase = true) }
		// Avoid matching "Disney+" or "Paramount+" to an unrelated company
		if (exact == null && name.contains('+')) return@run null
		(exact ?: results.maxByOrNull { obj ->
			val us = if ((obj["origin_country"] as? JsonPrimitive)?.contentOrNull == "US") 2 else 0
			val logo = if ((obj["logo_path"] as? JsonPrimitive)?.contentOrNull != null) 1 else 0
			us + logo
		})?.get("id")?.let { (it as? JsonPrimitive)?.intOrNull }
	}

	private suspend fun keywordFeeds(name: String): List<Feed> {
		val results = enhancedApi.get("tmdb/search/keyword", mapOf("query" to name)).jsonObject["results"]
			?.jsonArray.orEmpty().mapNotNull { it as? JsonObject }
		val keywordId = (results.firstOrNull { (it["name"] as? JsonPrimitive)?.contentOrNull.equals(name, ignoreCase = true) } ?: results.firstOrNull())
			?.get("id")?.let { (it as? JsonPrimitive)?.intOrNull }
			?: return emptyList()

		return listOf(
			pageFeed("jellyseerr/discover/movies/keyword/$keywordId"),
			pageFeed("jellyseerr/discover/tv/keyword/$keywordId"),
		)
	}

	/**
	 * Pages through several feeds together. Each feed keeps its own page number, so a feed that fails is retried at
	 * the same page next time instead of being skipped while the others move on.
	 */
	class FeedCursor(private val feeds: List<Feed>) {
		internal val nextPage = IntArray(feeds.size) { 1 }
		internal val done = BooleanArray(feeds.size)
		internal fun feed(index: Int) = feeds[index]
		internal val size get() = feeds.size

		val hasMore get() = done.any { !it }
	}

	/** One load: the new results, and whether some feed failed (its page will be retried). */
	class Batch(val results: List<SeerrResult>, val partialFailure: Boolean)

	/**
	 * Load the next page of every unfinished feed and interleave them. Results are filtered the way the web filters
	 * discovery cards: duplicates, hidden titles, and (when configured) titles in the library or blocklisted.
	 * Throws when every attempted feed failed.
	 */
	suspend fun loadNext(cursor: FeedCursor, recommendations: Boolean = false): Batch = coroutineScope {
		val indices = (0 until cursor.size).filter { !cursor.done[it] }
		val attempts = indices.map { i ->
			async {
				val page = cursor.nextPage[i]
				if (page > MAX_TMDB_PAGE) Result.success(SeerrPage(page = page, totalPages = MAX_TMDB_PAGE))
				else runCatching { cursor.feed(i).load(page) }.onFailure { Timber.w(it) }
			}
		}.map { it.await() }

		if (attempts.isNotEmpty() && attempts.all { it.isFailure }) throw attempts.first().exceptionOrNull()!!

		val lists = mutableListOf<List<SeerrResult>>()
		indices.zip(attempts).forEach { (i, attempt) ->
			val page = attempt.getOrNull() ?: return@forEach
			lists += page.results
			cursor.nextPage[i]++
			if (page.page >= page.totalPages || page.page >= MAX_TMDB_PAGE || page.results.isEmpty()) cursor.done[i] = true
		}
		val interleaved = buildList {
			val max = lists.maxOfOrNull { it.size } ?: 0
			for (j in 0 until max) for (list in lists) list.getOrNull(j)?.let(::add)
		}
		Batch(filterResults(interleaved, recommendations), partialFailure = attempts.any { it.isFailure })
	}

	suspend fun filterResults(results: List<SeerrResult>, recommendations: Boolean = false): List<SeerrResult> {
		val state = enhancedRepository.current
		val hidden = if (state.hiddenContent) runCatching { hiddenContentRepository.get() }.getOrNull() else null
		val filterHidden = hidden != null && hidden.settings.enabled &&
			(if (recommendations) hidden.settings.filterRecommendations else hidden.settings.filterDiscovery)
		val hiddenIds = if (filterHidden) hidden!!.hiddenTmdbIds else emptySet()
		val hiddenItemIds = if (filterHidden) hidden!!.hiddenItemIds else emptySet()

		val seen = HashSet<String>()
		val guard = state.spoilerGuard
		return results.filter { item ->
			seen.add(item.dedupeKey) &&
				item.id.toString() !in hiddenIds &&
				item.mediaInfo?.jellyfinMediaId?.replace("-", "")?.lowercase() !in hiddenItemIds &&
				!(state.config.jellyseerrExcludeLibraryItems && item.mediaInfo?.jellyfinMediaId != null) &&
				!(state.config.jellyseerrExcludeBlocklistedItems && item.mediaInfo?.status == SeerrMediaStatus.BLOCKLISTED)
		}.map { item ->
			// Library titles: load the poster through the server while Spoiler Guard is on, so its artwork rules apply
			val libraryId = item.mediaInfo?.jellyfinMediaId ?: item.mediaInfo?.jellyfinMediaId4k
			if (guard && libraryId != null) item.copy(libraryPosterUrl = enhancedApi.imageUrl("/Items/$libraryId/Images/Primary", null, 300))
			else item
		}
	}

	/** Movies of a Jellyfin collection's TMDB collection that aren't available yet, oldest first. */
	suspend fun collectionMissing(boxSetId: String): List<SeerrResult> {
		val tmdbId = (enhancedApi.get("boxset/$boxSetId").jsonObject["tmdbId"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
			?: return emptyList()
		val parts = enhancedApi.get("jellyseerr/collection/$tmdbId").jsonObject["parts"]?.jsonArray.orEmpty()
			.mapNotNull { runCatching { EnhancedApi.json.decodeFromJsonElement<SeerrResult>(it) }.getOrNull() }
			.map { if (it.mediaType == null) it.copy(mediaType = MEDIA_MOVIE) else it }
			.filter { it.status != SeerrMediaStatus.AVAILABLE }
			.sortedBy { it.releaseDate ?: "9999" }
		return filterResults(parts)
	}

	// Details and requests

	suspend fun details(mediaType: String, tmdbId: Int): SeerrDetails = getAs("jellyseerr/$mediaType/$tmdbId")

	suspend fun partialRequestSettings(): SeerrPartialRequestSettings? =
		runCatching { getAs<SeerrPartialRequestSettings>("jellyseerr/settings/partial-requests") }.getOrNull()

	/** Sonarr's TVDB candidates for a show. Throws when the lookup fails; a 404 means no Sonarr is configured. */
	suspend fun tvdbCandidates(tmdbId: Int): List<SeerrTvdbCandidate> =
		getAs<List<SeerrTvdbCandidate>>("jellyseerr/sonarr/lookup/$tmdbId").filter { (it.tvdbId ?: 0) > 0 }.take(6)

	suspend fun quota(): SeerrQuota? = runCatching { getAs<SeerrQuota>("jellyseerr/quota") }.getOrNull()

	/**
	 * Submit a request. [seasons] is only used for TV; null requests every season. Never retried automatically:
	 * after a failure the caller should reload the details to see whether the request landed.
	 */
	suspend fun request(mediaType: String, tmdbId: Int, seasons: List<Int>? = null, is4k: Boolean = false, tvdbId: Int? = null): JsonElement {
		val body = buildJsonObject {
			put("mediaType", mediaType)
			put("mediaId", tmdbId)
			if (mediaType == MEDIA_TV) {
				if (seasons == null) put("seasons", "all")
				else putJsonArray("seasons") { seasons.forEach { add(JsonPrimitive(it)) } }
				if (tvdbId != null) put("tvdbId", tvdbId)
			}
			if (is4k) put("is4k", true)
		}
		return enhancedApi.post("jellyseerr/request", body)
	}

}
