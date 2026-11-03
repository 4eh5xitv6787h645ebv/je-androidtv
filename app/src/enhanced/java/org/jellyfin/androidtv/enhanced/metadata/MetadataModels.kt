package org.jellyfin.androidtv.enhanced.metadata

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Public-config fields this feature needs that [org.jellyfin.androidtv.enhanced.data.EnhancedPublicConfig] doesn't
 * carry: the plugin's defaults for per-user settings and the Elsewhere/MDBList presentation options. Decoded from
 * our own `GET public-config` (PascalCase; `DEFAULT_*` are spelled exactly like this).
 */
@Serializable
data class MetadataPublicConfig(
	@SerialName("ShowFileSizes") val showFileSizes: Boolean = false,
	@SerialName("ShowWatchProgress") val showWatchProgress: Boolean = false,
	@SerialName("WatchProgressDefaultMode") val watchProgressDefaultMode: String? = null,
	@SerialName("WatchProgressTimeFormat") val watchProgressTimeFormat: String? = null,
	@SerialName("ShowAudioLanguages") val showAudioLanguages: Boolean = false,
	@SerialName("PeopleTagsEnabled") val peopleTagsEnabled: Boolean = false,
	@SerialName("ReviewsExpandedByDefault") val reviewsExpandedByDefault: Boolean = false,
	@SerialName("DEFAULT_REGION") val defaultRegion: String? = null,
	@SerialName("DEFAULT_PROVIDERS") val defaultProviders: String? = null,
	@SerialName("IGNORE_PROVIDERS") val ignoreProviders: String? = null,
	@SerialName("MdblistRatingsSources") val mdblistRatingsSources: String? = null,
)

/** `GET item-stats/{uid}/{itemId}`: aggregated size and watched progress of an item and its children. */
@Serializable
data class ItemStats(
	val success: Boolean = false,
	/** Bytes. */
	val size: Long? = null,
	/** Integer percentage 0–100. */
	val progress: Int? = null,
	val totalPlaybackTicks: Long = 0,
	val totalRuntimeTicks: Long = 0,
)

/** `GET mdblist-ratings/{movie|tv}/{tmdbId}`. `Confirmed:false` means "unknown for now", never "no ratings". */
@Serializable
data class MdblistRatings(
	@SerialName("Found") val found: Boolean = false,
	@SerialName("Confirmed") val confirmed: Boolean = false,
	@SerialName("Ratings") val ratings: List<MdblistRating> = emptyList(),
)

@Serializable
data class MdblistRating(
	@SerialName("Source") val source: String? = null,
	/** The source's own scale (IMDb 0–10, Letterboxd 0–5…). */
	@SerialName("Value") val value: Double? = null,
	/** MDBList's normalised 0–100 figure, when it has one. */
	@SerialName("Score") val score: Double? = null,
	@SerialName("Votes") val votes: Long? = null,
)

/** Rotten Tomatoes block of Seerr's ratings (`jellyseerr/tv/{id}/ratings` returns it unwrapped). */
@Serializable
data class SeerrRtRating(
	val title: String? = null,
	val criticsRating: String? = null,
	val criticsScore: Int? = null,
	val audienceRating: String? = null,
	val audienceScore: Int? = null,
)

@Serializable
data class SeerrImdbRating(
	val title: String? = null,
	val criticsScore: Double? = null,
	val criticsScoreCount: Long? = null,
)

/** `GET jellyseerr/movie/{id}/ratingscombined`. */
@Serializable
data class SeerrCombinedRatings(
	val rt: SeerrRtRating? = null,
	val imdb: SeerrImdbRating? = null,
)

/** `GET awards/{movie|tv|person}/{tmdbId}` (Wikidata-backed). */
@Serializable
data class Awards(
	@SerialName("Found") val found: Boolean = false,
	@SerialName("Wins") val wins: Int = 0,
	@SerialName("Nominations") val nominations: Int = 0,
	@SerialName("Awards") val awards: List<Award> = emptyList(),
)

@Serializable
data class Award(
	@SerialName("Name") val name: String = "",
	/** `Won` or `Nominated`. */
	@SerialName("Result") val result: String? = null,
	@SerialName("Year") val year: Int? = null,
	/** On titles: who received it. */
	@SerialName("Recipients") val recipients: List<String> = emptyList(),
	/** On people: for which work. */
	@SerialName("Works") val works: List<String> = emptyList(),
) {
	val won get() = result.equals("Won", ignoreCase = true)
}

