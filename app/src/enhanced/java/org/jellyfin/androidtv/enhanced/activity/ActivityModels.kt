package org.jellyfin.androidtv.enhanced.activity

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jellyfin.androidtv.enhanced.data.SpoilerGuardState

/** `GET /JellyfinEnhanced/activity`: a camelCase wrapper around PascalCase entries. */
@Serializable
data class ActivityFeed(
	val items: List<ActivityEntry> = emptyList(),
)

@Serializable
data class ActivityEntry(
	/** `Watched`, `Favorited` or `Reviewed`. */
	@SerialName("ActivityType") val activityType: String = "",
	@SerialName("UserId") val userId: String = "",
	@SerialName("UserName") val userName: String = "",
	@SerialName("UserHasPrimaryImage") val userHasPrimaryImage: Boolean = false,
	/** Unix time in **milliseconds**. */
	@SerialName("Timestamp") val timestampMs: Long = 0,
	@SerialName("Item") val item: ActivityItem? = null,
	/** Review stars (0–5, halves allowed). */
	@SerialName("Rating") val rating: Double? = null,
	/** Review text. */
	@SerialName("Content") val content: String? = null,
	@SerialName("Completed") val completed: Boolean = false,
	/** Highest watched position as a **fraction** 0–1. */
	@SerialName("Progress") val progress: Double = 0.0,
) {
	val type get() = ActivityType.of(activityType)
}

/** The item of an activity entry. Episode numbers are `SeasonNumber`/`EpisodeNumber` here (not `…IndexNumber`). */
@Serializable
data class ActivityItem(
	@SerialName("Id") val id: String = "",
	@SerialName("Name") val name: String = "",
	@SerialName("Type") val type: String = "",
	@SerialName("ProductionYear") val productionYear: Int? = null,
	@SerialName("SeriesName") val seriesName: String? = null,
	@SerialName("SeriesId") val seriesId: String? = null,
	@SerialName("SeasonNumber") val seasonNumber: Int? = null,
	@SerialName("EpisodeNumber") val episodeNumber: Int? = null,
	@SerialName("HasPrimaryImage") val hasPrimaryImage: Boolean = false,
	@SerialName("SeriesHasPrimaryImage") val seriesHasPrimaryImage: Boolean = false,
)

enum class ActivityType {
	WATCHED, FAVORITED, REVIEWED, OTHER;

	companion object {
		fun of(value: String) = entries.firstOrNull { it != OTHER && it.name.equals(value, ignoreCase = true) } ?: OTHER
	}
}

/** The activity options of `public-config` that [org.jellyfin.androidtv.enhanced.data.EnhancedPublicConfig] doesn't carry. */
@Serializable
data class ActivityConfig(
	@SerialName("ActivityFeedShowWatched") val showWatched: Boolean = true,
	@SerialName("ActivityFeedShowFavorited") val showFavorited: Boolean = true,
	@SerialName("ActivityFeedShowReviewed") val showReviewed: Boolean = true,
	@SerialName("ActivityFeedShowActiveStreams") val showActiveStreams: Boolean = true,
) {
	/** The filter chips worth offering: the categories the server includes. */
	val categories get() = buildList {
		if (showWatched) add(ActivityType.WATCHED)
		if (showFavorited) add(ActivityType.FAVORITED)
		if (showReviewed) add(ActivityType.REVIEWED)
	}
}

/** One entry of `GET active-streams/sessions` (PascalCase). */
@Serializable
data class ActiveSession(
	@SerialName("UserId") val userId: String = "",
	@SerialName("UserName") val userName: String = "",
	@SerialName("UserHasPrimaryImage") val userHasPrimaryImage: Boolean = false,
	@SerialName("Client") val client: String? = null,
	@SerialName("DeviceName") val deviceName: String? = null,
	@SerialName("NowPlayingItem") val nowPlaying: NowPlayingItem? = null,
	@SerialName("PlayState") val playState: SessionPlayState? = null,
)

@Serializable
data class NowPlayingItem(
	@SerialName("Id") val id: String = "",
	@SerialName("Type") val type: String = "",
	@SerialName("Name") val name: String = "",
	@SerialName("SeriesName") val seriesName: String? = null,
	@SerialName("SeriesId") val seriesId: String? = null,
	@SerialName("ParentIndexNumber") val parentIndexNumber: Int? = null,
	@SerialName("IndexNumber") val indexNumber: Int? = null,
	@SerialName("RunTimeTicks") val runTimeTicks: Long? = null,
)

@Serializable
data class SessionPlayState(
	@SerialName("IsPaused") val isPaused: Boolean = false,
	@SerialName("PositionTicks") val positionTicks: Long? = null,
	/** `DirectPlay`, `DirectStream` or `Transcode`. */
	@SerialName("PlayMethod") val playMethod: String? = null,
)

object ActivityLogic {
	/**
	 * Entries of the selected categories (all of them when [selected] is empty), newest first. Unknown future
	 * categories are only shown unfiltered.
	 */
	fun filter(entries: List<ActivityEntry>, selected: Set<ActivityType>): List<ActivityEntry> = entries
		.filter { selected.isEmpty() || it.type in selected }
		.sortedByDescending { it.timestampMs }

	/**
	 * Whether the entry's item belongs to a title the user protects with Spoiler Guard. Activity rows carry raw
	 * library names and review text, so fail closed when the guard list couldn't be read ([state] null).
	 */
	fun isGuarded(itemId: String?, seriesId: String?, spoilerGuardEnabled: Boolean, state: SpoilerGuardState?): Boolean {
		if (!spoilerGuardEnabled) return false
		if (state == null) return true
		val keys = listOfNotNull(itemId, seriesId).map { normalise(it) }
		return listOf(state.series.keys, state.movies.keys).any { guarded -> guarded.any { normalise(it) in keys } }
	}

	private fun normalise(id: String) = id.replace("-", "").lowercase()

	/** `Series · S1:E3 · Name`; the episode name is dropped when [guarded]. */
	fun displayName(name: String, seriesName: String?, season: Int?, episode: Int?, guarded: Boolean): String {
		if (seriesName.isNullOrBlank()) return name
		val code = if (season != null && episode != null) "S$season:E$episode" else null
		return listOfNotNull(seriesName, code, name.takeIf { !guarded && it.isNotBlank() }).joinToString(" · ")
	}

	/** Compact age like the web feed: `null` for under a minute ("just now"), otherwise `5m`, `2h`, `3d`, `1w`, `4mo`, `1y`. */
	fun compactAge(timestampMs: Long, nowMs: Long): String? {
		val seconds = ((nowMs - timestampMs) / 1000).coerceAtLeast(0)
		if (seconds < 60) return null
		val units = listOf("y" to 31_536_000L, "mo" to 2_592_000L, "w" to 604_800L, "d" to 86_400L, "h" to 3_600L, "m" to 60L)
		return units.firstNotNullOf { (unit, size) -> (seconds / size).takeIf { it >= 1 }?.let { "$it$unit" } }
	}

	/** Review stars as text, e.g. `★★★★½` for 4.5. */
	fun stars(rating: Double): String {
		val clamped = rating.coerceIn(0.0, 5.0)
		val full = clamped.toInt()
		val half = clamped - full >= 0.5
		return "★".repeat(full) + if (half) "½" else ""
	}

	/** Progress as a whole percentage (activity `Progress` is a fraction). */
	fun percent(fraction: Double): Int = (fraction.coerceIn(0.0, 1.0) * 100).toInt()

	/**
	 * Lazy list keys for [values], unique even when two values give the same [base] key: repeats get `#2`, `#3`, … in
	 * list order. Compose throws on duplicate keys, and `active-streams/sessions` has no session id (one user can
	 * play the same item on two devices with the same name), so content keys alone can collide.
	 */
	fun <T> uniqueKeys(values: List<T>, base: (T) -> String): List<String> {
		val seen = HashMap<String, Int>()
		return values.map { value ->
			val key = base(value)
			val count = seen.merge(key, 1, Int::plus)!!
			if (count == 1) key else "$key#$count"
		}
	}

	/** A stream's content key: who plays what where. Not unique on its own, see [uniqueKeys]. */
	fun sessionKey(session: ActiveSession) =
		"session-${session.userId}-${session.deviceName}-${session.client}-${session.nowPlaying?.id}"

	/** An activity entry's content key. Not unique on its own, see [uniqueKeys]. */
	fun entryKey(entry: ActivityEntry) = "${entry.activityType}-${entry.userId}-${entry.item?.id}-${entry.timestampMs}"

	/** Session progress percentage from ticks, or null when the runtime is unknown. */
	fun sessionPercent(positionTicks: Long?, runTimeTicks: Long?): Int? {
		if (positionTicks == null || runTimeTicks == null || runTimeTicks <= 0) return null
		return (positionTicks.coerceAtLeast(0) * 100 / runTimeTicks).toInt().coerceIn(0, 100)
	}
}
