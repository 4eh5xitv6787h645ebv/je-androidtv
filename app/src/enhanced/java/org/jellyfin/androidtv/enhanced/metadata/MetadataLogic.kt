package org.jellyfin.androidtv.enhanced.metadata

import org.jellyfin.androidtv.enhanced.data.EnhancedState
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import java.time.LocalDate
import java.util.Locale
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * The TMDB identity the plugin's TMDB-keyed routes (`reviews`, `awards`, `ddd`, `mdblist-ratings`…) use for a library
 * item. Seasons and episodes use their *show's* TMDB id: their own `ProviderIds.Tmdb` is TMDB's season/episode id,
 * which those routes don't understand.
 */
data class TmdbTarget(
	/** `movie`, `tv` or `person`. */
	val mediaType: String,
	val tmdbId: Int,
	val seasonNumber: Int? = null,
	val episodeNumber: Int? = null,
) {
	val isSeasonOrEpisode get() = seasonNumber != null

	/** The JE review key: `603`, `1399:s1` or `1399:s1:e2` (never `s1e2`). */
	val reviewKey: String
		get() = buildString {
			append(tmdbId)
			if (seasonNumber != null) {
				append(":s").append(seasonNumber)
				if (episodeNumber != null) append(":e").append(episodeNumber)
			}
		}

	/** The `watch-providers` batch key (`movie:603`, `tv:1399`). */
	val providerKey get() = "$mediaType:$tmdbId"

	companion object {
		const val MOVIE = "movie"
		const val TV = "tv"
		const val PERSON = "person"

		/**
		 * The target of [item]. [seriesTmdbId] is the parent show's TMDB id for seasons and episodes (fetched
		 * separately: the DTO doesn't carry it). Null when the item has no usable TMDB id.
		 */
		fun of(item: BaseItemDto, seriesTmdbId: Int? = null): TmdbTarget? = when (item.type) {
			BaseItemKind.MOVIE -> tmdbIdOf(item)?.let { TmdbTarget(MOVIE, it) }
			BaseItemKind.SERIES -> tmdbIdOf(item)?.let { TmdbTarget(TV, it) }
			BaseItemKind.PERSON -> tmdbIdOf(item)?.let { TmdbTarget(PERSON, it) }
			BaseItemKind.SEASON -> {
				val season = item.indexNumber
				if (seriesTmdbId != null && season != null) TmdbTarget(TV, seriesTmdbId, season) else null
			}

			BaseItemKind.EPISODE -> {
				val season = item.parentIndexNumber
				val episode = item.indexNumber
				if (seriesTmdbId != null && season != null && episode != null) TmdbTarget(TV, seriesTmdbId, season, episode) else null
			}

			else -> null
		}

		fun tmdbIdOf(item: BaseItemDto): Int? =
			item.providerIds?.entries?.firstOrNull { it.key.equals("Tmdb", ignoreCase = true) }?.value?.toIntOrNull()
	}
}

/** The overview the plugin substitutes for a protected item. */
const val SPOILER_GUARD_OVERVIEW = "Spoiler Guard activated"

private val GUARDABLE_TYPES = setOf(BaseItemKind.MOVIE, BaseItemKind.SERIES, BaseItemKind.SEASON, BaseItemKind.EPISODE)

/**
 * Whether external, story-related data (ratings, reviews, air dates) must be withheld for [item]. The plugin strips
 * ratings and overviews from protected items server side, so their absence while Spoiler Guard is on is treated as
 * "protected". Fail-closed: an item that simply has no community rating is withheld too.
 */
fun isSpoilerGuarded(state: EnhancedState, item: BaseItemDto): Boolean =
	item.overview == SPOILER_GUARD_OVERVIEW ||
		(state.spoilerGuard && item.communityRating == null && item.type in GUARDABLE_TYPES)

// Sizes and progress

/** Bytes as the web shows them: 1024-based, up to two decimals (`3.15 GB`). */
fun formatSize(bytes: Long): String {
	if (bytes <= 0) return "0 Bytes"
	val units = listOf("Bytes", "KB", "MB", "GB", "TB", "PB")
	val exponent = floor(ln(bytes.toDouble()) / ln(1024.0)).toInt().coerceIn(0, units.lastIndex)
	val value = bytes / 1024.0.pow(exponent)
	val rounded = (value * 100).roundToInt() / 100.0
	val text = if (rounded == floor(rounded)) rounded.toLong().toString() else rounded.toString().trimEnd('0').trimEnd('.')
	return "$text ${units[exponent]}"
}

/** The watch-progress display modes the web cycles through, in order (`WatchProgressMode`). */
enum class WatchProgressMode(val key: String) {
	PERCENTAGE("percentage"),
	TIME("time"),
	REMAINING("remaining");

