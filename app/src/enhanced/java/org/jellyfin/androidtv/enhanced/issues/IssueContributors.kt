package org.jellyfin.androidtv.enhanced.issues

import org.jellyfin.androidtv.enhanced.ui.secondaryTextColor
import android.app.AlertDialog
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.enhanced.api.EnhancedApi
import org.jellyfin.androidtv.enhanced.data.EnhancedState
import org.jellyfin.androidtv.enhanced.seerr.SeerrRepository
import org.jellyfin.androidtv.enhanced.seerrdetails.SeerrDates
import org.jellyfin.androidtv.enhanced.ui.details.DetailsAction
import org.jellyfin.androidtv.enhanced.ui.details.DetailsContext
import org.jellyfin.androidtv.enhanced.ui.details.DetailsContributor
import org.jellyfin.androidtv.enhanced.ui.hub.LoadState
import org.jellyfin.androidtv.enhanced.ui.seerr.SeerrDetailsAction
import org.jellyfin.androidtv.enhanced.ui.seerr.SeerrDetailsContext
import org.jellyfin.androidtv.enhanced.ui.seerr.SeerrDetailsContributor
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.button.Button
import org.jellyfin.design.Tokens
import org.jellyfin.sdk.model.api.BaseItemKind
import timber.log.Timber

/** Whether to offer "Report a problem": the plugin's report button setting and a usable Seerr link. */
internal fun EnhancedState.canReportIssues() = seerrUsable && config.jellyseerrShowReportButton

/**
 * "Report a problem" on library movies, shows, seasons and episodes. Seasons and episodes report against their show,
 * with the season/episode prefilled.
 */
class IssueDetailsContributor(
	private val repository: SeerrIssueRepository,
	private val enhancedApi: EnhancedApi,
) : DetailsContributor {
	override suspend fun actions(context: DetailsContext): List<DetailsAction> {
		if (!context.state.canReportIssues()) return emptyList()
		val item = context.item

		val tmdbId: Int
		val seriesId: String?
		val prefill: SeerrIssueScope
		when (item.type) {
			BaseItemKind.MOVIE -> {
				tmdbId = context.tmdbId ?: return emptyList()
				seriesId = null
				prefill = SeerrIssueScope.WHOLE
			}
			BaseItemKind.SERIES -> {
				tmdbId = context.tmdbId ?: return emptyList()
				seriesId = item.id.toString()
				prefill = SeerrIssueScope.WHOLE
			}
			BaseItemKind.SEASON, BaseItemKind.EPISODE -> {
				// A season's or episode's own TMDB id isn't the show's; Seerr knows only the show
				seriesId = item.seriesId?.toString() ?: return emptyList()
				tmdbId = seriesTmdbId(seriesId) ?: return emptyList()
				prefill = if (item.type == BaseItemKind.SEASON) {
					SeerrIssueScope(season = item.indexNumber ?: 0)
				} else {
					val season = item.parentIndexNumber ?: 0
					SeerrIssueScope(season = season, episode = if (season > 0) item.indexNumber ?: 0 else 0)
				}
			}
			else -> return emptyList()
		}

		val title = (if (seriesId != null) item.seriesName else null) ?: item.name.orEmpty()
		val mediaType = if (seriesId != null) SeerrRepository.MEDIA_TV else SeerrRepository.MEDIA_MOVIE
		val label = context.fragment.context?.getString(R.string.je_issues_report) ?: return emptyList()
		return listOf(
			DetailsAction(
				icon = R.drawable.je_ic_issue_report,
				label = label,
				priority = 80,
			) {
				IssueReportFlow(
					fragment = context.fragment,
					repository = repository,
					title = title,
					mediaType = mediaType,
					tmdbId = tmdbId,
					knownMediaId = null,
					episodeSource = seriesId?.let { LibraryEpisodeSource(enhancedApi, it) },
					prefill = prefill,
				).start()
			}
		)
	}

	/** The show's TMDB id, from the library series. Null when it has none or can't be loaded (no button then). */
	private suspend fun seriesTmdbId(seriesId: String): Int? = try {
		val series = enhancedApi.get("/Items/$seriesId") as? JsonObject
		(series?.get("ProviderIds") as? JsonObject)?.entries
			?.firstOrNull { it.key.equals("Tmdb", ignoreCase = true) }
			?.let { (it.value as? JsonPrimitive)?.contentOrNull?.toIntOrNull() }
	} catch (err: CancellationException) {
		throw err
	} catch (err: Exception) {
		Timber.w(err, "Failed to load the series of an episode for issue reporting")
		null
	}
}

/**
 * "Report a problem" and the open-issues indicator on the Seerr details screen. Both need Seerr's media record
 * (`mediaInfo.id`): a title Seerr has never seen has nothing to report against, so neither is shown then.
 */
