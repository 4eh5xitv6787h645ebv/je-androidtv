package org.jellyfin.androidtv.enhanced.reviews

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.jellyfin.androidtv.enhanced.metadata.displayMessage
import timber.log.Timber

/**
 * The reviews of one library title. [key] is the JE review key (`603`, `1399:s1`, `1399:s1:e2`); [tmdbId] the
 * movie's or show's TMDB id for TMDB reviews, which exist for whole titles only. [anilistId] is set when AniList
 * reviews are on and the title (or its series) has an AniList id; a title with only an AniList id has an empty
 * [key], 0 [tmdbId] and neither JE nor TMDB reviews.
 */
data class ReviewTarget(
	val mediaType: String,
	val key: String,
	val tmdbId: Int,
	val title: String,
	val includeUser: Boolean,
	val includeTmdb: Boolean,
	val anilistId: Int? = null,
)

/** Loaded reviews: JE reviews first (yours, then newest), then AniList's, then TMDB's. A part that failed has its error set. */
data class ReviewsData(
	val summary: RatingSummary? = null,
	val own: JeReview? = null,
	val entries: List<ReviewEntry> = emptyList(),
	val tmdbPage: Int = 0,
	val tmdbPages: Int = 0,
	val userError: Failure? = null,
	val tmdbError: Failure? = null,
	val anilistError: Failure? = null,
) {
	/** A failed part, with the server's message when there was one. */
	data class Failure(val message: String?)

	val hasMoreTmdb get() = tmdbPage in 1 until tmdbPages

	/** The first failed part, for the row's retry card and the reader's error page. */
	val firstError get() = userError ?: tmdbError ?: anilistError
}

/**
 * JE reviews (own first, then newest first), then AniList reviews in AniList's (most helpful first) order, then TMDB
 * reviews in TMDB's order. TMDB goes last because it is the only paged source: later pages append at the end.
 */
fun buildEntries(
	user: List<JeReview>,
	tmdb: List<TmdbReview>,
	currentUserKey: String?,
	anilist: List<AnilistReview> = emptyList(),
): List<ReviewEntry> {
	val jellyfin = user.map { ReviewEntry.of(it, currentUserKey) }
		.sortedWith(compareByDescending<ReviewEntry> { it.isOwn }.thenByDescending { it.date.orEmpty() })
	val anilistEntries = anilist.map(ReviewEntry::of).filter { it.content.isNotBlank() || it.summary != null }
	return jellyfin + anilistEntries + tmdb.map(ReviewEntry::of).filter { it.content.isNotBlank() }
}

private suspend fun <T> part(name: String, block: suspend () -> T): Result<T> = try {
	Result.success(block())
} catch (err: CancellationException) {
	throw err
} catch (err: Exception) {
	Timber.w(err, "Failed to load %s reviews", name)
	Result.failure(err)
}

/** Load the first page of everything [target] asks for; each source fails on its own. */
suspend fun ReviewsRepository.load(target: ReviewTarget): ReviewsData = coroutineScope {
	val user = if (target.includeUser) async { part("user") { userReviews(target.mediaType, target.key) } } else null
	val tmdb = if (target.includeTmdb) async { part("TMDB") { tmdbReviews(target.mediaType, target.tmdbId, 1) } } else null
	val anilist = target.anilistId?.let { id -> async { part("AniList") { anilistReviews(id) } } }
	val userResult = user?.await()
	val tmdbResult = tmdb?.await()
	val anilistResult = anilist?.await()
	val userReviews = userResult?.getOrNull().orEmpty()
	val tmdbPage = tmdbResult?.getOrNull()

	ReviewsData(
		summary = if (userResult?.isSuccess == true) summarize(userReviews) else null,
		own = currentUserKey?.let { me -> userReviews.firstOrNull { sameUser(it.userId, me) } },
		entries = buildEntries(userReviews, tmdbPage?.results.orEmpty(), currentUserKey, anilistResult?.getOrNull()?.reviews.orEmpty()),
		tmdbPage = tmdbPage?.page ?: 0,
		tmdbPages = tmdbPage?.totalPages ?: 0,
		userError = userResult?.exceptionOrNull()?.let { ReviewsData.Failure(it.displayMessage()) },
		tmdbError = tmdbResult?.exceptionOrNull()?.let { ReviewsData.Failure(it.displayMessage()) },
		anilistError = anilistResult?.exceptionOrNull()?.let { ReviewsData.Failure(it.displayMessage()) },
	)
}

/** The next page of TMDB reviews appended to [data]. */
suspend fun ReviewsRepository.loadMoreTmdb(target: ReviewTarget, data: ReviewsData): ReviewsData {
	if (!data.hasMoreTmdb) return data
	val page = tmdbReviews(target.mediaType, target.tmdbId, data.tmdbPage + 1)
	val known = data.entries.toSet()
	return data.copy(
		entries = data.entries + page.results.map(ReviewEntry::of).filter { it.content.isNotBlank() && it !in known },
		tmdbPage = page.page,
		tmdbPages = page.totalPages,
	)
}