	fun next() = entries[(ordinal + 1) % entries.size]

	companion object {
		fun of(key: String?) = entries.firstOrNull { it.key.equals(key, ignoreCase = true) } ?: PERCENTAGE
	}
}

private const val TICKS_PER_SECOND = 10_000_000L

/**
 * A tick count as the web's watch-progress chip writes it. Format `hours` gives `12h 5m`; anything else the long form
 * (`1y 2mo`, `3mo 4d`, `2d 5h`, `3h 20m`).
 */
fun formatTicks(ticks: Long, format: String?): String {
	val totalMinutes = ticks.coerceAtLeast(0) / TICKS_PER_SECOND / 60
	val totalHours = totalMinutes / 60
	val totalDays = totalHours / 24
	val totalMonths = totalDays / 30
	val totalYears = totalDays / 365

	fun pair(major: Long, majorUnit: String, minor: Long, minorUnit: String) =
		if (minor > 0) "$major$majorUnit $minor$minorUnit" else "$major$majorUnit"

	return when {
		format.equals("hours", ignoreCase = true) || format.isNullOrEmpty() -> when {
			totalHours >= 1 -> pair(totalHours, "h", totalMinutes % 60, "m")
			else -> "${totalMinutes}m"
		}

		totalYears >= 1 -> pair(totalYears, "y", (totalDays % 365) / 30, "mo")
		totalMonths >= 1 -> pair(totalMonths, "mo", totalDays % 30, "d")
		totalDays >= 1 -> pair(totalDays, "d", totalHours % 24, "h")
		totalHours >= 1 -> pair(totalHours, "h", totalMinutes % 60, "m")
		else -> "${totalMinutes}m"
	}
}

/** The watch-progress chip text for [mode]: `42%`, `1h 5m / 2h 32m` or `-1h 27m / 2h 32m`. */
fun watchProgressText(stats: ItemStats, mode: WatchProgressMode, timeFormat: String?): String {
	val total = stats.totalRuntimeTicks.coerceAtLeast(0)
	val played = stats.totalPlaybackTicks.coerceIn(0, total)
	return when (mode) {
		WatchProgressMode.PERCENTAGE -> "${stats.progress ?: 0}%"
		WatchProgressMode.TIME -> "${formatTicks(played, timeFormat)} / ${formatTicks(total, timeFormat)}"
		WatchProgressMode.REMAINING -> "-${formatTicks(total - played, timeFormat)} / ${formatTicks(total, timeFormat)}"
	}
}

// Audio languages

/** ISO 639-2/B codes that differ from the /T codes Java knows. */
private val BIBLIOGRAPHIC_CODES = mapOf(
	"alb" to "sq", "arm" to "hy", "baq" to "eu", "bur" to "my", "chi" to "zh", "cze" to "cs", "dut" to "nl",
	"fre" to "fr", "geo" to "ka", "ger" to "de", "gre" to "el", "ice" to "is", "mac" to "mk", "mao" to "mi",
	"may" to "ms", "per" to "fa", "rum" to "ro", "slo" to "sk", "tib" to "bo", "wel" to "cy",
)

private val ISO3_TO_ISO2: Map<String, String> by lazy {
	Locale.getISOLanguages().mapNotNull { code ->
		runCatching { Locale.forLanguageTag(code).isO3Language }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { it to code }
	}.toMap()
}

/** Normalise a stream language (`eng`, `ger`, `en-US`, `pt_BR`) to a BCP 47 tag (`en`, `de`, `en-US`, `pt-BR`). */
fun normalizeLanguageTag(code: String): String {
	val parts = code.trim().replace('_', '-').split('-').filter { it.isNotEmpty() }
	if (parts.isEmpty()) return code
	val language = parts.first().lowercase()
	val base = BIBLIOGRAPHIC_CODES[language] ?: ISO3_TO_ISO2[language] ?: language
	return (listOf(base) + parts.drop(1)).joinToString("-")
}

/** Distinct audio languages of [streams], in stream order, skipping `und`/`root`. Returned as BCP 47 tags. */
fun audioLanguages(streams: List<TagDataStream>): List<String> = streams
	.asSequence()
	.filter { it.type.equals("Audio", ignoreCase = true) }
	.mapNotNull { it.language?.trim()?.takeIf(String::isNotEmpty) }
	.filter { it.lowercase() !in setOf("und", "root") }
	.map(::normalizeLanguageTag)
	.distinctBy { it.lowercase() }
	.toList()

