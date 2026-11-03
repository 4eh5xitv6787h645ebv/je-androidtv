package org.jellyfin.androidtv.enhanced.requests

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jellyfin.androidtv.enhanced.data.SpoilerGuardState
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.ln
import kotlin.math.pow

/**
 * Seerr's request status (`MediaRequestStatus`). Distinct from the media status
 * ([org.jellyfin.androidtv.enhanced.seerr.SeerrMediaStatus]): a show can be "Partially Available" while a request
 * for its next season is still pending.
 */
object SeerrRequestStatus {
	const val PENDING = 1
	const val APPROVED = 2
	const val DECLINED = 3
	const val FAILED = 4
	const val COMPLETED = 5
}

/** Status filters of `GET arr/requests`, as the web's tabs. [apiValue] null means no filter. */
enum class RequestFilter(val apiValue: String?) {
	ALL(null),
	PENDING("pending"),
	APPROVED("approved"),
	PROCESSING("processing"),
	COMING_SOON("comingsoon"),
	AVAILABLE("available"),
}

/**
 * One entry of `GET arr/requests`: a Seerr request enriched by the plugin with TMDB title/poster and release dates.
 * [requestId] is the Seerr request id (for approve/decline), [tmdbId] the title's TMDB id (for details) and
 * [jellyfinMediaId] the library copy, when there is one. [mediaStatus] is the plugin's text label (`Pending`,
 * `Approved`, `Processing`, `Partially Available`, `Available`, `Declined`…), not the numeric media status.
 */
@Serializable
data class ArrRequest(
	@SerialName("id") val requestId: Int? = null,
	val type: String? = null,
	val title: String? = null,
	val year: Int? = null,
	/** An absolute TMDB image URL. */
	val posterUrl: String? = null,
	val tmdbId: Int? = null,
	val tvdbId: Int? = null,
	val mediaStatus: String? = null,
	val requestStatus: Int? = null,
	val requestedBy: String? = null,
	/** A server path (`/JellyfinEnhanced/proxy/avatar?path=…`) that needs the user's credentials. */
	val requestedByAvatar: String? = null,
	val createdAt: String? = null,
	val jellyfinMediaId: String? = null,
	val digitalReleaseDate: String? = null,
	val theatricalReleaseDate: String? = null,
	val initialAirDate: String? = null,
	val nextAirDate: String? = null,
	val isComingSoon: Boolean = false,
	val isOrphaned: Boolean = false,
) {
	val isTv get() = type == "tv"
	val isPendingApproval get() = requestStatus == SeerrRequestStatus.PENDING && requestId != null
}

/** `GET arr/requests`. */
@Serializable
data class ArrRequestsPage(
	val requests: List<ArrRequest> = emptyList(),
	val totalPages: Int = 0,
	val totalResults: Int = 0,
	val canApproveRequests: Boolean = false,
)

/** The chip shown on a request card, following the web's `resolveRequestStatus`. */
enum class RequestChip {
	ORPHANED, COMING_SOON, AVAILABLE, PARTIALLY_AVAILABLE, PROCESSING, REQUESTED, PENDING, DECLINED, BLOCKLISTED,
	DELETED, FAILED, COMPLETED, UNKNOWN;

	companion object {
		fun of(request: ArrRequest, today: LocalDate): RequestChip {
			if (request.isOrphaned) return ORPHANED
			val status = request.mediaStatus.orEmpty().lowercase()
			if (releaseLabel(request, today) != null) {
				val comingSoonStatuses = if (request.isTv) setOf("approved", "processing", "partially available") else setOf("approved", "processing")
				if (status in comingSoonStatuses) return COMING_SOON
			}
			return when (status) {
				"available" -> AVAILABLE
				"partially available" -> PARTIALLY_AVAILABLE
				"processing" -> PROCESSING
				// Approved but not downloading yet: Seerr calls that "Requested"
				"approved" -> REQUESTED
				"pending" -> PENDING
				"declined" -> DECLINED
				"blocklisted" -> BLOCKLISTED
				"deleted" -> DELETED
				"failed" -> FAILED
				"completed" -> COMPLETED
				else -> UNKNOWN
			}
		}
	}
}

/** Where an upcoming date comes from: the next episode, the digital release or the cinema release. */
enum class ReleaseKind { NEXT_EPISODE, DIGITAL, THEATRICAL }

/** An upcoming release [daysAway] days from today (0 = today). */
data class ReleaseLabel(val kind: ReleaseKind, val date: LocalDate, val daysAway: Long)

/** Parse the plugin's dates, either `yyyy-MM-dd` or a full ISO timestamp (its date part is used). */
fun parseDay(value: String?): LocalDate? = value
	?.takeIf { it.length >= 10 }
	?.let { runCatching { LocalDate.parse(it.substring(0, 10)) }.getOrNull() }

