package org.jellyfin.androidtv.enhanced.playback

import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import kotlin.math.roundToInt

/**
 * What the player overlays show about the playing item, taken only from the item DTO the player already holds. The
 * server has filtered that DTO for Spoiler Guard, so anything it stripped (overview, ratings, episode title) is simply
 * absent here and never fetched from elsewhere.
 */
object PlaybackItemInfo {
	private const val TICKS_PER_MINUTE = 600_000_000L
	private const val MAX_BADGE_GENRES = 3
	private const val CRITIC_FRESH = 60

	/** `Series · S1E2 · Episode` for episodes; null for anything else. */
	fun episodeLine(item: BaseItemDto): String? {
		if (item.type != BaseItemKind.EPISODE) return null
		val season = item.parentIndexNumber
		val episode = item.indexNumber
		return listOfNotNull(
			item.seriesName?.takeIf { it.isNotBlank() },
			if (season != null && episode != null) "S${season}E$episode" else null,
			item.name?.takeIf { it.isNotBlank() },
		).joinToString(" · ").ifEmpty { null }
	}

	/** The pause screen's title: the show for episodes, the item otherwise. */
	fun title(item: BaseItemDto): String = when (item.type) {
		BaseItemKind.EPISODE -> item.seriesName ?: item.name
		else -> item.name
	}.orEmpty()

	/** `1h 52m` / `48m`, like pausescreen.js; null without a runtime. */
	fun runtime(runTimeTicks: Long?): String? {
		if (runTimeTicks == null || runTimeTicks <= 0) return null
		val totalMinutes = runTimeTicks / TICKS_PER_MINUTE
		val hours = totalMinutes / 60
		val minutes = totalMinutes % 60
		return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
	}

	/** `m:ss`, or `h:mm:ss` from an hour on (the web's `formatClock`). */
	fun clock(ms: Long): String {
		val total = (ms.coerceAtLeast(0) / 1000)
		val hours = total / 3600
		val minutes = (total % 3600) / 60
		val seconds = total % 60
		return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
	}

	/** Progress 0..1, 0 without a duration. */
	fun progress(positionMs: Long, durationMs: Long): Float =
		if (durationMs <= 0) 0f else (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)

	/** The official rating to announce, or null (no badge) when the DTO has none. */
	fun officialRating(item: BaseItemDto): String? = item.officialRating?.trim()?.takeIf { it.isNotEmpty() }

	/** Up to three genres for the badge's second line. */
	fun badgeGenres(item: BaseItemDto): List<String> =
		item.genres.orEmpty().filter { it.isNotBlank() }.take(MAX_BADGE_GENRES)

	/** The OSD's community rating as the web shows it (`8.2`), or null. */
	fun communityRating(item: BaseItemDto): String? = item.communityRating?.let { "%.1f".format(it) }

	/** The OSD's critic score clamped to 0–100 (`osd-rating.js`), or null. */
	fun criticPercent(item: BaseItemDto): Int? = item.criticRating?.takeIf { it.isFinite() }?.roundToInt()?.coerceIn(0, 100)

	/** Rotten below 60%, as on the web and in the upstream info row. */
	fun criticFresh(percent: Int): Boolean = percent >= CRITIC_FRESH
}
