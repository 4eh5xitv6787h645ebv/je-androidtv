package org.jellyfin.androidtv.enhanced.seerr

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import org.jellyfin.androidtv.auth.repository.UserRepository
import org.jellyfin.androidtv.enhanced.api.EnhancedApi
import org.jellyfin.androidtv.enhanced.data.EnhancedRepository
import org.jellyfin.androidtv.enhanced.data.EnhancedState
import org.jellyfin.androidtv.enhanced.data.SeerrUserStatus
import timber.log.Timber
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Keeps the Seerr account link working without anybody signing in to Seerr.
 *
 * The plugin links a Jellyfin user to the Seerr account that carries their Jellyfin user id. When the admin turned on
 * "Auto import Jellyfin users to Seerr" (`JellyseerrAutoImportUsers`), every Seerr lookup that allows auto-import
 * creates the missing account: that includes `GET jellyseerr/user-status` itself (and every proxied Seerr call); only
 * the admin's read-only `permission-audit` doesn't. So the state load in [EnhancedRepository] already links the user
 * where the server allows it. This class covers what's left:
 * - an unlinked (or unreadable) status gets one more look per user and app session, after the plugin's 60 s
 *   negative cache ([SeerrLink.recheckDelayMs]); blocked, disabled, unreachable and no-user never do
 *   ([SeerrLink.autoRecheck]), and a look still waiting is dropped when a refresh reports one of them;
 * - a Seerr call that says `unlinked` while the state still says linked reloads the state, once per user and session
 *   ([onUnlinkedError]);
 * - administrators can import every Jellyfin user into Seerr ([importUsers], `POST jellyseerr/import-users`);
 * - "Check again" on the About page re-reads the status on request ([recheck]).
 *
 * Whenever the link changes, the whole Jellyfin Enhanced state is reloaded so the Seerr pages and rows appear.
 */
