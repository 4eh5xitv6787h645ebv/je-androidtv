package org.jellyfin.androidtv.enhanced.reviews

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put
import org.jellyfin.androidtv.auth.repository.UserRepository
import org.jellyfin.androidtv.enhanced.api.EnhancedApi
import org.jellyfin.androidtv.enhanced.data.EnhancedRepository
import org.jellyfin.androidtv.enhanced.data.EnhancedState
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import java.util.UUID

/**
 * Public-config fields of the reviews feature that [org.jellyfin.androidtv.enhanced.data.EnhancedPublicConfig]
 * doesn't carry. `ReviewTranslationServer` is true when translation is on and the admin set a LibreTranslate URL.
 */
@Serializable
data class ReviewsPublicConfig(
	@SerialName("ReviewTranslationEnabled") val reviewTranslationEnabled: Boolean = false,
	@SerialName("ReviewTranslationServer") val reviewTranslationServer: Boolean = false,
)

/**
 * JE user reviews (`reviews/{movie|tv}/{key}`, keys `603`, `1399:s1`, `1399:s1:e2`), TMDB reviews through the
 * plugin's TMDB proxy and AniList reviews (`anilist/reviews`). Writes are single calls made as the signed-in user.
 */
class ReviewsRepository(
	private val enhancedApi: EnhancedApi,
	private val userRepository: UserRepository,
	private val enhancedRepository: EnhancedRepository,
) {
	private val mutex = Mutex()
	private var configFor: EnhancedState? = null
	private var config: ReviewsPublicConfig? = null
	private val seriesAnilistIds = HashMap<SeriesKey, Int?>()

	/** A series as one user on one server sees it: Jellyfin ids are only unique per server. */
	private data class SeriesKey(val server: String?, val userId: UUID, val seriesId: UUID)

	/** The signed-in user's id, dashless, to recognise their own review. */
	val currentUserKey: String? get() = userRepository.currentUser.value?.id?.toString()?.replace("-", "")

	suspend fun userReviews(mediaType: String, key: String): List<JeReview> =
		EnhancedApi.json.decodeFromJsonElement<JeReviewList>(enhancedApi.get("reviews/$mediaType/$key")).reviews

	/** Create or replace the user's review. [rating] null (or 0) saves a text-only review. */
	suspend fun saveReview(mediaType: String, key: String, content: String, rating: Double?) {
		val body = buildJsonObject {
			put("Content", content.trim())
			put("Rating", rating?.takeIf { it > 0 }?.let(::JsonPrimitive) ?: JsonNull)
		}
		enhancedApi.post("reviews/$mediaType/$key", body)
	}

	suspend fun deleteReview(mediaType: String, key: String) {
		enhancedApi.delete("reviews/$mediaType/$key")
	}

	suspend fun tmdbReviews(mediaType: String, tmdbId: Int, page: Int): TmdbReviewPage =
		EnhancedApi.json.decodeFromJsonElement(enhancedApi.get("tmdb/$mediaType/$tmdbId/reviews", mapOf("page" to page)))

	/** AniList's most helpful reviews of the anime with AniList id [anilistId]. 503 when the admin turned them off. */
	suspend fun anilistReviews(anilistId: Int): AnilistReviewList =
		EnhancedApi.json.decodeFromJsonElement(enhancedApi.get("anilist/reviews", mapOf("anilistId" to anilistId)))

	/**
	 * The AniList id of [item], or for a season or episode without one of its own, of its series (the AniList
	 * metadata plugin only tags shows and movies). Series lookups are cached per server and user.
	 */
	suspend fun anilistId(item: BaseItemDto): Int? {
		anilistIdOf(item.providerIds)?.let { return it }
		if (item.type != BaseItemKind.SEASON && item.type != BaseItemKind.EPISODE) return null
		val seriesId = item.seriesId ?: return null
		val userId = userRepository.currentUser.value?.id ?: return null
		// Pinned, so the lookup is made on the server the key names even if the account changes meanwhile
		val api = enhancedApi.pinned()
		val key = SeriesKey(api.baseUrl, userId, seriesId)
		mutex.withLock {
			if (seriesAnilistIds.containsKey(key)) return seriesAnilistIds[key]
		}
		val series = api.get("/Items/$seriesId", mapOf("userId" to userId.toString())) as? JsonObject
		val ids = (series?.get("ProviderIds") as? JsonObject)?.mapValues { (it.value as? JsonPrimitive)?.contentOrNull }
		val anilistId = anilistIdOf(ids)
		mutex.withLock { seriesAnilistIds[key] = anilistId }
		return anilistId
	}

	/** Our part of the public config, re-read whenever the plugin state is reloaded (user switch, settings save). */
	suspend fun publicConfig(): ReviewsPublicConfig {
		val state = enhancedRepository.ensureLoaded()
		mutex.withLock {
			if (configFor === state) config?.let { return it }
		}
		val loaded = EnhancedApi.json.decodeFromJsonElement<ReviewsPublicConfig>(enhancedApi.get("public-config"))
		mutex.withLock {
			configFor = state
			config = loaded
		}
		return loaded
	}

	/** Whether the reader may offer Translate: the plugin is on, translation is enabled and has a server. */
	suspend fun translationAvailable(): Boolean {
		val state = enhancedRepository.ensureLoaded()
		if (!state.available || !state.config.reviewTranslationEnabled) return false
		return publicConfig().let { it.reviewTranslationEnabled && it.reviewTranslationServer }
	}

	companion object {
		/** The plugin's limit on review text. */
		const val MAX_CONTENT_LENGTH = 2000
	}
}
