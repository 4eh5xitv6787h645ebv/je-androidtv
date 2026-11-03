package org.jellyfin.androidtv.enhanced.reviews

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put
import org.jellyfin.androidtv.auth.repository.UserRepository
import org.jellyfin.androidtv.enhanced.api.EnhancedApi

/**
 * JE user reviews (`reviews/{movie|tv}/{key}`, keys `603`, `1399:s1`, `1399:s1:e2`) and TMDB reviews through the
 * plugin's TMDB proxy. Writes are single calls made as the signed-in user.
 */
class ReviewsRepository(
	private val enhancedApi: EnhancedApi,
	private val userRepository: UserRepository,
) {
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

	companion object {
		/** The plugin's limit on review text. */
		const val MAX_CONTENT_LENGTH = 2000
	}
}
