package org.jellyfin.androidtv.enhanced.requests

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import org.jellyfin.androidtv.enhanced.api.EnhancedApi
import org.jellyfin.androidtv.enhanced.data.EnhancedRepository
import org.jellyfin.androidtv.enhanced.data.HiddenContentRepository
import org.jellyfin.androidtv.enhanced.data.SpoilerGuardRepository
import org.jellyfin.androidtv.enhanced.seerr.SeerrImages
import org.jellyfin.androidtv.enhanced.seerr.SeerrMediaInfo
import org.jellyfin.androidtv.enhanced.seerr.SeerrPage
import org.jellyfin.androidtv.enhanced.seerr.SeerrRepository
import org.jellyfin.androidtv.enhanced.seerr.SeerrResult
import timber.log.Timber
import java.net.URLDecoder
import java.time.LocalDate

/**
 * Requests, watchlist, download queue/history and issues for the Requests page. All calls go through the plugin as
 * the signed-in user; the server decides what each user may see (own requests only, admin-only history…). The rows
 * are then filtered the way the web filters its Requests page: Hidden Content's "requests" surface (on every tab,
 * failing closed when the hidden list can't be read), and Spoiler Guard's artwork and episode-title protection.
 */
class RequestsRepository(
	private val enhancedApi: EnhancedApi,
	private val enhancedRepository: EnhancedRepository,
	private val seerrRepository: SeerrRepository,
	private val hiddenContentRepository: HiddenContentRepository,
	private val spoilerGuardRepository: SpoilerGuardRepository,
) {
	/**
	 * A request ready to show. [posterUrl] is the server's library poster for library titles while Spoiler Guard is on
	 * (null when it can't be resolved), the TMDB poster otherwise. [protectedTitle] means release dates and the year
	 * come from outside the plugin's filtering and must be withheld.
	 */
	data class RequestCard(val request: ArrRequest, val posterUrl: String?, val protectedTitle: Boolean)

	data class RequestsResult(val cards: List<RequestCard>, val totalPages: Int, val canApproveRequests: Boolean)

	/** An issue with the title it is about (looked up on Seerr, as the web does). */
	data class IssueCard(val issue: SeerrIssue, val title: String?, val year: String?, val posterUrl: String?, val mediaType: String?)

	data class IssuesResult(val cards: List<IssueCard>, val totalPages: Int)

	/** Queue rows plus the *arr instances that failed; failures never blank the rows that did load. */
	data class QueueResult(val entries: List<QueueEntry>, val errors: List<QueueError>)

	/**
	 * Hidden Content is on but the user's hidden list couldn't be read: the rows aren't shown at all, since showing
	 * them unfiltered could bring back titles the user hid.
	 */
	class HiddenContentUnavailableException(cause: Throwable) : Exception("Hidden content unavailable", cause)

	// Requests

	suspend fun requests(filter: RequestFilter, userOnly: Boolean, page: Int, pageSize: Int): RequestsResult {
		val response = EnhancedApi.json.decodeFromJsonElement<ArrRequestsPage>(
			enhancedApi.get(
				"arr/requests",
				mapOf(
					"take" to pageSize,
					"skip" to (page - 1) * pageSize,
					"filter" to filter.apiValue,
					"userOnly" to userOnly,
				),
			)
		)
		val hidden = hiddenFilter()
		val visible = response.requests.filterNot { hidden.isHidden(it.tmdbId, it.jellyfinMediaId) }

		val cards = withArtwork(visible)
		return RequestsResult(cards, response.totalPages, response.canApproveRequests)
	}

	/**
	 * Library titles go through the shared Spoiler Guard artwork path (the server's poster with its current image
	 * tag); everything else keeps the TMDB poster.
	 */
	private suspend fun withArtwork(requests: List<ArrRequest>): List<RequestCard> {
		val asResults = requests.map { request ->
			SeerrResult(
				id = request.tmdbId ?: 0,
				mediaType = request.type,
				title = request.title,
				mediaInfo = request.jellyfinMediaId?.let { SeerrMediaInfo(jellyfinMediaId = it) },
			)
		}
		return requests.zip(seerrRepository.refreshLibraryArtwork(asResults)) { request, result ->
			if (result.libraryArtwork) RequestCard(request, result.libraryPosterUrl, protectedTitle = true)
			else RequestCard(request, request.posterUrl, protectedTitle = false)
		}
	}

	/** Approve a pending request. Not idempotent: never retried automatically. */
	suspend fun approve(requestId: Int) {
		enhancedApi.post("arr/requests/$requestId/approve")
	}

	/** Decline a pending request. Not idempotent: never retried automatically. */
	suspend fun decline(requestId: Int) {
		enhancedApi.post("arr/requests/$requestId/decline")
	}

	// Watchlist

	suspend fun watchlist(page: Int): SeerrPage {
		val response = EnhancedApi.json.decodeFromJsonElement<SeerrPage>(enhancedApi.get("jellyseerr/watchlist", mapOf("page" to page)))
		val hidden = hiddenFilter()
		val visible = response.results.filterNot { result ->
			hidden.isHidden(result.id.takeIf { it > 0 }, result.mediaInfo?.jellyfinMediaId) ||
				hidden.isHidden(null, result.mediaInfo?.jellyfinMediaId4k)
		}
		return response.copy(results = seerrRepository.refreshLibraryArtwork(visible))
	}

	/** Run the server's watchlist sync now (admins only, and only when the server has it turned on). */
	suspend fun syncWatchlist(): WatchlistSyncResult =
		EnhancedApi.json.decodeFromJsonElement(enhancedApi.post("jellyseerr/sync-watchlist"))

	// Downloads

	suspend fun queue(): QueueResult {
		val response = EnhancedApi.json.decodeFromJsonElement<QueueResponse>(enhancedApi.get("arr/queue"))
		val hidden = hiddenFilter()
		val spoilers = spoilerPolicy()
		val visible = response.items.filterNot { hidden.isHidden(it.tmdbId, it.jellyfinMediaId) }
		val items = withLibraryPosters(
			items = visible.map(spoilers::apply),
			withheld = { spoilers.withholdsPoster(it.isSonarr, it.tmdbId) },
			result = { SeerrResult(id = it.tmdbId ?: 0, mediaType = if (it.isSonarr) "tv" else "movie", mediaInfo = it.jellyfinMediaId?.let { id -> SeerrMediaInfo(jellyfinMediaId = id) }) },
			withPoster = { item, url -> item.copy(posterUrl = url) },
		)
		return QueueResult(groupQueue(items), response.errors)
	}

	suspend fun history(page: Int, pageSize: Int): HistoryPage {
		val response = EnhancedApi.json.decodeFromJsonElement<HistoryPage>(
			enhancedApi.get("arr/history", mapOf("take" to pageSize, "skip" to (page - 1) * pageSize))
		)
		if (!response.visible) return response
		val hidden = hiddenFilter()
		val spoilers = spoilerPolicy()
		val visible = response.items.filterNot { hidden.isHidden(it.tmdbId, it.jellyfinMediaId) }
		return response.copy(
			items = withLibraryPosters(
				items = visible.map(spoilers::apply),
				withheld = { spoilers.withholdsPoster(it.isSonarr, it.tmdbId) },
				result = { SeerrResult(id = it.tmdbId ?: 0, mediaType = if (it.isSonarr) "tv" else "movie", mediaInfo = it.jellyfinMediaId?.let { id -> SeerrMediaInfo(jellyfinMediaId = id) }) },
				withPoster = { item, url -> item.copy(posterUrl = url) },
			)
		)
	}

	/**
	 * Rows whose *arr poster Spoiler Guard withheld get the server's own poster instead when they are library titles
	 * (the server applies the protection to it); the rest stay without a poster.
	 */
	private suspend fun <T> withLibraryPosters(
		items: List<T>,
		withheld: (T) -> Boolean,
		result: (T) -> SeerrResult,
		withPoster: (T, String?) -> T,
	): List<T> {
		val candidates = items.indices.filter { withheld(items[it]) && result(items[it]).mediaInfo != null }
		if (candidates.isEmpty()) return items
		val resolved = seerrRepository.refreshLibraryArtwork(candidates.map { result(items[it]) })
		val posters = candidates.zip(resolved) { index, artwork -> index to artwork.libraryPosterUrl.takeIf { artwork.libraryArtwork } }.toMap()
		return items.mapIndexed { index, item -> if (index in posters) withPoster(item, posters[index]) else item }
	}

	// Issues

	/**
	 * Issues with their titles. Seerr's issue only carries the media's ids, so each title is looked up once per
	 * [titleCache] (the screen's, so it never outlives the signed-in user): several issues about the same title share
	 * one lookup, and only a few run at a time. A failed lookup leaves the title out. Hidden titles are dropped by
	 * their ids before any lookup, so a failed lookup can't let them through.
	 */
	suspend fun issues(filter: IssueFilter, page: Int, pageSize: Int, titleCache: MutableMap<String, SeerrResult?>): IssuesResult {
		val response = EnhancedApi.json.decodeFromJsonElement<SeerrIssuePage>(
			enhancedApi.get(
				"jellyseerr/issue",
				mapOf("take" to pageSize, "skip" to (page - 1) * pageSize, "filter" to filter.apiValue, "sort" to "added"),
			)
		)
		val hidden = hiddenFilter()
		val issues = response.results.filterNot { hidden.isHidden(it.media?.tmdbId, it.media?.jellyfinMediaId) }
		val keys = issues.mapNotNull { issueTitleKey(it) }.distinct()
		val lookups = Semaphore(MAX_TITLE_LOOKUPS)
		val titlesByKey = coroutineScope {
			keys.map { key -> async { key to lookups.withPermit { issueTitle(key, titleCache) } } }.awaitAll().toMap()
		}
		val titles = issues.map { issue -> issueTitleKey(issue)?.let { titlesByKey[it] } }
		val withArtwork = seerrRepository.refreshLibraryArtwork(titles.map { it ?: SeerrResult(id = 0) })
		val cards = issues.zip(titles.zip(withArtwork)) { issue, (title, result) ->
			if (title == null) IssueCard(issue, null, null, null, issue.media?.mediaType)
			else IssueCard(
				issue = issue,
				title = result.displayTitle.ifEmpty { null },
				year = result.year.takeIf { !result.libraryArtwork },
				posterUrl = if (result.libraryArtwork) result.libraryPosterUrl else SeerrImages.poster(result.posterPath),
				mediaType = issue.media?.mediaType,
			)
		}
		return IssuesResult(cards, response.pageInfo.pages)
	}

	private data class IssueTitleKey(val mediaType: String, val tmdbId: Int) {
		override fun toString() = "$mediaType:$tmdbId"
	}

	private fun issueTitleKey(issue: SeerrIssue): IssueTitleKey? {
		val mediaType = issue.media?.mediaType ?: return null
		val tmdbId = issue.media.tmdbId ?: return null
		return IssueTitleKey(mediaType, tmdbId)
	}

	private suspend fun issueTitle(key: IssueTitleKey, cache: MutableMap<String, SeerrResult?>): SeerrResult? {
		val cacheKey = key.toString()
		synchronized(cache) { if (cache.containsKey(cacheKey)) return cache[cacheKey] }
		val result = try {
			val details = seerrRepository.details(key.mediaType, key.tmdbId)
			SeerrResult(
				id = key.tmdbId,
				mediaType = key.mediaType,
				title = details.title,
				name = details.name,
				posterPath = details.posterPath,
				releaseDate = details.releaseDate,
				firstAirDate = details.firstAirDate,
				mediaInfo = details.mediaInfo,
			)
		} catch (err: CancellationException) {
			throw err
		} catch (err: Exception) {
			// Not cached, so the next refresh tries again
			Timber.w(err, "Issue title lookup failed for %s", cacheKey)
			return null
		}
		synchronized(cache) { cache[cacheKey] = result }
		return result
	}

	// Filtering

	/**
	 * Hidden Content's "requests" surface (`FilterRequests`, on by default). A failed lookup fails closed: it throws
	 * [HiddenContentUnavailableException], so the tab shows an error with Retry instead of unfiltered rows.
	 */
	private suspend fun hiddenFilter(): HiddenRequestFilter {
		// An unknown configuration counts as on: the list is read, and its failure fails closed like any other
		val state = enhancedRepository.ensureLoaded()
		if (!state.hiddenContent && !state.configUnavailable) return HiddenRequestFilter.NONE
		val snapshot = try {
			hiddenContentRepository.get()
		} catch (err: CancellationException) {
			throw err
		} catch (err: Exception) {
			Timber.w(err, "Failed to load hidden content")
			throw HiddenContentUnavailableException(err)
		}
		val settings = snapshot.document["Settings"] as? JsonObject
		val filterRequests = (settings?.get("FilterRequests") as? JsonPrimitive)?.booleanOrNull ?: true
		if (!snapshot.settings.enabled || !filterRequests) return HiddenRequestFilter.NONE
		return HiddenRequestFilter(snapshot.hiddenTmdbIds, snapshot.hiddenItemIds)
	}

	private suspend fun spoilerPolicy(): ArrSpoilerPolicy {
		// Unknown configuration fails closed: the guard lists still apply, or everything is withheld if they can't be read
		if (!enhancedRepository.ensureLoaded().spoilerGuardMayApply) return ArrSpoilerPolicy.NONE
		val state = try {
			spoilerGuardRepository.getState()
		} catch (err: CancellationException) {
			throw err
		} catch (err: Exception) {
			Timber.w(err, "Failed to load Spoiler Guard state; withholding episode titles and movie posters")
			null
		}
		return ArrSpoilerPolicy.of(enabled = true, state = state)
	}

	/** An authenticated URL for a requester avatar, or null when it isn't a plugin route. */
	fun avatarUrl(raw: String?): String? {
		val (path, query) = splitPluginPath(raw) ?: return null
		return enhancedApi.url(path, query)
	}

	companion object {
		// Seerr title lookups for one page of issues running at the same time
		private const val MAX_TITLE_LOOKUPS = 4

		/** Today in the device's zone, for release labels. */
		fun today(): LocalDate = LocalDate.now()

		/**
		 * Split a plugin route with a query (`/JellyfinEnhanced/proxy/avatar?path=%2Fa`) into its path and decoded query.
		 * Anything that isn't a path under the plugin (an absolute URL in particular) is rejected, so the user's
		 * credentials are never attached to a foreign host.
		 */
		fun splitPluginPath(raw: String?): Pair<String, Map<String, String>>? {
			if (raw.isNullOrBlank() || !raw.startsWith("${EnhancedApi.BASE}/")) return null
			val path = raw.substringBefore('?')
			if (".." in path) return null
			val query = raw.substringAfter('?', "")
				.split('&')
				.filter { it.isNotEmpty() }
				.associate { part ->
					val key = URLDecoder.decode(part.substringBefore('='), Charsets.UTF_8)
					val value = URLDecoder.decode(part.substringAfter('=', ""), Charsets.UTF_8)
					key to value
				}
			return path to query
		}

		/** The server's explanation for a failed call, when it sent one (`message`, or a textual `error`). */
		fun serverMessage(err: Throwable): String? {
			val api = err as? EnhancedApi.EnhancedApiException ?: return null
			api.serverMessage?.takeIf { it.isNotBlank() }?.let { return it }
			val error = (api.body as? JsonObject)?.get("error") as? JsonPrimitive ?: return null
			return error.takeIf { it.isString }?.contentOrNull?.takeIf { it.isNotBlank() }
		}
	}
}