/**
 * The upcoming release date shown on a request card, like the web's `getReleaseDateLabel`: shows use the next air
 * date; movies the digital release when known (and only that), otherwise the cinema release. Past dates show
 * nothing.
 */
fun releaseLabel(request: ArrRequest, today: LocalDate): ReleaseLabel? {
	val (kind, raw) = when {
		request.isTv -> ReleaseKind.NEXT_EPISODE to request.nextAirDate
		request.digitalReleaseDate != null -> ReleaseKind.DIGITAL to request.digitalReleaseDate
		else -> ReleaseKind.THEATRICAL to request.theatricalReleaseDate
	}
	val date = parseDay(raw) ?: return null
	val days = ChronoUnit.DAYS.between(today, date)
	return if (days < 0) null else ReleaseLabel(kind, date, days)
}

/** One download of `GET arr/queue`. [queueId] is the *arr queue record id; [progress] is a percentage. */
@Serializable
data class QueueItem(
	@SerialName("id") val queueId: String? = null,
	val source: String? = null,
	val instanceName: String? = null,
	val title: String? = null,
	val subtitle: String? = null,
	val seasonNumber: Int? = null,
	val episodeNumber: Int? = null,
	/** Lower-case *arr state: `downloading`, `queued`, `paused`, `importing`, `completed`, `warning`, `failed`, `delay`… */
	val status: String? = null,
	val progress: Double = 0.0,
	val totalSize: Long = 0,
	val sizeRemaining: Long = 0,
	/** `HH:mm:ss` or `d.HH:mm:ss`. */
	val timeRemaining: String? = null,
	val posterUrl: String? = null,
	val tmdbId: Int? = null,
	val jellyfinMediaId: String? = null,
) {
	val isSonarr get() = source.equals("Sonarr", ignoreCase = true)
}

/** A *arr instance whose queue couldn't be read. The other instances' items are still valid. */
@Serializable
data class QueueError(
	val instanceName: String? = null,
	val source: String? = null,
	val reason: String? = null,
)

/** `GET arr/queue`. */
@Serializable
data class QueueResponse(
	val items: List<QueueItem> = emptyList(),
	val errors: List<QueueError> = emptyList(),
)

/** A queue row: one download, or several episodes of a season that download together (a season pack). */
sealed interface QueueEntry {
	val item: QueueItem
	val totalSize: Long
	val sizeRemaining: Long

	data class Single(override val item: QueueItem) : QueueEntry {
		override val totalSize get() = item.totalSize
		override val sizeRemaining get() = item.sizeRemaining
	}

	data class SeasonPack(
		val episodes: List<QueueItem>,
		override val totalSize: Long,
		override val sizeRemaining: Long,
	) : QueueEntry {
		override val item get() = episodes.first()
		val firstEpisode get() = episodes.mapNotNull { it.episodeNumber }.minOrNull()
		val lastEpisode get() = episodes.mapNotNull { it.episodeNumber }.maxOrNull()
	}
}

/**
 * Collapse season packs like the web's `groupDownloads`: three or more Sonarr episodes of the same show, season,
 * instance and progress are one download. A pack whose episodes all report the same size is one file, so its size
 * counts once; otherwise the episode sizes add up. Rows keep the order of their first item.
 */
fun groupQueue(items: List<QueueItem>): List<QueueEntry> {
	val groups = LinkedHashMap<Any, MutableList<QueueItem>>()
	items.forEachIndexed { index, item ->
		val key: Any = if (item.isSonarr && item.seasonNumber != null) {
			listOf(item.title, item.seasonNumber, item.progress, item.instanceName.orEmpty())
		} else index
		groups.getOrPut(key) { mutableListOf() } += item
	}
	return groups.values.flatMap { group ->
		if (group.size < 3) return@flatMap group.map { QueueEntry.Single(it) }
		val first = group.first()
		val oneFile = group.all { it.totalSize == first.totalSize && it.sizeRemaining == first.sizeRemaining }
		listOf(
			QueueEntry.SeasonPack(
				episodes = group.sortedBy { it.episodeNumber ?: 0 },
				totalSize = if (oneFile) first.totalSize else group.sumOf { it.totalSize },
				sizeRemaining = if (oneFile) first.sizeRemaining else group.sumOf { it.sizeRemaining },
			)
		)
	}
}

