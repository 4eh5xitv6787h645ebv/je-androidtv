package org.jellyfin.androidtv.enhanced.reviews

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.fragment.app.Fragment
import androidx.leanback.widget.Row
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.enhanced.data.SpoilerProtection
import org.jellyfin.androidtv.enhanced.metadata.MetadataRepository
import org.jellyfin.androidtv.enhanced.metadata.TmdbTarget
import org.jellyfin.androidtv.enhanced.metadata.showComposeDialog
import org.jellyfin.androidtv.enhanced.ui.details.DetailsAction
import org.jellyfin.androidtv.enhanced.ui.details.DetailsContext
import org.jellyfin.androidtv.enhanced.ui.details.DetailsContributor
import org.jellyfin.androidtv.enhanced.ui.details.EnhancedComposeRow
import org.jellyfin.androidtv.ui.navigation.NavigationRepository
import org.jellyfin.sdk.model.api.BaseItemKind
import timber.log.Timber
import java.util.WeakHashMap

/**
 * JE user reviews, TMDB reviews and AniList reviews on the details screen: a "Rate" button (half-star picker plus optional text) and
 * a "Reviews" row whose cards open [ReviewsReaderFragment]. The row is withheld when the title (or, for seasons and
 * episodes, its series) is Spoiler Guarded and reviews are hidden for this user, or when that can't be ruled out.
 */
class ReviewsDetailsContributor(
	private val repository: ReviewsRepository,
	private val metadataRepository: MetadataRepository,
	private val navigationRepository: NavigationRepository,
	private val spoilerProtection: SpoilerProtection,
) : DetailsContributor {
	companion object {
		private const val ACTION_PRIORITY = 55
		private val REVIEW_TYPES = setOf(BaseItemKind.MOVIE, BaseItemKind.SERIES, BaseItemKind.SEASON, BaseItemKind.EPISODE)
		private val TMDB_REVIEW_TYPES = setOf(BaseItemKind.MOVIE, BaseItemKind.SERIES)
	}

	/** Bumped after the user saves or deletes their review, so that screen's row reloads. Main thread only. */
	private val revisions = WeakHashMap<Fragment, MutableStateFlow<Int>>()

	private fun revision(fragment: Fragment) = revisions.getOrPut(fragment) { MutableStateFlow(0) }

	private suspend fun target(context: DetailsContext): ReviewTarget? {
		val state = context.state
		val config = state.config
		val item = context.item
		if (!state.available || item.type !in REVIEW_TYPES) return null
		val wantUser = config.showUserReviews
		val wantTmdb = config.showReviews && config.tmdbEnabled && item.type in TMDB_REVIEW_TYPES
		val anilistId = if (config.showAnilistReviews) {
			runCatching { repository.anilistId(item) }
				.onFailure { Timber.w(it, "Failed to resolve the AniList id for reviews") }
				.getOrNull()
		} else null
		if (!wantUser && !wantTmdb && anilistId == null) return null
		val tmdb = if (wantUser || wantTmdb) {
			runCatching { metadataRepository.tmdbTarget(item) }
				.onFailure { Timber.w(it, "Failed to resolve the TMDB id for reviews") }
				.getOrNull()
				?.takeIf { it.mediaType != TmdbTarget.PERSON }
		} else null
		// An anime with only an AniList id still gets its AniList reviews, but nothing keyed by TMDB id
		if (tmdb == null && anilistId == null) return null

		val title = when (item.type) {
			BaseItemKind.SEASON, BaseItemKind.EPISODE -> listOfNotNull(item.seriesName, item.name).joinToString(" · ")
			else -> item.name.orEmpty()
		}
		return ReviewTarget(
			mediaType = tmdb?.mediaType.orEmpty(),
			key = tmdb?.reviewKey.orEmpty(),
			tmdbId = tmdb?.tmdbId ?: 0,
			title = title,
			includeUser = wantUser && tmdb != null,
			includeTmdb = wantTmdb && tmdb != null,
			anilistId = anilistId,
		)
	}

	override suspend fun actions(context: DetailsContext): List<DetailsAction> {
		val target = target(context)?.takeIf { it.includeUser } ?: return emptyList()
		val fragment = context.fragment
		val me = repository.currentUserKey
		val own = me?.let { key ->
			runCatching { repository.userReviews(target.mediaType, target.key) }.getOrNull()?.firstOrNull { sameUser(it.userId, key) }
		}
		val label = own?.rating?.takeIf { it > 0 }?.let { fragment.getString(R.string.je_reviews_rated, formatRating(it)) }
			?: fragment.getString(R.string.je_reviews_rate)

		// The button label ("Rated 3.5") is built once with the screen, so a save reloads the screen
		return listOf(DetailsAction(R.drawable.je_ic_reviews_star, label, ACTION_PRIORITY) { openRateDialog(fragment, target, context.reload) })
	}

	private fun openRateDialog(fragment: Fragment, target: ReviewTarget, afterChange: (() -> Unit)? = null) {
		val revision = revision(fragment)
		showComposeDialog(fragment) { dismiss ->
			RateDialog(
				title = target.title,
				target = target,
				repository = repository,
				onChanged = {
					revision.value++
					afterChange?.invoke()
				},
				dismiss = dismiss,
			)
		}
	}

	override suspend fun rows(context: DetailsContext): List<Row> {
		val state = context.state
		if (spoilerProtection.effectiveFor(context.item).hideReviews) return emptyList()
		val target = target(context) ?: return emptyList()
		val initial = repository.load(target)
		val hasContent = initial.entries.isNotEmpty() || (initial.summary?.count ?: 0) > 0 || initial.firstError != null
		if (!hasContent) return emptyList()

		val fragment = context.fragment
		val config = runCatching { metadataRepository.publicConfig() }.getOrNull()
		val expanded = state.userSettings.reviewsExpandedByDefault ?: config?.reviewsExpandedByDefault ?: false
		val revision = revision(fragment)

		// Outside the composition: leanback rebinds the row when it scrolls back into view
		val dataState = mutableStateOf(initial)

		return listOf(EnhancedComposeRow(fragment.getString(R.string.je_reviews_title)) {
			var data by dataState
			val scope = rememberCoroutineScope()
			val currentRevision by revision.collectAsState()
			LaunchedEffect(currentRevision) {
				if (currentRevision > 0) data = repository.load(target)
			}

			ReviewsRowContent(
				data = data,
				expanded = expanded,
				onWrite = if (target.includeUser) ({ openRateDialog(fragment, target) }) else null,
				onOpen = { index -> navigationRepository.navigate(ReviewsReaderFragment.destination(target, index)) },
				onRetry = { scope.launch { data = repository.load(target) } },
			)
		})
	}
}
