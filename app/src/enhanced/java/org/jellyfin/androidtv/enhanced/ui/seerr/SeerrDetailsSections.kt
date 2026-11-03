package org.jellyfin.androidtv.enhanced.ui.seerr

import org.jellyfin.androidtv.enhanced.seerr.SeerrSeason

/**
 * A person in the cast row of the Seerr details screen. For titles not in the library it comes from Seerr's credits
 * ([tmdbPersonId]); for library titles from the server's own, Spoiler Guard filtered, people ([jellyfinPersonId]).
 */
data class SeerrDetailsCastEntry(
	val name: String,
	val role: String?,
	val imageUrl: String?,
	val tmdbPersonId: Int?,
	val jellyfinPersonId: String?,
)

/** A season card: its label, episode count and effective status (see `SeerrSeasonAvailability`). */
data class SeerrDetailsSeasonEntry(
	val seasonNumber: Int,
	val label: String,
	val episodeCount: Int,
	val status: Int?,
	val requestable: Boolean,
)

/** Season names for the season cards and the request picker, which must agree on what they withhold. */
object SeerrSeasonLabels {
	/**
	 * Seerr's own name for [season] ("Season 2: The Reckoning" can give the story away), or null when the neutral
	 * "Season N" / "Specials" label applies: always for a protected title, and when Seerr has no name.
	 */
	fun customName(season: SeerrSeason, guarded: Boolean): String? =
		season.name?.takeIf { !guarded && season.seasonNumber != 0 && it.isNotBlank() }
}