/** A display name for a language tag in the user's locale; the tag itself when unknown. */
fun languageDisplayName(tag: String, locale: Locale = Locale.getDefault()): String {
	val parsed = Locale.forLanguageTag(tag)
	val name = parsed.getDisplayName(locale)
	return if (name.isBlank() || name.equals(tag, ignoreCase = true)) tag.uppercase() else name
}

// Release dates

/** One release/air date chip. [date] is a calendar date; TMDB dates are not instants. */
data class ReleaseInfo(val kind: Kind, val date: LocalDate) {
	enum class Kind { CINEMA, DIGITAL, PHYSICAL, EPISODE }
}

private val MOVIE_BUCKETS = listOf(
	ReleaseInfo.Kind.CINEMA to setOf(1, 2, 3),
	ReleaseInfo.Kind.DIGITAL to setOf(4),
	ReleaseInfo.Kind.PHYSICAL to setOf(5),
)

/** Parse `YYYY-MM-DD` or `YYYY-MM-DDT…`, as a calendar date. */
fun parseCalendarDate(value: String?): LocalDate? =
	value?.takeIf { it.length >= 10 }?.let { runCatching { LocalDate.parse(it.substring(0, 10)) }.getOrNull() }

/**
 * Theatrical, digital and physical release dates of a movie: the earliest date of each kind in the first of
 * [regions] that has one, else in any region (most countries record only one kind), like the web.
 */
fun movieReleaseInfo(releases: TmdbReleaseDates, regions: List<String>): List<ReleaseInfo> {
	fun earliest(entry: TmdbRegionReleases?, types: Set<Int>): LocalDate? =
		entry?.releaseDates?.filter { it.type in types }?.mapNotNull { parseCalendarDate(it.releaseDate) }?.minOrNull()

	return MOVIE_BUCKETS.mapNotNull { (kind, types) ->
		val preferred = regions.firstNotNullOfOrNull { region ->
			earliest(releases.results.firstOrNull { it.region.equals(region, ignoreCase = true) }, types)
		}
		val date = preferred ?: releases.results.firstNotNullOfOrNull { earliest(it, types) }
		date?.let { ReleaseInfo(kind, it) }
	}
}

/** A show's next episode, else its last one. */
fun seriesAirInfo(show: TmdbAirDates): ReleaseInfo? =
	(parseCalendarDate(show.nextEpisodeToAir?.airDate) ?: parseCalendarDate(show.lastEpisodeToAir?.airDate))
		?.let { ReleaseInfo(ReleaseInfo.Kind.EPISODE, it) }

/** A season's next episode from [today], else its last aired one. */
fun seasonAirInfo(season: TmdbAirDates, today: LocalDate): ReleaseInfo? {
	val dates = season.episodes.mapNotNull { parseCalendarDate(it.airDate) }
	if (dates.isEmpty()) return null
	val date = dates.firstOrNull { !it.isBefore(today) } ?: dates.last()
	return ReleaseInfo(ReleaseInfo.Kind.EPISODE, date)
}

/** Regions to try, in order: the user's Elsewhere region, the server default, then US. */
fun releaseRegions(userRegion: String?, defaultRegion: String?): List<String> =
	listOfNotNull(userRegion, defaultRegion, "US")
		.map { it.trim().uppercase() }
		.filter { it.isNotEmpty() }
		.distinct()

// Watch providers

/** Split the plugin's provider lists (`DEFAULT_PROVIDERS`, `IGNORE_PROVIDERS`): commas or new lines, quotes ignored. */
fun splitProviderList(raw: String?): List<String> =
	raw.orEmpty().replace("'", "").replace('\n', ',').split(',').map { it.trim() }.filter { it.isNotEmpty() }

/**
 * Providers to show: the user's chosen services when they picked any, otherwise the server's `DEFAULT_PROVIDERS`;
 * then without anything matching an `IGNORE_PROVIDERS` pattern (case-insensitive regular expressions, as on the web;
 * an invalid pattern is ignored).
 */
fun filterProviders(
	providers: List<WatchProvider>,
	userServices: List<String>,
	defaultProviders: List<String>,
	ignorePatterns: List<String>,
): List<WatchProvider> {
	var filtered = providers.distinctBy { it.providerId }
	val allowed = userServices.ifEmpty { defaultProviders }
	if (allowed.isNotEmpty()) filtered = filtered.filter { it.providerName in allowed }
	val ignore = ignorePatterns.mapNotNull { runCatching { Regex(it, RegexOption.IGNORE_CASE) }.getOrNull() }
	if (ignore.isNotEmpty()) filtered = filtered.filterNot { provider -> ignore.any { it.containsMatchIn(provider.providerName) } }
	return filtered
}

// Ratings