/** One import event of `GET arr/history`. [eventType] is `imported`, `partial` or a failure. */
@Serializable
data class HistoryItem(
	val source: String? = null,
	val instanceName: String? = null,
	val title: String? = null,
	val subtitle: String? = null,
	val eventType: String? = null,
	val date: String? = null,
	val posterUrl: String? = null,
	val tmdbId: Int? = null,
	val jellyfinMediaId: String? = null,
	val episodeCount: Int? = null,
	val importedCount: Int? = null,
	val failedCount: Int? = null,
) {
	val isSonarr get() = source.equals("Sonarr", ignoreCase = true)
	val outcome
		get() = when (eventType) {
			"imported" -> HistoryOutcome.IMPORTED
			"partial" -> HistoryOutcome.PARTIAL
			else -> HistoryOutcome.FAILED
		}
}

enum class HistoryOutcome { IMPORTED, PARTIAL, FAILED }

/** `GET arr/history`. [visible] false means the server hides history from this user, not that it failed. */
@Serializable
data class HistoryPage(
	val items: List<HistoryItem> = emptyList(),
	val totalPages: Int = 0,
	val visible: Boolean = true,
)

/** `POST jellyseerr/sync-watchlist`. */
@Serializable
data class WatchlistSyncResult(
	val success: Boolean = false,
	val itemsProcessed: Int = 0,
	val itemsAdded: Int = 0,
	val errors: List<String>? = null,
)

/** Seerr issue types. */
object SeerrIssueType {
	const val VIDEO = 1
	const val AUDIO = 2
	const val SUBTITLES = 3
	const val OTHER = 4
}

/** Seerr issue status. */
object SeerrIssueStatus {
	const val OPEN = 1
	const val RESOLVED = 2
}

/** Issue filters of `GET jellyseerr/issue`. */
enum class IssueFilter(val apiValue: String) { ALL("all"), OPEN("open"), RESOLVED("resolved") }

/** The media an issue is about. [seerrMediaId] is Seerr's internal media id, not the TMDB id. */
@Serializable
data class SeerrIssueMedia(
	@SerialName("id") val seerrMediaId: Int? = null,
	val mediaType: String? = null,
	val tmdbId: Int? = null,
	val jellyfinMediaId: String? = null,
)

@Serializable
data class SeerrIssueUser(
	val displayName: String? = null,
	val jellyfinUsername: String? = null,
	val username: String? = null,
) {
	val name get() = jellyfinUsername ?: displayName ?: username
}

@Serializable
data class SeerrIssueComment(val message: String? = null)

/** One issue of `GET jellyseerr/issue` (raw Seerr shape). [issueId] is Seerr's issue id. */
@Serializable
data class SeerrIssue(
	@SerialName("id") val issueId: Int,
	val issueType: Int? = null,
	val status: Int? = null,
	val problemSeason: Int? = null,
	val problemEpisode: Int? = null,
	val createdAt: String? = null,
	val media: SeerrIssueMedia? = null,
	val createdBy: SeerrIssueUser? = null,
	val comments: List<SeerrIssueComment> = emptyList(),
) {
	val isResolved get() = status == SeerrIssueStatus.RESOLVED
	/** The reporter's description: the first comment. */
	val message get() = comments.firstOrNull()?.message?.takeIf { it.isNotBlank() }
	/** `S2 E5`, `S2` or null when the issue is about the whole title. */
	val episodeLabel
		get() = when {
			(problemSeason ?: 0) <= 0 -> null
			(problemEpisode ?: 0) <= 0 -> "S$problemSeason"
			else -> "S$problemSeason E$problemEpisode"
		}
}

@Serializable
data class SeerrPageInfo(val pages: Int = 0, val pageSize: Int = 0, val results: Int = 0, val page: Int = 1)

/** `GET jellyseerr/issue`. */
@Serializable
data class SeerrIssuePage(
	val pageInfo: SeerrPageInfo = SeerrPageInfo(),
	val results: List<SeerrIssue> = emptyList(),
)

/**
 * What to withhold from *arr queue and history rows while Spoiler Guard is on. Those rows come from Sonarr/Radarr,
 * outside the plugin's filtering, and only carry TMDB ids, so protected titles can't be matched exactly: as soon as
 * the user protects any show, episode titles are dropped from every Sonarr row, and as soon as they protect any
 * movie or collection, Radarr posters are withheld. When the protection state couldn't be loaded, both are.
 */
