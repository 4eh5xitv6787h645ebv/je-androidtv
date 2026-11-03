package org.jellyfin.androidtv.enhanced.seerr

import androidx.annotation.StringRes
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.enhanced.api.EnhancedApi
import org.jellyfin.androidtv.enhanced.data.EnhancedState
import org.jellyfin.androidtv.enhanced.data.SeerrUserStatus
import java.util.UUID

/**
 * The signed-in user's Seerr account link, as the app presents it. The plugin links a Jellyfin user to the Seerr
 * account carrying their Jellyfin user id; nobody ever signs in to Seerr from the TV.
 */
enum class SeerrLinkKind {
	/** Seerr isn't set up on the server (or the plugin isn't there). */
	OFF,
	LINKED,

	/** Seerr has no account for this Jellyfin user and the server didn't (or couldn't) create one. */
	UNLINKED,

	/** The administrator excluded this user from Seerr. */
	BLOCKED,
	UNREACHABLE,

	/** The server couldn't tell who is signed in. */
	NO_USER,

	/** The status couldn't be read at all (the call failed). */
	UNKNOWN,
}

/** Status logic of the Seerr link: which kind a `user-status` answer is, and when the app may look again by itself. */
object SeerrLink {
	const val REASON_LINKED = "linked"
	const val REASON_UNLINKED = "unlinked"
	const val REASON_BLOCKED = "blocked"
	const val REASON_UNREACHABLE = "unreachable"
	const val REASON_DISABLED = "disabled"
	const val REASON_NO_USER = "no_user"

	/**
	 * The plugin keeps a failed user lookup for 60 s (`GetJellyseerrUser`'s negative cache), so an automatic second
	 * look at an unlinked user waits a little longer than that; anything sooner would get the cached answer.
	 */
	const val UNLINKED_RECHECK_DELAY_MS = 65_000L

	/** A status call that failed outright (timeout, server error) is looked at again soon. */
	const val UNKNOWN_RECHECK_DELAY_MS = 5_000L

	fun kind(state: EnhancedState): SeerrLinkKind =
		if (!state.available || !state.config.jellyseerrEnabled) SeerrLinkKind.OFF else kind(state.seerr)

	fun kind(status: SeerrUserStatus): SeerrLinkKind = when {
		status.linked -> SeerrLinkKind.LINKED
		status.reason == REASON_DISABLED -> SeerrLinkKind.OFF
		status.reason == REASON_BLOCKED -> SeerrLinkKind.BLOCKED
		status.reason == REASON_UNREACHABLE -> SeerrLinkKind.UNREACHABLE
		status.reason == REASON_NO_USER -> SeerrLinkKind.NO_USER
		status.reason == REASON_UNLINKED -> SeerrLinkKind.UNLINKED
		// Older plugins answer without a reason
		status.active && !status.userFound -> SeerrLinkKind.UNLINKED
		else -> SeerrLinkKind.UNKNOWN
	}

	/**
	 * Whether the app looks at the status again by itself (once per user and app session, see [SeerrRecheckGate]).
	 * Only states the server could still turn into "linked" on its own qualify: an unlinked user (the status read
	 * itself auto-imports when the server allows it) and an unreadable status. Blocked, disabled, unreachable and
	 * no-user are decisions or outages a retry can't fix, so they're never retried automatically.
	 */
	fun autoRecheck(kind: SeerrLinkKind) = kind == SeerrLinkKind.UNLINKED || kind == SeerrLinkKind.UNKNOWN

	fun recheckDelayMs(kind: SeerrLinkKind) =
		if (kind == SeerrLinkKind.UNKNOWN) UNKNOWN_RECHECK_DELAY_MS else UNLINKED_RECHECK_DELAY_MS

	/** Whether "Import Jellyfin users into Seerr" is the main action for this state (administrators only). */
	fun importIsPrimary(kind: SeerrLinkKind, isAdmin: Boolean) =
		isAdmin && (kind == SeerrLinkKind.UNLINKED || kind == SeerrLinkKind.NO_USER)

	/** Whether to offer the import at all (About page): any administrator of a server with Seerr set up. */
	fun importOffered(kind: SeerrLinkKind, isAdmin: Boolean) = isAdmin && kind != SeerrLinkKind.OFF

	/**
	 * Whether a page gated on Seerr (Discover, Requests) is unavailable because of the user's Seerr link rather than
	 * because the page is off; it shows the link notice then instead of "not turned on". [pageEnabled] is the page's
	 * own public-config flag.
	 */
	fun blocksPage(state: EnhancedState, pageEnabled: Boolean): Boolean =
		pageEnabled && kind(state).let { it != SeerrLinkKind.OFF && it != SeerrLinkKind.LINKED }
}

/**
 * Remembers which automatic Seerr link checks already ran, per user, for this app session, so none of them can
 * repeat: each is claimed once and never released. User-initiated checks (a button) don't go through it.
 */
class SeerrRecheckGate {
	enum class Check {
		/** The delayed second look at an unlinked or unreadable status. */
		AUTO,

		/** A state reload after a Seerr call said `unlinked` while the state still said linked. */
		STALE,
	}

	private val claimed = mutableSetOf<Pair<UUID, Check>>()

	/** True the first time [check] is claimed for [userId]; false ever after. */
	@Synchronized
	fun claim(userId: UUID, check: Check): Boolean = claimed.add(userId to check)
}

/** A user-facing text about Seerr: one of ours, or the server's own (made a full sentence). */
sealed interface SeerrText {
	data class Resource(@StringRes val id: Int) : SeerrText
	data class Server(val text: String) : SeerrText
}

/** How to present a Seerr link state or a failed Seerr call: the text, and whether the import is the main action. */
data class SeerrProblem(val text: SeerrText, val kind: SeerrLinkKind?, val offerImport: Boolean = false)

/** Maps the plugin's Seerr error codes and link reasons to TV-appropriate texts. */
object SeerrErrors {
	const val CODE_UNLINKED = "unlinked"
	const val CODE_BLOCKED = "blocked"
	const val CODE_UNREACHABLE = "unreachable"
	const val CODE_PARENTAL_BLOCK = "parental_block"
	const val CODE_NO_REQUEST_PERMISSION = "no_request_permission"
	const val CODE_NO_ISSUE_PERMISSION = "no_issue_permission"
	const val CODE_NO_ISSUE_VIEW_PERMISSION = "no_issue_view_permission"

	/**
	 * The plugin's `unlinked` message. Matched only where a caller kept the message but not the code (the search
	 * rows); it's the proxy's fixed text, never shown as is.
	 */
	const val UNLINKED_SERVER_MESSAGE = "Current Jellyfin user is not linked to a Jellyseerr user."

	/** The plugin's error code, when [err] is a plugin error that carried one. */
	fun code(err: Throwable?): String? = (err as? EnhancedApi.EnhancedApiException)?.code

	/** Whether [err] says the user has no Seerr account. */
	fun isUnlinked(err: Throwable?): Boolean = code(err) == CODE_UNLINKED || isUnlinkedMessage(serverText(err))

	fun isUnlinkedMessage(message: String?) = message?.trim().equals(UNLINKED_SERVER_MESSAGE, ignoreCase = true)

	/** The server's explanation: the `message` field, or a textual `error` (the import and admin routes use that). */
	fun serverText(err: Throwable?): String? {
		val api = err as? EnhancedApi.EnhancedApiException ?: return null
		api.serverMessage?.takeIf { it.isNotBlank() }?.let { return it }
		val error = (api.body as? JsonObject)?.get("error") as? JsonPrimitive ?: return null
		return error.takeIf { it.isString }?.contentOrNull?.takeIf { it.isNotBlank() }
	}

	/** [text] as a full sentence: trimmed, starting with a capital and ending in punctuation. Null when blank. */
	fun sentence(text: String?): String? {
		val trimmed = text?.trim()?.takeIf { it.isNotEmpty() } ?: return null
		val capitalised = trimmed.replaceFirstChar { it.uppercaseChar() }
		return if (capitalised.last() in ".!?…") capitalised else "$capitalised."
	}

	/** The text for a link state; for [SeerrLinkKind.UNLINKED] and [SeerrLinkKind.NO_USER] admins get the import. */
	fun forLink(kind: SeerrLinkKind, isAdmin: Boolean): SeerrProblem {
		val id = when (kind) {
			SeerrLinkKind.OFF -> R.string.je_seerr_link_off
			SeerrLinkKind.LINKED -> R.string.je_seerr_link_linked
			SeerrLinkKind.UNLINKED -> if (isAdmin) R.string.je_seerr_link_unlinked_admin else R.string.je_seerr_link_unlinked
			SeerrLinkKind.BLOCKED -> R.string.je_seerr_link_blocked
			SeerrLinkKind.UNREACHABLE -> R.string.je_seerr_link_unreachable
			SeerrLinkKind.NO_USER -> if (isAdmin) R.string.je_seerr_link_no_user_admin else R.string.je_seerr_link_no_user
			SeerrLinkKind.UNKNOWN -> R.string.je_seerr_link_unknown
		}
		return SeerrProblem(SeerrText.Resource(id), kind, offerImport = SeerrLink.importIsPrimary(kind, isAdmin))
	}

	/**
	 * The text for a failed Seerr call, or null when it isn't a plugin error with anything to say (callers then use
	 * their own fallback). `unlinked` never shows the server's text (a technical line; the web client's version even
	 * asks users to sign in to Seerr, which on a TV they can't and needn't); other codes keep the server's own text,
	 * made a full sentence, with ours as the fallback.
	 */
	fun forError(err: Throwable?, isAdmin: Boolean): SeerrProblem? {
		val api = err as? EnhancedApi.EnhancedApiException ?: return null
		if (isUnlinked(api)) return forLink(SeerrLinkKind.UNLINKED, isAdmin)
		val kind = when (api.code) {
			CODE_BLOCKED -> SeerrLinkKind.BLOCKED
			CODE_UNREACHABLE -> SeerrLinkKind.UNREACHABLE
			else -> null
		}
		sentence(serverText(api))?.let { return SeerrProblem(SeerrText.Server(it), kind) }
		if (kind != null) return forLink(kind, isAdmin)
		val id = when (api.code) {
			CODE_PARENTAL_BLOCK -> R.string.je_seerr_link_parental_block
			CODE_NO_REQUEST_PERMISSION -> R.string.je_seerr_link_no_request_permission
			CODE_NO_ISSUE_PERMISSION, CODE_NO_ISSUE_VIEW_PERMISSION -> R.string.je_seerr_link_no_issue_permission
			else -> return null
		}
		return SeerrProblem(SeerrText.Resource(id), null)
	}
}

/** `POST jellyseerr/import-users` (admin; the server allows one run per 30 s). */
@Serializable
data class SeerrImportResult(
	val success: Boolean = false,
	val usersImported: Int = 0,
	val totalUsers: Int = 0,
	/** One line per user Seerr refused (e-mail collisions and the like). */
	val errors: List<String> = emptyList(),
)
