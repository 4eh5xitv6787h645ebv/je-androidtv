package org.jellyfin.androidtv.enhanced.maintenance

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * The maintenance fields of `GET /JellyfinEnhanced/public-config`. The plugin reports the live state there: a
 * scheduled or timed window is "enabled" without the admin's toggle, with its own message and end time.
 */
@Serializable
data class MaintenanceInfo(
	@SerialName("MaintenanceModeEnabled") val enabled: Boolean = false,
	/** May contain `{countdown}` and `{ends_at}` tokens. */
	@SerialName("MaintenanceModeMessage") val message: String? = null,
	/** UTC ISO timestamp, or absent while the window is open-ended. */
	@SerialName("MaintenanceModeEndsAt") val endsAt: String? = null,
	/** `none`, `disable_accounts`, `disable_remote` or `both`. */
	@SerialName("MaintenanceModeAction") val action: String? = null,
	/** `all` or `selected`; the plugin never sends who is selected. */
	@SerialName("MaintenanceModeAffectedUsers") val affectedUsers: String? = null,
)

/** A maintenance window to tell this user about. */
data class MaintenanceNotice(
	/** The server's message, tokens unresolved ([MaintenanceLogic.formatMessage]); null for the default text. */
	val message: String?,
	val endsAt: Instant?,
	/**
	 * The window doesn't lock this user out: an administrator while accounts or remote access are being disabled
	 * (the plugin never disables administrators). They get the banner, worded as "for other users", but no dialog.
	 */
	val adminOnly: Boolean,
	/** Only the users the admin picked are locked out, and the plugin doesn't say who: "may affect your account". */
	val selectedUsersOnly: Boolean,
	/** Identifies the window, for remembering that its dialog was seen ([MaintenanceLogic.windowKey]). */
	val windowKey: String,
)

/** What to do about the app-start dialog, from [MaintenanceLogic.dialogDecision]. */
enum class MaintenanceDialogDecision {
	/** Show it, and remember the window once it is dismissed. */
	SHOW,

	/** Nothing to show (no window, already seen, or banner only). */
	NONE,

	/** No window any more: forget the remembered one, so the next window shows its dialog even with the same text. */
	FORGET,
}

object MaintenanceLogic {
	const val ACTION_NONE = "none"
	const val AFFECTED_SELECTED = "selected"

	private val countdownToken = Regex("\\{countdown\\}", RegexOption.IGNORE_CASE)
	private val endsAtToken = Regex("\\{ends_at\\}", RegexOption.IGNORE_CASE)

	/**
	 * The end of the window, or null when open-ended or unreadable. The plugin sends UTC; a timestamp without an
	 * offset is read as UTC too.
	 */
	fun endsAt(info: MaintenanceInfo): Instant? {
		val value = info.endsAt?.trim()?.takeIf { it.isNotEmpty() } ?: return null
		return runCatching { Instant.parse(value) }.getOrNull()
			?: runCatching { LocalDateTime.parse(value).toInstant(ZoneOffset.UTC) }.getOrNull()
	}

	/**
	 * The notice for the signed-in user, or null when there is none: maintenance is off, or its end time has passed
	 * (the plugin turns it off on its next check, like the web banner hides then). Every user is told about a window
	 * that disables nothing (`none`, such as the scheduled "library is updating" window); otherwise administrators,
	 * whom the plugin never disables, get the banner only ([MaintenanceNotice.adminOnly]). Non-administrators are
	 * told even when only selected users are affected, since nothing says they aren't among them.
	 */
	fun notice(info: MaintenanceInfo, isAdmin: Boolean, now: Instant): MaintenanceNotice? {
		if (!info.enabled) return null
		val endsAt = endsAt(info)
		if (endsAt != null && !endsAt.isAfter(now)) return null
		// The plugin's default action is disable_accounts
		val disablesNothing = info.action?.trim().equals(ACTION_NONE, ignoreCase = true)
		val selected = info.affectedUsers?.trim().equals(AFFECTED_SELECTED, ignoreCase = true)
		val message = info.message?.trim()?.takeIf { it.isNotEmpty() }
		return MaintenanceNotice(
			message = message,
			endsAt = endsAt,
			adminOnly = isAdmin && !disablesNothing,
			selectedUsersOnly = selected && !disablesNothing,
			windowKey = windowKey(endsAt, message),
		)
	}

	/**
	 * The plugin doesn't publish when a window started, so a window is known by its end time, or, while open-ended,
	 * by its message. An open-ended window re-enabled later with the same message shows its dialog again only if the
	 * app saw maintenance off in between ([MaintenanceDialogDecision.FORGET]).
	 */
	fun windowKey(endsAt: Instant?, message: String?): String =
		if (endsAt != null) "ends:${endsAt.epochSecond}" else "open:${Integer.toHexString(message.orEmpty().hashCode())}"

	/** Whether to show the app-start dialog for [notice], given the window whose dialog was last seen. */
	fun dialogDecision(notice: MaintenanceNotice?, seenWindow: String?): MaintenanceDialogDecision = when {
		notice == null -> if (seenWindow != null) MaintenanceDialogDecision.FORGET else MaintenanceDialogDecision.NONE
		notice.adminOnly -> MaintenanceDialogDecision.NONE
		notice.windowKey == seenWindow -> MaintenanceDialogDecision.NONE
		else -> MaintenanceDialogDecision.SHOW
	}

	/** Time left as the plugin writes it: "1h 05m", "12m"; minutes round up so the last one never reads "0m". */
	fun countdown(endsAt: Instant, now: Instant): String {
		val seconds = Duration.between(now, endsAt).seconds.coerceAtLeast(0)
		val minutes = (seconds + 59) / 60
		val hours = minutes / 60
		return if (hours > 0) "%dh %02dm".format(Locale.ROOT, hours, minutes % 60) else "${minutes}m"
	}

	/**
	 * The message with its `{countdown}` and `{ends_at}` tokens replaced, like the plugin's `FormatMessage` (without
	 * its appended "Time remaining", the end time is shown on its own line). Null when there is no message.
	 */
	fun formatMessage(message: String?, endsAt: Instant?, now: Instant, endsAtText: String?): String? {
		val text = message?.trim()?.takeIf { it.isNotEmpty() } ?: return null
		val resolved = if (endsAt == null) {
			text.replace(countdownToken, "").replace(endsAtToken, "")
		} else {
			text.replace(countdownToken, countdown(endsAt, now)).replace(endsAtToken, endsAtText.orEmpty())
		}
		return resolved.trim().ifEmpty { null }
	}

	/**
	 * When maintenance ends, for "Back around …": the local time ("9:30 PM") on the same day, with the weekday
	 * ("Tue 9:30 PM") within a week, otherwise with the date.
	 */
	fun endTime(endsAt: Instant, now: Instant, zone: ZoneId, locale: Locale): String {
		val end = endsAt.atZone(zone)
		val today = now.atZone(zone).toLocalDate()
		val time = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale).format(end)
		val days = ChronoUnit.DAYS.between(today, end.toLocalDate())
		return when {
			days == 0L -> time
			days in 1..6 -> "${DateTimeFormatter.ofPattern("EEE", locale).format(end)} $time"
			else -> DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(locale).format(end)
		}
	}

	/** The player's fragments live in these packages; the dialog never shows over them. */
	fun isPlaybackScreen(className: String): Boolean =
		className.startsWith("org.jellyfin.androidtv.ui.playback.") || className.startsWith("org.jellyfin.androidtv.ui.player.")
}