/** A named rating chip: `IMDb 7.9`, `RT Critic 92%`. */
data class RatingChip(val source: String, val label: String, val value: String)

private val MDBLIST_LABELS = mapOf(
	"master" to "Master", "imdb" to "IMDb", "tmdb" to "TMDB", "trakt" to "Trakt", "letterboxd" to "Letterboxd",
	"tomatoes" to "RT Critic", "popcorn" to "RT Audience", "metacritic" to "Metascore",
	"metacriticuser" to "Metacritic User", "rogerebert" to "Roger Ebert", "myanimelist" to "MAL", "anilist" to "AniList",
)

private val MDBLIST_DEFAULT_ORDER = listOf("tmdb", "tomatoes", "popcorn", "imdb", "trakt", "metacritic")

private fun formatScore(value: Double): String =
	if (value == floor(value)) value.toLong().toString() else "%.1f".format(Locale.ROOT, value).trimEnd('0').trimEnd('.')

/**
 * MDBList chips, following the web: only sources with a value, only the admin's `MdblistRatingsSources` (in that
 * order) when set, else the default order first. A normalised `Score` is shown rounded (with `%` when the admin wants
 * it); otherwise the source's raw `Value` keeps its precision. Unconfirmed results give nothing (unknown, not zero).
 */
fun mdblistChips(ratings: MdblistRatings, configuredSources: String?, showPercent: Boolean): List<RatingChip> {
	if (!ratings.confirmed) return emptyList()
	val configured = configuredSources.orEmpty().split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
	val order = configured.ifEmpty { MDBLIST_DEFAULT_ORDER }

	return ratings.ratings
		.filter { it.source != null && (it.score != null || it.value != null) }
		.filter { configured.isEmpty() || it.source!!.lowercase() in configured }
		.sortedBy { order.indexOf(it.source!!.lowercase()).let { index -> if (index < 0) Int.MAX_VALUE else index } }
		.distinctBy { it.source!!.lowercase() }
		.map { rating ->
			val key = rating.source!!.lowercase()
			val value = rating.score?.let { it.roundToInt().toString() + if (showPercent) "%" else "" } ?: formatScore(rating.value!!)
			RatingChip(key, MDBLIST_LABELS[key] ?: rating.source, value)
		}
}

/** Seerr's Rotten Tomatoes/IMDb chips, keyed like MDBList's sources so duplicates can be dropped. */
fun seerrChips(rt: SeerrRtRating?, imdb: SeerrImdbRating?): List<RatingChip> = listOfNotNull(
	imdb?.criticsScore?.takeIf { it > 0 }?.let { RatingChip("imdb", "IMDb", formatScore(it)) },
	rt?.criticsScore?.takeIf { it > 0 }?.let { RatingChip("tomatoes", "RT Critic", "$it%") },
	rt?.audienceScore?.takeIf { it > 0 }?.let { RatingChip("popcorn", "RT Audience", "$it%") },
)

/** MDBList chips first, then Seerr's for sources MDBList didn't cover. */
fun mergeRatingChips(mdblist: List<RatingChip>, seerr: List<RatingChip>): List<RatingChip> {
	val seen = mdblist.map { it.source }.toSet()
	return mdblist + seerr.filter { it.source !in seen }
}

// Content warnings

/** The web counts a topic as present at 50% "yes" with at least three votes. */
const val DDD_PRESENT_PERCENT = 50
const val DDD_MIN_VOTES = 3

/**
 * A title's content warnings split the way the web shows them: [triggered] are the user's triggers that are
 * present, [clear] the user's triggers voted absent, [others] everything else present. Each sorted by vote count.
 */
data class ContentWarnings(
	val triggered: List<DddTopicVotes>,
	val clear: List<DddTopicVotes>,
	val others: List<DddTopicVotes>,
) {
	val isEmpty get() = triggered.isEmpty() && clear.isEmpty() && others.isEmpty()

	companion object {
		fun of(entry: DddEntry, triggers: Set<Int>): ContentWarnings {
			fun present(topic: DddTopicVotes) = topic.percent >= DDD_PRESENT_PERCENT
			val byVotes = compareByDescending<DddTopicVotes> { it.votes }
			val mine = entry.topics.filter { it.id in triggers }
			return ContentWarnings(
				triggered = mine.filter(::present).sortedWith(byVotes),
				clear = mine.filterNot(::present),
				others = entry.topics
					.filter { it.id !in triggers && present(it) && it.votes >= DDD_MIN_VOTES }
					.sortedWith(byVotes),
			)
		}
	}
}

/** Capitalise the first letter, as the web does for DoesTheDogDie topic names. */
fun capitalizeFirst(text: String) = text.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
