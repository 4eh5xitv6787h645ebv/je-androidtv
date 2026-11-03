package org.jellyfin.androidtv.enhanced.seerrdetails

import org.jellyfin.androidtv.enhanced.seerr.SeerrDetails
import org.jellyfin.androidtv.enhanced.seerr.SeerrMediaStatus
import org.jellyfin.androidtv.enhanced.seerr.SeerrSeason

/**
 * Per-season status of a show, the TV counterpart of the web's `checkForUnrequestedSeasons`
 * (`moreinfo/more-info-modal-seasons.js`).
 *
 * Seerr's `mediaInfo.seasons` alone isn't enough: a pending or approved request doesn't always show up there (it can be
 * empty while a request is processing), so seasons held by an open request count as requested too. When the library's
 * own seasons are known they win over Seerr's view: Seerr keeps reporting a season as available after it was deleted
 * (so an "available" season that isn't there counts as deleted, requestable again), and a season that is in the
 * library but Seerr hasn't synced yet isn't offered.
 */
object SeerrSeasonAvailability {
	/**
	 * The effective media status of [seasonNumber]. [requests] are the title's `mediaInfo.requests`, [librarySeasons]
	 * the season numbers present in the Jellyfin library, or null when unknown (the show isn't in the library, or
	 * they couldn't be loaded).
	 */
	fun seasonStatus(
		details: SeerrDetails,
		requests: List<SeerrMediaRequest>,
		librarySeasons: Set<Int>?,
		seasonNumber: Int,
	): Int? {
		val media = details.mediaInfo
		val raw = media?.seasons?.firstOrNull { it.seasonNumber == seasonNumber }?.status
		val mediaStatus = when {
			raw != SeerrMediaStatus.AVAILABLE && raw != SeerrMediaStatus.PARTIALLY_AVAILABLE -> raw
			librarySeasons != null -> if (seasonNumber in librarySeasons) raw else SeerrMediaStatus.DELETED
			else -> media?.effectiveSeasonStatus(seasonNumber)
		}
		if (!SeerrMediaStatus.isRequestable(mediaStatus)) return mediaStatus
		// In the library even though Seerr hasn't caught up: nothing to request
		if (librarySeasons != null && seasonNumber in librarySeasons) return SeerrMediaStatus.AVAILABLE

		// Not (or no longer) in the library: an open request still holds the season
		val holding = requests
			.filter { !it.is4k && SeerrRequestStatus.blocksSeasons(it.status) }
			.filter { request -> request.seasons.any { it.seasonNumber == seasonNumber } }
		return when {
			holding.isEmpty() -> mediaStatus
			holding.any { it.status == SeerrRequestStatus.PENDING } -> SeerrMediaStatus.PENDING
			else -> SeerrMediaStatus.PROCESSING
		}
	}

	/**
	 * Seasons that can still be requested: with episodes, not available, not requested. Specials (season 0) only
	 * when [includeSpecials].
	 */
	fun requestableSeasons(
		details: SeerrDetails,
		requests: List<SeerrMediaRequest>,
		librarySeasons: Set<Int>?,
		includeSpecials: Boolean,
	): List<SeerrSeason> = details.seasons
		.filter { it.episodeCount > 0 }
		.filter { it.seasonNumber > 0 || includeSpecials }
		.filter { SeerrMediaStatus.isRequestable(seasonStatus(details, requests, librarySeasons, it.seasonNumber)) }
		.distinctBy { it.seasonNumber }
		.sortedBy { it.seasonNumber }
}
