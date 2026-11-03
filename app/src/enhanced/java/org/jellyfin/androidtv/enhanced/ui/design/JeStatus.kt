package org.jellyfin.androidtv.enhanced.ui.design

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.enhanced.seerr.SeerrMediaStatus
import org.jellyfin.design.Tokens

/**
 * The one status palette (spec §1.4), used by [StatusBadge] and [StatusRibbon] everywhere: requests, Seerr cards,
 * seasons, calendar, issues, the download queue. Containers are the 600/700 steps of upstream's tokens so the content
 * colour passes AA at 12 sp bold. [labelRes] is the default label; the mapping functions in [JeStatuses] pick a more
 * specific one ("Blocklisted", "Downloading"…) where the source has it.
 */
enum class JeStatus(val container: Color, val content: Color, @StringRes val labelRes: Int, val showsIcon: Boolean = false) {
	Pending(Tokens.Color.colorYellow300, Tokens.Color.colorGrey900, R.string.je_design_status_pending),
	Approved(Tokens.Color.colorBlue600, Tokens.Color.colorWhite, R.string.je_design_status_approved),
	Processing(Tokens.Color.colorPurple600, Tokens.Color.colorWhite, R.string.je_design_status_processing),
	ComingSoon(Tokens.Color.colorCyan700, Tokens.Color.colorWhite, R.string.je_design_status_coming_soon),
	Available(Tokens.Color.colorGreen600, Tokens.Color.colorWhite, R.string.je_design_status_available),
	PartlyAvailable(Tokens.Color.colorGreen700, Tokens.Color.colorWhite, R.string.je_design_status_partly_available),
	Declined(Tokens.Color.colorRed600, Tokens.Color.colorWhite, R.string.je_design_status_declined),
	Warning(Tokens.Color.colorOrange600, Tokens.Color.colorWhite, R.string.je_design_status_warning, showsIcon = true),
	Neutral(Tokens.Color.colorGrey700, Tokens.Color.colorGrey100, R.string.je_design_status_unknown),
}

/**
 * What a [StatusBadge] shows: the palette entry, and either a literal [text] (a scope tag, a server-provided label)
 * or a string resource ([labelRes], the status's default unless a mapping chose a more specific one).
 */
@Immutable
data class StatusBadgeSpec(
	val status: JeStatus,
	val text: String? = null,
	@StringRes val labelRes: Int = status.labelRes,
)

/**
 * Maps the statuses the features receive (Seerr media/request/issue codes, the plugin's text labels, *arr queue
 * states) to [StatusBadgeSpec]s, so every screen colours and names a status the same way.
 */
object JeStatuses {
	/**
	 * A Seerr media status ([SeerrMediaStatus]) for a card or a details header; null when there is nothing to show
	 * (unknown / never requested), so cards stay clean. Pass the effective status (`SeerrMediaInfo.effectiveStatus`).
	 */
	fun seerrMedia(status: Int?): StatusBadgeSpec? = when (status) {
		SeerrMediaStatus.PENDING -> StatusBadgeSpec(JeStatus.Pending, labelRes = R.string.je_design_status_requested)
		SeerrMediaStatus.PROCESSING -> StatusBadgeSpec(JeStatus.Processing)
		SeerrMediaStatus.PARTIALLY_AVAILABLE -> StatusBadgeSpec(JeStatus.PartlyAvailable)
		SeerrMediaStatus.AVAILABLE -> StatusBadgeSpec(JeStatus.Available)
		SeerrMediaStatus.BLOCKLISTED -> StatusBadgeSpec(JeStatus.Declined, labelRes = R.string.je_design_status_blocklisted)
		SeerrMediaStatus.DELETED -> StatusBadgeSpec(JeStatus.Neutral, labelRes = R.string.je_design_status_deleted)
		else -> null
	}

	/** A season's Seerr media status: like [seerrMedia], but "Not requested" instead of nothing. */
	fun seerrSeason(status: Int?): StatusBadgeSpec =
		seerrMedia(status) ?: StatusBadgeSpec(JeStatus.Neutral, labelRes = R.string.je_design_status_not_requested)

	/** Seerr's request status (`MediaRequestStatus`: 1 pending, 2 approved, 3 declined, 4 failed, 5 completed). */
	fun seerrRequest(status: Int?): StatusBadgeSpec = when (status) {
		1 -> StatusBadgeSpec(JeStatus.Pending)
		2 -> StatusBadgeSpec(JeStatus.Approved)
		3 -> StatusBadgeSpec(JeStatus.Declined)
		4 -> StatusBadgeSpec(JeStatus.Declined, labelRes = R.string.je_design_status_failed)
		5 -> StatusBadgeSpec(JeStatus.Available, labelRes = R.string.je_design_status_completed)
		else -> StatusBadgeSpec(JeStatus.Neutral)
	}

	/** Seerr's issue status (1 open, 2 resolved). */
	fun seerrIssue(status: Int?): StatusBadgeSpec = when (status) {
		1 -> StatusBadgeSpec(JeStatus.Warning, labelRes = R.string.je_design_status_open)
		2 -> StatusBadgeSpec(JeStatus.Available, labelRes = R.string.je_design_status_resolved)
		else -> StatusBadgeSpec(JeStatus.Neutral)
	}

	/**
	 * A textual status: the plugin's request labels (`Pending`, `Partially Available`…), the requests screen's chip
	 * names (`PARTIALLY_AVAILABLE`, `COMING_SOON`, `ORPHANED`…) and the calendar's (`Downloaded`, `Monitored`). Case,
	 * spaces, dashes and underscores are ignored. Unknown values fall back to [JeStatus.Neutral] with the
	 * text as is, so a new server label still shows rather than vanishing.
	 */
	fun label(text: String?): StatusBadgeSpec {
		val key = normalize(text)
		return when (key) {
			"pending", "pendingapproval" -> StatusBadgeSpec(JeStatus.Pending)
			"approved" -> StatusBadgeSpec(JeStatus.Approved)
			"processing" -> StatusBadgeSpec(JeStatus.Processing)
			"requested" -> StatusBadgeSpec(JeStatus.Processing, labelRes = R.string.je_design_status_requested)
			"comingsoon" -> StatusBadgeSpec(JeStatus.ComingSoon)
			"monitored" -> StatusBadgeSpec(JeStatus.ComingSoon, labelRes = R.string.je_design_status_monitored)
			"available" -> StatusBadgeSpec(JeStatus.Available)
			"downloaded" -> StatusBadgeSpec(JeStatus.Available, labelRes = R.string.je_design_status_downloaded)
			"completed" -> StatusBadgeSpec(JeStatus.Available, labelRes = R.string.je_design_status_completed)
			"partiallyavailable", "partlyavailable", "partial" -> StatusBadgeSpec(JeStatus.PartlyAvailable)
			"declined" -> StatusBadgeSpec(JeStatus.Declined)
			"blocklisted", "blacklisted" -> StatusBadgeSpec(JeStatus.Declined, labelRes = R.string.je_design_status_blocklisted)
			"failed" -> StatusBadgeSpec(JeStatus.Declined, labelRes = R.string.je_design_status_failed)
			"orphaned" -> StatusBadgeSpec(JeStatus.Warning, labelRes = R.string.je_design_status_orphaned)
			"stalled" -> StatusBadgeSpec(JeStatus.Warning, labelRes = R.string.je_design_status_stalled)
			"deleted" -> StatusBadgeSpec(JeStatus.Neutral, labelRes = R.string.je_design_status_deleted)
			"notrequested" -> StatusBadgeSpec(JeStatus.Neutral, labelRes = R.string.je_design_status_not_requested)
			"", "unknown" -> StatusBadgeSpec(JeStatus.Neutral)
			else -> StatusBadgeSpec(JeStatus.Neutral, text = text?.trim())
		}
	}

	/** An *arr download-queue state (`downloading`, `queued`, `paused`, `importing`, `warning`, `failed`…). */
	fun queue(status: String?): StatusBadgeSpec = when (normalize(status)) {
		"downloading" -> StatusBadgeSpec(JeStatus.Processing, labelRes = R.string.je_design_status_downloading)
		"importing", "importpending" -> StatusBadgeSpec(JeStatus.Processing, labelRes = R.string.je_design_status_importing)
		"queued" -> StatusBadgeSpec(JeStatus.Neutral, labelRes = R.string.je_design_status_queued)
		"paused" -> StatusBadgeSpec(JeStatus.Warning, labelRes = R.string.je_design_status_paused)
		"delay", "delayed" -> StatusBadgeSpec(JeStatus.Warning, labelRes = R.string.je_design_status_delayed)
		"warning" -> StatusBadgeSpec(JeStatus.Warning)
		"completed", "imported" -> StatusBadgeSpec(JeStatus.Available, labelRes = R.string.je_design_status_completed)
		"failed", "downloadclientunavailable" -> StatusBadgeSpec(JeStatus.Declined, labelRes = R.string.je_design_status_failed)
		"", "unknown" -> StatusBadgeSpec(JeStatus.Neutral)
		else -> StatusBadgeSpec(JeStatus.Neutral, text = status?.trim())
	}

	private fun normalize(text: String?): String =
		text.orEmpty().lowercase().filterNot { it == ' ' || it == '_' || it == '-' }
}
