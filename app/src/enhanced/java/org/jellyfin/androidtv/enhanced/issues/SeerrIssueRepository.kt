package org.jellyfin.androidtv.enhanced.issues

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import org.jellyfin.androidtv.enhanced.api.EnhancedApi

/**
 * Seerr issues through the Jellyfin Enhanced proxy, mirroring `js/jellyseerr/api.js` (`reportIssue`,
 * `fetchIssuesForMedia`). Issues are attached to Seerr's internal media id (`mediaInfo.id`), not the TMDB id.
 */
class SeerrIssueRepository(
	private val enhancedApi: EnhancedApi,
) {
	companion object {
		private const val PAGE_SIZE = 100

		/** Enough for any one server's issue list; beyond that the count is shown as a lower bound. */
		private const val MAX_PAGES = 5

		/** Issues of [mediaId] among [issues], newest first. */
		fun forMedia(issues: List<SeerrIssue>, mediaId: Int): List<SeerrIssue> = issues
			.filter { it.media?.id == mediaId }
			.distinctBy { it.id }
			.sortedByDescending { it.createdAt.orEmpty() }
	}

	/** Issues of one title. [complete] is false when there were more issues on the server than were scanned. */
	class MediaIssues(val issues: List<SeerrIssue>, val complete: Boolean) {
		val open get() = issues.count { it.isOpen }
	}

	/**
	 * Seerr's internal media id for a title, or null when Seerr doesn't know the title (it has no `mediaInfo`, so
	 * there's nothing to report against). Throws when the lookup fails.
	 */
	suspend fun resolveMediaId(mediaType: String, tmdbId: Int, api: EnhancedApi = enhancedApi): Int? {
		val details = api.get("jellyseerr/$mediaType/$tmdbId") as? JsonObject ?: return null
		val mediaInfo = details["mediaInfo"] as? JsonObject ?: return null
		return (mediaInfo["id"] as? JsonPrimitive)?.intOrNull?.takeIf { it > 0 }
	}

	/**
	 * All issues of the title with Seerr media id [mediaId]. Seerr's issue list can't be filtered by media (the proxy
	 * forwards `mediaId`, but Seerr rejects it with 400), so the list is paged and filtered here like the web does.
	 * Throws when the first page fails (e.g. `no_issue_view_permission`).
	 */
	suspend fun issuesFor(mediaId: Int): MediaIssues {
		val all = mutableListOf<SeerrIssue>()
		var complete = false
		for (page in 0 until MAX_PAGES) {
			val result = EnhancedApi.json.decodeFromJsonElement<SeerrIssuePage>(
				enhancedApi.get(
					"jellyseerr/issue",
					mapOf("take" to PAGE_SIZE, "skip" to page * PAGE_SIZE, "filter" to "all", "sort" to "added"),
				)
			)
			all += result.results
			if (result.results.size < PAGE_SIZE || all.size >= result.pageInfo.results) {
				complete = true
				break
			}
		}
		return MediaIssues(forMedia(all, mediaId), complete)
	}

	/** A client bound to the current user, for a report's media id lookup and submission together. */
	fun pinnedApi(): EnhancedApi = enhancedApi.pinned()

	/** Report a problem on the title with Seerr media id [mediaId]. Never retried: on failure the caller reports the error. */
	suspend fun report(api: EnhancedApi, mediaId: Int, type: SeerrIssueType, scope: SeerrIssueScope, message: String) {
		api.post("jellyseerr/issue", body(mediaId, type, scope, message))
	}

	internal fun body(mediaId: Int, type: SeerrIssueType, scope: SeerrIssueScope, message: String) = buildJsonObject {
		put("mediaId", mediaId)
		put("issueType", type.value)
		put("problemSeason", scope.season.coerceAtLeast(0))
		// An episode without a season means nothing to Seerr
		put("problemEpisode", if (scope.season > 0) scope.episode.coerceAtLeast(0) else 0)
		put("message", message.trim())
	}
}
