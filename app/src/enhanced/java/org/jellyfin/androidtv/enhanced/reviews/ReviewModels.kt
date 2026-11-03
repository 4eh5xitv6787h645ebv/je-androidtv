package org.jellyfin.androidtv.enhanced.reviews

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** `GET reviews/{movie|tv}/{key}`: the JE user reviews this viewer may see. Note the camelCase anonymous object. */
@Serializable
data class JeReviewList(
	val reviews: List<JeReview> = emptyList(),
)

@Serializable
data class JeReview(
	/** Jellyfin user id, dashless. */
	val userId: String = "",
	val userName: String? = null,
	val content: String? = null,
	/** 0.5–5 in 0.5 steps, or null for a text-only review. */
	val rating: Double? = null,
	/** ISO-8601 instants. */
	val createdAt: String? = null,
	val updatedAt: String? = null,
)

/** `GET tmdb/{movie|tv}/{id}/reviews?page=N` (TMDB's own snake_case). */
@Serializable
data class TmdbReviewPage(
	val page: Int = 1,
	@SerialName("total_pages") val totalPages: Int = 0,
	@SerialName("total_results") val totalResults: Int = 0,
	val results: List<TmdbReview> = emptyList(),
)

@Serializable
data class TmdbReview(
	val id: String = "",
	val author: String? = null,
	@SerialName("author_details") val authorDetails: TmdbAuthorDetails? = null,
	val content: String? = null,
	@SerialName("created_at") val createdAt: String? = null,
	@SerialName("updated_at") val updatedAt: String? = null,
)

@Serializable
data class TmdbAuthorDetails(
	val name: String? = null,
	val username: String? = null,
	/** Out of 10. */
	val rating: Double? = null,
)

/** One review as the row and the reader show it, whatever its source. */
data class ReviewEntry(
	val source: Source,
	val author: String,
	/** Stars out of 5 for JE reviews, out of 10 for TMDB ones (see [source]). */
	val rating: Double?,
	/** ISO-8601 date or instant. */
	val date: String?,
	val content: String,
	val isOwn: Boolean = false,
) {
	enum class Source { JELLYFIN, TMDB }

	companion object {
		fun of(review: JeReview, currentUserKey: String?) = ReviewEntry(
			source = Source.JELLYFIN,
			author = review.userName?.takeIf { it.isNotBlank() } ?: review.userId,
			rating = review.rating,
			date = review.updatedAt?.takeIf { it.isNotBlank() } ?: review.createdAt,
			content = review.content.orEmpty().trim(),
			isOwn = currentUserKey != null && sameUser(review.userId, currentUserKey),
		)

		fun of(review: TmdbReview) = ReviewEntry(
			source = Source.TMDB,
			author = review.authorDetails?.name?.takeIf { it.isNotBlank() }
				?: review.author?.takeIf { it.isNotBlank() }
				?: review.authorDetails?.username.orEmpty(),
			rating = review.authorDetails?.rating,
			date = review.createdAt,
			content = cleanReviewText(review.content.orEmpty()),
		)
	}
}

/** Jellyfin ids come dashed or dashless and in either case. */
fun sameUser(a: String, b: String) = a.replace("-", "").equals(b.replace("-", ""), ignoreCase = true)

/** Average stars and number of ratings of [reviews]; null average when nobody rated. */
data class RatingSummary(val average: Double?, val count: Int, val reviewCount: Int)

fun summarize(reviews: List<JeReview>): RatingSummary {
	val ratings = reviews.mapNotNull { it.rating?.takeIf { rating -> rating > 0 } }
	return RatingSummary(ratings.takeIf { it.isNotEmpty() }?.average(), ratings.size, reviews.size)
}

/** Ratings are 0.5–5 in half steps; 0 means "no rating" in the picker. */
const val MAX_RATING = 5.0
const val RATING_STEP = 0.5

/** Move [rating] one half star up or down, staying within 0 (no rating) and [MAX_RATING]. */
fun stepRating(rating: Double, up: Boolean): Double =
	(rating + if (up) RATING_STEP else -RATING_STEP).coerceIn(0.0, MAX_RATING)

/** The star icons for [rating] out of five: full, half and empty counts (rounded to the nearest half). */
data class Stars(val full: Int, val half: Boolean, val empty: Int)

fun starsOf(rating: Double, max: Int = 5): Stars {
	val halves = Math.round(rating.coerceIn(0.0, max.toDouble()) * 2).toInt()
	val full = halves / 2
	val half = halves % 2 == 1
	return Stars(full, half, max - full - if (half) 1 else 0)
}

/** `3.5` → `3.5`, `4.0` → `4`. */
fun formatRating(rating: Double): String =
	if (rating % 1.0 == 0.0) rating.toLong().toString() else "%.1f".format(java.util.Locale.ROOT, rating)

private val htmlTag = Regex("<[^>]+>")
private val markdownEmphasis = Regex("""(\*\*|__|\*)(\S(?:.*?\S)?)\1""")
private val markdownLink = Regex("""\[([^\]]+)]\((https?://[^)]+)\)""")
private val blankLines = Regex("""\n{3,}""")

/** TMDB review bodies carry Markdown and some HTML; keep the words, drop the markup. */
fun cleanReviewText(text: String): String = text
	.replace("\r\n", "\n")
	.replace(Regex("(?i)<br\\s*/?>"), "\n")
	.replace(htmlTag, "")
	.replace(markdownLink, "$1")
	.replace(markdownEmphasis, "$2")
	.replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">")
	.replace(blankLines, "\n\n")
	.trim()