class SeerrLinkManager(
	private val enhancedApi: EnhancedApi,
	private val enhancedRepository: EnhancedRepository,
	private val userRepository: UserRepository,
	private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
	/** Waits out the time before the automatic second look ([SeerrLink.recheckDelayMs]); tests pass their own. */
	private val waitBeforeRecheck: suspend (SeerrLinkKind) -> Unit = { delay(SeerrLink.recheckDelayMs(it)) },
	private val clock: () -> Long = System::currentTimeMillis,
) {
	companion object {
		/** How long "no name" (not in the list, or the list failed) is kept, so the About page doesn't ask every time. */
		const val MISSING_NAME_TTL_MS = 5 * 60_000L
	}

	private val gate = SeerrRecheckGate()
	private var pending: Job? = null
	private var pendingFor: UUID? = null

	/** A Seerr display name, or null for "none found" until [expiresAt] (names themselves never expire). */
	private data class CachedName(val name: String?, val expiresAt: Long)

	/** Server + Seerr user id → display name, for the About page. Seerr ids are per server, so the key has both. */
	private val displayNames = ConcurrentHashMap<String, CachedName>()

	init {
		enhancedRepository.state.onEach(::onState).launchIn(scope)
	}

	private val currentUserId get() = userRepository.currentUser.value?.id

	@Synchronized
	private fun onState(state: EnhancedState) {
		val userId = currentUserId
		// A check scheduled for someone else (the user switched accounts) is dropped
		if (pendingFor != null && pendingFor != userId) cancelPending()
		if (userId == null) return
		val kind = SeerrLink.kind(state)
		// Linked, or a refresh said blocked/unreachable/no-user meanwhile: a scheduled look has nothing left to fix.
		// Off isn't a reason (a state that isn't loaded yet says off too), and the look itself returns early then.
		if (kind != SeerrLinkKind.OFF && !SeerrLink.autoRecheck(kind)) {
			cancelPending()
			return
		}
		if (!SeerrLink.autoRecheck(kind) || !gate.claim(userId, SeerrRecheckGate.Check.AUTO)) return

		Timber.i("Seerr link is %s, checking again once", kind)
		pendingFor = userId
		pending = scope.launch {
			waitBeforeRecheck(kind)
			// No longer pending once it runs: the state the look itself publishes mustn't cancel it halfway
			if (!clearPending(userId)) return@launch
			if (currentUserId == userId) recheck()
		}
	}

	/** Forget the pending look of [userId] as it starts; false when it was cancelled (or replaced) meanwhile. */
	@Synchronized
	private fun clearPending(userId: UUID): Boolean {
		if (pendingFor != userId) return false
		pending = null
		pendingFor = null
		return true
	}

	private fun cancelPending() {
		pending?.cancel()
		pending = null
		pendingFor = null
	}

	/**
	 * Read `user-status` again (which lets the server auto-import the user when it does that) and, when the link
	 * changed, reload the Jellyfin Enhanced state. Returns the fresh kind; a failed read is [SeerrLinkKind.UNKNOWN].
	 */
	suspend fun recheck(): SeerrLinkKind {
		val before = enhancedRepository.current
		if (!before.available || !before.config.jellyseerrEnabled) return SeerrLinkKind.OFF
		val userId = currentUserId ?: return SeerrLink.kind(before)
		val status = try {
			EnhancedApi.json.decodeFromJsonElement<SeerrUserStatus>(enhancedApi.get("jellyseerr/user-status"))
		} catch (err: CancellationException) {
			throw err
		} catch (err: Exception) {
			Timber.w(err, "Seerr user status unavailable")
			return SeerrLinkKind.UNKNOWN
		}
		// Someone else signed in meanwhile: this answer is theirs to ignore
		if (currentUserId != userId) return SeerrLink.kind(enhancedRepository.current)
		val kind = SeerrLink.kind(status)
		if (kind != SeerrLink.kind(before.seerr) || status.canRequestAdvanced != before.seerr.canRequestAdvanced) {
			enhancedRepository.ensureLoaded(force = true)
		}
		return kind
	}

	/**
	 * A Seerr call failed with `unlinked`. When the state still says linked (the account was removed in Seerr since),
	 * reload it, at most once per user and app session, so the pages show the link notice instead of failing again.
	 */
	fun onUnlinkedError() {
		val userId = currentUserId ?: return
		if (!enhancedRepository.current.seerr.linked) return
		if (gate.claim(userId, SeerrRecheckGate.Check.STALE)) enhancedRepository.refreshInBackground()
	}

	/**
	 * Administrators: import every Jellyfin user (except those the admin blocked) into Seerr, then re-read this
	 * user's link. Throws the server's refusal: 403 for non-admins, 429 within 30 s of the last run, 400/502 when Seerr
	 * isn't set up or reachable (texts in [SeerrErrors.serverText]).
	 */
	suspend fun importUsers(): SeerrImportResult {
		val result = EnhancedApi.json.decodeFromJsonElement<SeerrImportResult>(enhancedApi.post("jellyseerr/import-users"))
		displayNames.clear()
		recheck()
		return result
	}

	/**
	 * The signed-in user's Seerr display name, for "Linked as …". Only administrators can read Seerr's user list
	 * (`GET jellyseerr/user`), so it's null for everyone else, and on any failure. A missing name is kept for
	 * [MISSING_NAME_TTL_MS] so the large list isn't fetched on every visit.
	 */
	suspend fun linkedDisplayName(state: EnhancedState): String? {
		val seerrUserId = state.seerr.jellyseerrUserId?.takeIf { state.seerr.linked } ?: return null
		val key = "${enhancedApi.baseUrl}|$seerrUserId"
		val now = clock()
		displayNames[key]?.let { cached -> if (cached.name != null || now < cached.expiresAt) return cached.name }
		if (!state.isAdmin) return null
		val name = try {
			val users = (enhancedApi.get("jellyseerr/user", mapOf("take" to 1000)) as? JsonObject)?.get("results")?.jsonArray.orEmpty()
			val user = users.firstOrNull { (it as? JsonObject)?.string("id") == seerrUserId } as? JsonObject
			SeerrLinkNames.displayName(user)
		} catch (err: CancellationException) {
			throw err
		} catch (err: Exception) {
			Timber.w(err, "Couldn't read the Seerr user list")
			null
		}
		displayNames[key] = CachedName(name, now + MISSING_NAME_TTL_MS)
		return name
	}
}

/** Display names of Seerr users. */
internal object SeerrLinkNames {
	/** Seerr's own `displayName`, else the Jellyfin, plain or Plex user name, else the e-mail address. */
	fun displayName(user: JsonObject?): String? = listOf("displayName", "jellyfinUsername", "username", "plexUsername", "email")
		.firstNotNullOfOrNull { key -> user?.string(key)?.takeIf { it.isNotBlank() } }
}

private fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull
