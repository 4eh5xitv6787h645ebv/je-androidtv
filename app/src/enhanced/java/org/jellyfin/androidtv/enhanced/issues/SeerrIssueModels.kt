package org.jellyfin.androidtv.enhanced.issues

import androidx.annotation.StringRes
import kotlinx.serialization.Serializable
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.enhanced.seerrdetails.SeerrUserRef

/** Seerr's issue types, as `issueType` in the API. */
enum class SeerrIssueType(val value: Int, @StringRes val label: Int) {
	VIDEO(1, R.string.je_issues_type_video),
	AUDIO(2, R.string.je_issues_type_audio),
	SUBTITLES(3, R.string.je_issues_type_subtitles),
	OTHER(4, R.string.je_issues_type_other);

	companion object {
		fun of(value: Int?) = entries.firstOrNull { it.value == value } ?: OTHER
	}
}

/** Seerr's issue status. */
object SeerrIssueStatus {
	const val OPEN = 1
	const val RESOLVED = 2
}

/** The media an issue is attached to. [id] is Seerr's internal media id (`mediaInfo.id`), [tmdbId] the TMDB id. */
@Serializable
data class SeerrIssueMedia(
	val id: Int? = null,
	val tmdbId: Int? = null,
	val mediaType: String? = null,
)

@Serializable
data class SeerrIssueComment(
	val id: Int? = null,
	val message: String? = null,
	val createdAt: String? = null,
	val user: SeerrUserRef? = null,
)

/** A Seerr issue. [id] is the issue id. Season and episode 0 mean "the whole show / season". */
@Serializable
data class SeerrIssue(
	val id: Int = 0,
	val issueType: Int? = null,
	val status: Int? = null,
	val problemSeason: Int = 0,
	val problemEpisode: Int = 0,
	val createdAt: String? = null,
	val updatedAt: String? = null,
	val media: SeerrIssueMedia? = null,
	val createdBy: SeerrUserRef? = null,
	val comments: List<SeerrIssueComment> = emptyList(),
) {
	val type get() = SeerrIssueType.of(issueType)
	val isOpen get() = status == SeerrIssueStatus.OPEN

	/** Seerr stores the reporter's message as the first comment. */
	val message get() = comments.firstOrNull()?.message?.takeIf { it.isNotBlank() }
}

@Serializable
data class SeerrIssuePageInfo(
	val pages: Int = 0,
	val pageSize: Int = 0,
	val results: Int = 0,
	val page: Int = 1,
)

/** `GET jellyseerr/issue`. */
@Serializable
data class SeerrIssuePage(
	val pageInfo: SeerrIssuePageInfo = SeerrIssuePageInfo(),
	val results: List<SeerrIssue> = emptyList(),
)

/** Which part of a title a report is about. 0 means "all" at that level; movies always use 0/0. */
data class SeerrIssueScope(val season: Int = 0, val episode: Int = 0) {
	companion object {
		val WHOLE = SeerrIssueScope()
	}
}
