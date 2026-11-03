package org.jellyfin.androidtv.enhanced.search

import org.jellyfin.androidtv.enhanced.seerr.SeerrMediaStatus
import org.jellyfin.androidtv.enhanced.seerr.SeerrResult
import org.jellyfin.androidtv.enhanced.seerr.SeerrSeasonStatus

/**
 * A search box value split the way the web splits it: "Title (YYYY)" searches for the title and keeps only results
 * from that year, the same year shown on the cards. The year only counts once the closing parenthesis is typed.
 */
data class SeerrSearchQuery(val title: String, val year: String?) {
	companion object {
		private val YEARED = Regex("^(.*\\S)\\s*\\((\\d{4})\\)\\s*$")

		fun parse(raw: String): SeerrSearchQuery {
			val match = YEARED.matchEntire(raw) ?: return SeerrSearchQuery(raw, null)
			return SeerrSearchQuery(match.groupValues[1], match.groupValues[2])
		}
	}
}

/**
 * Which Seerr search results to show. These are the search semantics of the web client
 * (`js/jellyseerr/jellyseerr.js`), which differ from discovery's: Hidden Content applies only when the user's
 * `FilterSearch` is on, and library titles are dropped by `JellyseerrSearchExcludeLibraryItems` (not the discovery
 * flag) using the cards' "in library" state. Blocklisted titles follow `JellyseerrExcludeBlocklistedItems`.
 *
 * Hidden ids only apply to movies and shows: TMDB person ids are a separate number space.
 */
data class SeerrSearchFilter(
	/** TMDB ids hidden everywhere; empty when the user doesn't filter search. */
	val hiddenTmdbIds: Set<String> = emptySet(),
	/** Jellyfin ids (dash-less, lower case) hidden everywhere; empty when the user doesn't filter search. */
	val hiddenItemIds: Set<String> = emptySet(),
	val excludeLibraryItems: Boolean = false,
	val excludeBlocklisted: Boolean = false,
	val year: String? = null,
) {
	fun accepts(item: SeerrResult): Boolean {
		if (year != null && yearOf(item) != year) return false
		if (item.mediaType == MEDIA_PERSON) return true
		if (item.id.toString() in hiddenTmdbIds) return false
		val libraryId = libraryId(item)
		if (libraryId != null && libraryId in hiddenItemIds) return false
		if (excludeLibraryItems && isInLibrary(item)) return false
		if (excludeBlocklisted && item.mediaInfo?.status == SeerrMediaStatus.BLOCKLISTED) return false
		return true
	}

	companion object {
		const val MEDIA_PERSON = "person"

		/** The 4-digit release year a card shows, if any. */
		fun yearOf(item: SeerrResult): String? = item.year

		fun libraryId(item: SeerrResult): String? =
			(item.mediaInfo?.jellyfinMediaId ?: item.mediaInfo?.jellyfinMediaId4k)
				?.takeIf { it.isNotBlank() }
				?.replace("-", "")
				?.lowercase()

		/**
		 * Whether the card shows the title as in the library: it has a Jellyfin id and is available or partially
		 * available. For shows the seasons decide, so a stale Jellyfin id on a show with no season present doesn't
		 * count. Port of `getLibraryAvailability` in `js/jellyseerr/ui/ui-cards.js`.
		 */
		fun isInLibrary(item: SeerrResult): Boolean {
			val info = item.mediaInfo ?: return false
			val jellyfinId = (info.jellyfinMediaId ?: info.jellyfinMediaId4k)?.takeIf { it.isNotBlank() } ?: return false
			val status = if (item.isTv && info.seasons.isNotEmpty()) overallSeasonStatus(info.seasons)
			else SeerrMediaStatus.effective(info.status, jellyfinId)
			return status == SeerrMediaStatus.AVAILABLE || status == SeerrMediaStatus.PARTIALLY_AVAILABLE
		}

		/** Port of `analyzeSeasonStatuses` in `js/jellyseerr/ui/ui-results.js` (overall status only). */
		fun overallSeasonStatus(seasons: List<SeerrSeasonStatus>): Int {
			val regular = seasons.filter { it.seasonNumber > 0 }
			if (regular.isEmpty()) return SeerrMediaStatus.UNKNOWN

			fun count(status: Int) = regular.count { it.status == status }
			val requested = count(SeerrMediaStatus.PENDING) + count(SeerrMediaStatus.PROCESSING)
			val available = count(SeerrMediaStatus.AVAILABLE) + count(SeerrMediaStatus.PARTIALLY_AVAILABLE)
			val notRequested = count(SeerrMediaStatus.UNKNOWN)

			val overall = when {
				notRequested == 0 -> if (available == regular.size) SeerrMediaStatus.AVAILABLE else SeerrMediaStatus.DELETED
				requested + available > 0 -> if (available > 0) SeerrMediaStatus.PARTIALLY_AVAILABLE else SeerrMediaStatus.PROCESSING
				else -> SeerrMediaStatus.UNKNOWN
			}
			// Unrequested specials keep a fully available show requestable, like the web's "Request More"
			val specials = seasons.firstOrNull { it.seasonNumber == 0 }
			return if (overall == SeerrMediaStatus.AVAILABLE && specials?.status == SeerrMediaStatus.UNKNOWN) SeerrMediaStatus.DELETED
			else overall
		}
	}
}