/** `GET watch-providers?items=…&region=…`: flat-rate providers per `movie:603`/`tv:1399` key. */
@Serializable
data class WatchProviders(
	val region: String? = null,
	val results: Map<String, List<WatchProvider>?> = emptyMap(),
)

@Serializable
data class WatchProvider(
	@SerialName("provider_id") val providerId: Int = 0,
	@SerialName("provider_name") val providerName: String = "",
	@SerialName("logo_path") val logoPath: String? = null,
) {
	val logoUrl get() = logoPath?.let { "https://image.tmdb.org/t/p/w92$it" }
}

/** `GET tmdb/movie/{id}/release_dates`. */
@Serializable
data class TmdbReleaseDates(
	val results: List<TmdbRegionReleases> = emptyList(),
)

@Serializable
data class TmdbRegionReleases(
	@SerialName("iso_3166_1") val region: String = "",
	@SerialName("release_dates") val releaseDates: List<TmdbReleaseDate> = emptyList(),
)

@Serializable
data class TmdbReleaseDate(
	/** `YYYY-MM-DDT00:00:00.000Z`; a calendar date, not an instant. */
	@SerialName("release_date") val releaseDate: String? = null,
	/** TMDB release type: 1 premiere, 2 limited, 3 theatrical, 4 digital, 5 physical, 6 TV. */
	val type: Int = 0,
)

/** The air-date part of `GET tmdb/tv/{id}`, `…/season/{n}` and `…/season/{n}/episode/{e}`. */
@Serializable
data class TmdbAirDates(
	@SerialName("air_date") val airDate: String? = null,
	@SerialName("next_episode_to_air") val nextEpisodeToAir: TmdbEpisodeAirDate? = null,
	@SerialName("last_episode_to_air") val lastEpisodeToAir: TmdbEpisodeAirDate? = null,
	val episodes: List<TmdbEpisodeAirDate> = emptyList(),
)

@Serializable
data class TmdbEpisodeAirDate(
	@SerialName("air_date") val airDate: String? = null,
)

/** `GET ddd/{movie|tv}/{tmdbId}`: DoesTheDogDie community votes per topic. */
@Serializable
data class DddEntry(
	@SerialName("Found") val found: Boolean = false,
	@SerialName("Name") val name: String? = null,
	@SerialName("Topics") val topics: List<DddTopicVotes> = emptyList(),
)

@Serializable
data class DddTopicVotes(
	@SerialName("Id") val id: Int = 0,
	@SerialName("Name") val name: String = "",
	@SerialName("Yes") val yes: Int = 0,
	@SerialName("No") val no: Int = 0,
) {
	val votes get() = yes + no

	/** Share of "yes" votes, 0–100; 0 when nobody voted. */
	val percent get() = if (votes == 0) 0 else Math.round(yes * 100.0 / votes).toInt()
}

/** `GET ddd/topics`: the catalogue for the trigger picker. */
@Serializable
data class DddTopicCatalog(
	@SerialName("Topics") val topics: List<DddTopic> = emptyList(),
)

@Serializable
data class DddTopic(
	@SerialName("Id") val id: Int = 0,
	@SerialName("Name") val name: String = "",
	@SerialName("Category") val category: String = "",
	@SerialName("Group") val group: String = "",
)

/** `GET people/info?ids=…&itemId=…`. Keys are dashless Jellyfin person ids. */
@Serializable
data class PeopleInfo(
	val people: Map<String, PersonInfo> = emptyMap(),
)

@Serializable
data class PersonInfo(
	val id: String? = null,
	val name: String? = null,
	val birthDate: String? = null,
	val deathDate: String? = null,
	val birthPlace: String? = null,
	val isDeceased: Boolean = false,
	val currentAge: Int? = null,
	val ageAtDeath: Int? = null,
	val ageAtItemRelease: Int? = null,
)

/** `POST tag-data/{uid}` with `["itemId", …]`. Only the parts used for audio languages. */
@Serializable
data class TagData(
	@SerialName("Items") val items: List<TagDataItem> = emptyList(),
)

@Serializable
data class TagDataItem(
	@SerialName("Id") val id: String = "",
	@SerialName("Type") val type: String? = null,
	@SerialName("MediaStreams") val mediaStreams: List<TagDataStream> = emptyList(),
	@SerialName("FirstEpisode") val firstEpisode: TagDataRef? = null,
)

@Serializable
data class TagDataStream(
	@SerialName("Type") val type: String? = null,
	/** Region-aware where the file says so (`en-US`, `pt-BR`), otherwise ISO 639-2 (`eng`). */
	@SerialName("Language") val language: String? = null,
)

@Serializable
data class TagDataRef(
	@SerialName("Id") val id: String = "",
)