data class ArrSpoilerPolicy(
	val stripEpisodeTitles: Boolean,
	val withholdMoviePosters: Boolean,
	private val pendingTmdb: Set<String> = emptySet(),
) {
	/** Whether a title opted in before it reached the library (`movie:603`, `tv:1399`). */
	fun isPending(mediaType: String, tmdbId: Int?) = tmdbId != null && "$mediaType:$tmdbId" in pendingTmdb

	companion object {
		val NONE = ArrSpoilerPolicy(stripEpisodeTitles = false, withholdMoviePosters = false)

		/** [state] null with [enabled] means the state failed to load. */
		fun of(enabled: Boolean, state: SpoilerGuardState?): ArrSpoilerPolicy = when {
			!enabled -> NONE
			state == null -> ArrSpoilerPolicy(stripEpisodeTitles = true, withholdMoviePosters = true)
			else -> ArrSpoilerPolicy(
				stripEpisodeTitles = state.series.isNotEmpty(),
				withholdMoviePosters = state.movies.isNotEmpty() || state.collections.isNotEmpty(),
				pendingTmdb = state.pendingTmdb.keys,
			)
		}

		private val EPISODE_TITLE = Regex("^(S\\d+E\\d+)\\s*-\\s*.*$")

		/** `S02E01 - The Proposal` → `S02E01`. Other subtitles (a year, `S01 · E01-E08`) are kept. */
		fun withoutEpisodeTitle(subtitle: String?): String? =
			subtitle?.let { EPISODE_TITLE.matchEntire(it)?.groupValues?.get(1) ?: it }
	}

	fun apply(item: QueueItem): QueueItem {
		val movie = !item.isSonarr
		val strip = !movie && (stripEpisodeTitles || isPending("tv", item.tmdbId))
		val withhold = movie && (withholdMoviePosters || isPending("movie", item.tmdbId))
		return item.copy(
			subtitle = if (strip) withoutEpisodeTitle(item.subtitle) else item.subtitle,
			posterUrl = if (withhold) null else item.posterUrl,
		)
	}

	fun apply(item: HistoryItem): HistoryItem {
		val movie = !item.isSonarr
		val strip = !movie && (stripEpisodeTitles || isPending("tv", item.tmdbId))
		val withhold = movie && (withholdMoviePosters || isPending("movie", item.tmdbId))
		return item.copy(
			subtitle = if (strip) withoutEpisodeTitle(item.subtitle) else item.subtitle,
			posterUrl = if (withhold) null else item.posterUrl,
		)
	}
}

/**
 * Hidden Content's "requests" surface, like the web's `filterRequestItems`: drops rows whose TMDB id or library id is
 * hidden everywhere. Ids are compared dash-less and lower case.
 */
class HiddenRequestFilter(private val hiddenTmdbIds: Set<String>, hiddenItemIds: Set<String>) {
	private val hiddenItemIds = hiddenItemIds.mapTo(HashSet()) { it.normalizedId() }

	fun isHidden(tmdbId: Int?, jellyfinMediaId: String?): Boolean =
		(tmdbId != null && tmdbId.toString() in hiddenTmdbIds) ||
			(jellyfinMediaId != null && jellyfinMediaId.normalizedId() in hiddenItemIds)

	companion object {
		val NONE = HiddenRequestFilter(emptySet(), emptySet())
		private fun String.normalizedId() = replace("-", "").lowercase()
	}
}

/** Bytes as `1.5 GB`, base 1024 like the web. */
fun formatBytes(bytes: Long): String {
	if (bytes <= 0) return "0 B"
	val units = listOf("B", "KB", "MB", "GB", "TB")
	val exponent = (ln(bytes.toDouble()) / ln(1024.0)).toInt().coerceIn(0, units.lastIndex)
	val value = bytes / 1024.0.pow(exponent)
	val text = "%.1f".format(java.util.Locale.ROOT, value).removeSuffix(".0")
	return "$text ${units[exponent]}"
}

/** Downloaded / total, clamped like the web's `formatDownloadStats`. Null without a size. */
fun formatDownloadStats(totalSize: Long, sizeRemaining: Long): String? {
	if (totalSize <= 0) return null
	val remaining = sizeRemaining.coerceIn(0, totalSize)
	return "${formatBytes(totalSize - remaining)} / ${formatBytes(totalSize)}"
}

/** `HH:mm:ss` or `d.HH:mm:ss` → `2d 3h 4m`, `3h 4m 5s`, `4m 5s` or `5s`. Unknown formats pass through; zero is null. */
fun formatTimeRemaining(value: String?): String? {
	if (value.isNullOrBlank()) return null
	val match = Regex("^(?:(\\d+)\\.)?(\\d+):(\\d+):(\\d+)(?:\\.\\d+)?$").matchEntire(value) ?: return value
	val (d, h, m, s) = match.destructured
	val days = d.toLongOrNull() ?: 0
	val hours = h.toLong()
	val minutes = m.toLong()
	val seconds = s.toLong()
	return when {
		days > 0 -> "${days}d ${hours}h ${minutes}m"
		hours > 0 -> "${hours}h ${minutes}m ${seconds}s"
		minutes > 0 -> "${minutes}m ${seconds}s"
		seconds > 0 -> "${seconds}s"
		else -> null
	}
}
