package org.jellyfin.androidtv.enhanced.metadata

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.jellyfin.androidtv.auth.repository.UserRepository
import org.jellyfin.androidtv.enhanced.api.EnhancedApi
import org.jellyfin.androidtv.enhanced.data.EnhancedRepository
import org.jellyfin.androidtv.enhanced.data.EnhancedSettingsRepository
import org.jellyfin.androidtv.enhanced.data.EnhancedState
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import java.time.LocalDate
import java.util.UUID

/**
 * Calls behind the details enrichments: item stats, ratings, awards, release dates, watch providers, content
 * warnings and people facts. Everything is read as the signed-in user; results that belong to a user (stats, the
 * series lookups) are cached per user only.
 */
class MetadataRepository(
	private val enhancedApi: EnhancedApi,
	private val enhancedRepository: EnhancedRepository,
	private val settingsRepository: EnhancedSettingsRepository,
	private val userRepository: UserRepository,
) {
	private val mutex = Mutex()
	private var configFor: EnhancedState? = null
	private var config: MetadataPublicConfig? = null
	private val seriesTmdbIds = HashMap<Pair<UUID, UUID>, Int?>()

	/** The signed-in user's id in the plugin's dashless form. */
	private fun userKey(): String =
		requireNotNull(userRepository.currentUser.value?.id) { "No signed-in user" }.toString().replace("-", "")

	private inline fun <reified T> decode(element: JsonElement): T = EnhancedApi.json.decodeFromJsonElement(element)

	/** Our part of the public config. Re-read whenever the plugin state is reloaded (user switch, settings save). */
	suspend fun publicConfig(): MetadataPublicConfig {
		val state = enhancedRepository.ensureLoaded()
		mutex.withLock {
			if (configFor === state) config?.let { return it }
		}
		val loaded = decode<MetadataPublicConfig>(enhancedApi.get("public-config"))
		mutex.withLock {
			configFor = state
			config = loaded
		}
		return loaded
	}

	/**
	 * The parent show's TMDB id for a season or episode (their own `Tmdb` provider id is TMDB's season/episode id).
	 * Returns the item's own id for movies, shows and people.
	 */
	suspend fun tmdbTarget(item: BaseItemDto): TmdbTarget? {
		if (item.type != BaseItemKind.SEASON && item.type != BaseItemKind.EPISODE) return TmdbTarget.of(item)
		val seriesId = item.seriesId ?: return null
		val userId = userRepository.currentUser.value?.id ?: return null
		val key = userId to seriesId
		mutex.withLock {
			if (seriesTmdbIds.containsKey(key)) return TmdbTarget.of(item, seriesTmdbIds[key])
		}
		val series = enhancedApi.get("/Items/$seriesId", mapOf("userId" to userId.toString())) as? JsonObject
		val ids = series?.get("ProviderIds") as? JsonObject
		val tmdbId = ids?.entries?.firstOrNull { it.key.equals("Tmdb", ignoreCase = true) }?.value
			?.let { (it as? JsonPrimitive)?.contentOrNull?.toIntOrNull() }
		mutex.withLock { seriesTmdbIds[key] = tmdbId }
		return TmdbTarget.of(item, tmdbId)
	}

	suspend fun itemStats(itemId: UUID): ItemStats =
		decode(enhancedApi.get("item-stats/${userKey()}/${itemId.toString().replace("-", "")}"))

	/**
	 * Audio languages from the plugin's region-aware stream projection (`tag-data`), as the web does. Shows and
	 * seasons use their first episode. Null when the plugin didn't return the item.
	 */
	suspend fun audioLanguages(itemId: UUID): List<String>? {
		val user = userKey()
		suspend fun tagData(id: String): TagDataItem? =
			decode<TagData>(enhancedApi.postRaw("tag-data/$user", JsonArray(listOf(JsonPrimitive(id))))).items.firstOrNull()

		val item = tagData(itemId.toString().replace("-", "")) ?: return null
		val source = if (item.type == "Series" || item.type == "Season") {
			item.firstEpisode?.id?.let { tagData(it) } ?: return emptyList()
		} else item
		return audioLanguages(source.mediaStreams)
	}

	suspend fun mdblistRatings(target: TmdbTarget): MdblistRatings =
		decode(enhancedApi.get("mdblist-ratings/${target.mediaType}/${target.tmdbId}"))

	/** Seerr's Rotten Tomatoes (and, for movies, IMDb) ratings. TV uses a different route and shape. */
	suspend fun seerrRatings(target: TmdbTarget): List<RatingChip> = if (target.mediaType == TmdbTarget.MOVIE) {
		val combined = decode<SeerrCombinedRatings>(enhancedApi.get("jellyseerr/movie/${target.tmdbId}/ratingscombined"))
		seerrChips(combined.rt, combined.imdb)
	} else {
		seerrChips(decode<SeerrRtRating>(enhancedApi.get("jellyseerr/tv/${target.tmdbId}/ratings")), null)
	}

	suspend fun awards(target: TmdbTarget): Awards =
		decode(enhancedApi.get("awards/${target.mediaType}/${target.tmdbId}"))

	/** The user's Elsewhere preferences (`elsewhere.json`). */
	suspend fun elsewhereSettings(): ElsewhereSettings = ElsewhereSettings.from(settingsRepository.read(EnhancedSettingsRepository.ELSEWHERE).json)

	/** Change the user's Elsewhere preferences; other fields of the document are kept. */
	suspend fun editElsewhere(edit: (MutableMap<String, JsonElement>) -> Unit): ElsewhereSettings =
		ElsewhereSettings.from(settingsRepository.edit(EnhancedSettingsRepository.ELSEWHERE, edit).json)

	/** Regions TMDB has provider data for, as (code, name), by name. */
	suspend fun providerRegions(): List<Pair<String, String>> =
		decode<TmdbRegions>(enhancedApi.get("tmdb/watch/providers/regions")).results
			.filter { it.code.isNotBlank() }
			.map { it.code.uppercase() to (it.englishName ?: it.code) }
			.sortedBy { it.second.lowercase() }

	/** Names of the streaming services TMDB lists for [region] (movies and shows), most prominent first. */
	suspend fun regionServices(region: String): List<String> = coroutineScope {
		val lists = listOf("movie", "tv").map { type ->
			async { decode<TmdbProviderList>(enhancedApi.get("tmdb/watch/providers/$type", mapOf("watch_region" to region))).results }
		}.awaitAll().flatten()
		sortServices(lists, region)
	}

	/** Flat-rate providers for one title in [region], filtered by the user's and the server's provider lists. */
	suspend fun watchProviders(target: TmdbTarget, region: String, userServices: List<String>, config: MetadataPublicConfig): List<WatchProvider> {
		val response = decode<WatchProviders>(enhancedApi.get("watch-providers", mapOf("items" to target.providerKey, "region" to region)))
		return filterProviders(
			providers = response.results[target.providerKey].orEmpty(),
			userServices = userServices,
			defaultProviders = splitProviderList(config.defaultProviders),
			ignorePatterns = splitProviderList(config.ignoreProviders),
		)
	}

	/** Release dates of a movie, or the next/last air date of a show, season or episode. */
	suspend fun releaseInfo(target: TmdbTarget, regions: List<String>): List<ReleaseInfo> = when {
		target.mediaType == TmdbTarget.MOVIE ->
			movieReleaseInfo(decode(enhancedApi.get("tmdb/movie/${target.tmdbId}/release_dates")), regions)

		target.mediaType != TmdbTarget.TV -> emptyList()

		target.episodeNumber != null -> listOfNotNull(
			parseCalendarDate(decode<TmdbAirDates>(enhancedApi.get("tmdb/tv/${target.tmdbId}/season/${target.seasonNumber}/episode/${target.episodeNumber}")).airDate)
				?.let { ReleaseInfo(ReleaseInfo.Kind.EPISODE, it) }
		)

		target.seasonNumber != null -> listOfNotNull(
			seasonAirInfo(decode(enhancedApi.get("tmdb/tv/${target.tmdbId}/season/${target.seasonNumber}")), LocalDate.now())
		)

		else -> listOfNotNull(seriesAirInfo(decode(enhancedApi.get("tmdb/tv/${target.tmdbId}"))))
	}

	suspend fun contentWarnings(target: TmdbTarget): DddEntry =
		decode(enhancedApi.get("ddd/${target.mediaType}/${target.tmdbId}"))

	suspend fun contentWarningTopics(): DddTopicCatalog = decode(enhancedApi.get("ddd/topics"))

	/** The user's DoesTheDogDie trigger topic ids (`ddd-triggers.json`). */
	suspend fun triggers(): Set<Int> =
		triggersFrom(settingsRepository.read(EnhancedSettingsRepository.DDD_TRIGGERS).json)

	suspend fun saveTriggers(triggers: Set<Int>) {
		settingsRepository.edit(EnhancedSettingsRepository.DDD_TRIGGERS) { fields ->
			fields["Triggers"] = JsonArray(triggers.sorted().map { JsonPrimitive(it) })
		}
	}

	/**
	 * Birth/death facts of up to 100 people (Jellyfin person ids), with their age when [itemId] was released.
	 * Keys of the result are dashless lowercase ids.
	 */
	suspend fun peopleInfo(personIds: List<UUID>, itemId: UUID?): Map<String, PersonInfo> {
		val ids = personIds.distinct().take(MAX_PEOPLE).joinToString(",") { it.toString().replace("-", "") }
		if (ids.isEmpty()) return emptyMap()
		val info = decode<PeopleInfo>(enhancedApi.get("people/info", mapOf("ids" to ids, "itemId" to itemId?.toString()?.replace("-", ""))))
		return info.people.mapKeys { it.key.replace("-", "").lowercase() }
	}

	companion object {
		const val MAX_PEOPLE = 100

		/** Distinct provider names, by the region's display priority, then name. */
		fun sortServices(entries: List<TmdbProviderListEntry>, region: String): List<String> = entries
			.filter { it.providerName.isNotBlank() }
			.sortedWith(compareBy<TmdbProviderListEntry> { it.displayPriorities[region] ?: it.displayPriority ?: Int.MAX_VALUE }.thenBy { it.providerName.lowercase() })
			.map { it.providerName }
			.distinct()

		fun triggersFrom(json: JsonObject): Set<Int> =
			(json["Triggers"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.intOrNull }?.toSet().orEmpty()
	}
}

/** `elsewhere.json`: `{Region, Regions, Services, SeasonProviders}`. An empty region means "server default". */
data class ElsewhereSettings(
	val region: String? = null,
	val regions: List<String> = emptyList(),
	val services: List<String> = emptyList(),
) {
	companion object {
		fun from(json: JsonObject) = ElsewhereSettings(
			region = (json["Region"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() },
			regions = (json["Regions"] as? JsonArray)?.strings().orEmpty(),
			services = (json["Services"] as? JsonArray)?.strings().orEmpty(),
		)

		private fun JsonArray.strings() = mapNotNull { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }.filter { it.isNotBlank() }
	}
}