class IssueSeerrDetailsContributor(
	private val repository: SeerrIssueRepository,
	private val enhancedApi: EnhancedApi,
) : SeerrDetailsContributor {
	override suspend fun actions(context: SeerrDetailsContext): List<SeerrDetailsAction> {
		val mediaId = context.details.mediaInfo?.id ?: return emptyList()
		if (!context.state.canReportIssues()) return emptyList()
		val label = context.fragment.context?.getString(R.string.je_issues_report) ?: return emptyList()
		return listOf(SeerrDetailsAction(label) { reportFlow(context, mediaId).start() })
	}

	override fun section(context: SeerrDetailsContext): (@Composable () -> Unit)? {
		val mediaId = context.details.mediaInfo?.id ?: return null
		if (!context.state.seerrUsable || !context.state.config.jellyseerrShowIssueIndicator) return null
		return { IssueIndicator(context, mediaId) }
	}

	private fun reportFlow(context: SeerrDetailsContext, mediaId: Int): IssueReportFlow {
		val isTv = context.mediaType == SeerrRepository.MEDIA_TV
		val episodeSource = when {
			!isTv -> null
			context.libraryId != null -> LibraryEpisodeSource(enhancedApi, context.libraryId.toString())
			else -> SeerrEpisodeSource(context.details.seasons.associate { it.seasonNumber to it.episodeCount })
		}
		return IssueReportFlow(
			fragment = context.fragment,
			repository = repository,
			title = context.details.displayTitle,
			mediaType = context.mediaType,
			tmdbId = context.tmdbId,
			knownMediaId = mediaId,
			episodeSource = episodeSource,
			onReported = context.reload,
		)
	}

	@Composable
	private fun IssueIndicator(context: SeerrDetailsContext, mediaId: Int) {
		var attempt by remember { mutableIntStateOf(0) }
		var state by remember(context, attempt) { mutableStateOf<LoadState<SeerrIssueRepository.MediaIssues>>(LoadState.Loading) }
		// Keyed on the context: the screen builds a new one on every reload (e.g. after reporting)
		LaunchedEffect(context, attempt) {
			state = try {
				LoadState.Ready(repository.issuesFor(mediaId))
			} catch (err: CancellationException) {
				throw err
			} catch (err: EnhancedApi.EnhancedApiException) {
				Timber.w(err, "Failed to load Seerr issues")
				// Not allowed to view issues: nothing to indicate, reporting stays available
				if (err.status == 403) LoadState.Ready(SeerrIssueRepository.MediaIssues(emptyList(), complete = true))
				else LoadState.Error(err.serverMessage) { attempt++ }
			} catch (err: Exception) {
				Timber.w(err, "Failed to load Seerr issues")
				LoadState.Error(null) { attempt++ }
			}
		}

		when (val current = state) {
			LoadState.Loading -> Unit
			is LoadState.Ready -> {
				val issues = current.value
				if (issues.issues.isEmpty()) return
				val more = if (issues.complete) "" else "+"
				val label = if (issues.open > 0) {
					pluralStringResource(R.plurals.je_issues_open_count, issues.open, "${issues.open}$more")
				} else {
					pluralStringResource(R.plurals.je_issues_resolved_count, issues.issues.size, "${issues.issues.size}$more")
				}
				Button(onClick = { context.fragment.context?.let { showIssues(it, context.details.displayTitle, issues.issues) } }) {
					Text(label)
				}
			}
			is LoadState.Error -> Row(
				horizontalArrangement = Arrangement.spacedBy(12.dp),
				verticalAlignment = Alignment.CenterVertically,
			) {
				Text(current.message ?: stringResource(R.string.je_issues_load_failed), color = secondaryTextColor(), fontSize = 14.sp)
				Button(onClick = current.retry) { Text(stringResource(R.string.je_retry)) }
			}
		}
	}

	/** A read-only list of the title's issues; selecting one shows its whole message. */
	private fun showIssues(context: Context, title: String, issues: List<SeerrIssue>) {
		AlertDialog.Builder(context)
			.setTitle(context.getString(R.string.je_issues_list_title, title))
			.setItems(issues.map { issueSummary(context, it) }.toTypedArray()) { _, which -> showIssue(context, issues[which]) }
			.setNegativeButton(R.string.je_issues_close, null)
			.show()
	}

	private fun showIssue(context: Context, issue: SeerrIssue) {
		val comments = issue.comments.mapNotNull { comment ->
			val message = comment.message?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
			val meta = listOfNotNull(comment.user?.label, SeerrDates.format(comment.createdAt)).joinToString(" · ")
			if (meta.isEmpty()) message else "$meta\n$message"
		}
		AlertDialog.Builder(context)
			.setTitle(issueHeadline(context, issue))
			.setMessage(comments.joinToString("\n\n").ifEmpty { context.getString(R.string.je_issues_no_message) })
			.setPositiveButton(R.string.je_issues_close, null)
			.show()
	}

	private fun issueHeadline(context: Context, issue: SeerrIssue): String = listOfNotNull(
		"#${issue.id}",
		context.getString(issue.type.label),
		context.getString(if (issue.isOpen) R.string.je_issues_status_open else R.string.je_issues_status_resolved),
		when {
			issue.problemSeason <= 0 -> null
			issue.problemEpisode <= 0 -> context.getString(R.string.je_issues_season, issue.problemSeason)
			else -> context.getString(R.string.je_issues_season_episode, issue.problemSeason, issue.problemEpisode)
		},
	).joinToString(" · ")

	private fun issueSummary(context: Context, issue: SeerrIssue): String {
		val meta = listOfNotNull(issue.createdBy?.label, SeerrDates.format(issue.createdAt)).joinToString(" · ")
		val message = issue.message?.let { if (it.length > 120) it.take(119) + "…" else it }
		return listOfNotNull(issueHeadline(context, issue), meta.ifEmpty { null }, message).joinToString("\n")
	}
}
