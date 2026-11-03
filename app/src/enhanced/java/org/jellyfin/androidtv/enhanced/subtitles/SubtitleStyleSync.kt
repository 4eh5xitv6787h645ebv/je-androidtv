package org.jellyfin.androidtv.enhanced.subtitles

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.jsonObject
import org.jellyfin.androidtv.auth.repository.Session
import org.jellyfin.androidtv.auth.repository.SessionRepository
import org.jellyfin.androidtv.auth.repository.UserRepository
import org.jellyfin.androidtv.enhanced.api.EnhancedApi
import org.jellyfin.androidtv.enhanced.data.EnhancedRepository
import org.jellyfin.androidtv.enhanced.data.EnhancedSettingsRepository
import org.jellyfin.androidtv.enhanced.data.EnhancedState
import org.jellyfin.androidtv.preference.UserPreferences
import timber.log.Timber

/**
 * Keeps upstream's subtitle preferences in line with the signed-in user's Jellyfin Enhanced subtitle style, when the
 * user turned that on for this device and server ([SubtitleSyncStore]). Runs whenever the Jellyfin Enhanced state is
 * (re)loaded or the user's settings are saved from the app, which covers app start and account switches, and when
 * the settings page opens. Changes made in the web client arrive with the next of these.
 *
 * Nothing is written while JE's custom subtitle styles are off for the user (`DisableCustomSubtitleStyles`) or while
 * the plugin state is unknown; the user's own values come back as soon as the signed-in account doesn't sync.
 *
 * Every sync is bound to one session: its reads go through a client pinned to that session's token, and the result is
 * only applied if the app is still signed in as that session afterwards (see [isCurrent]). During an account switch the
 * shared API client changes before the new session is published, so an unpinned read could fetch the next account's
 * style while the session still names the previous one.
 */
class SubtitleStyleSync(
	private val enhancedApi: EnhancedApi,
	private val enhancedRepository: EnhancedRepository,
	private val sessionRepository: SessionRepository,
	private val userRepository: UserRepository,
	private val userPreferences: UserPreferences,
	private val store: SubtitleSyncStore,
) {
	private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
	private val mutex = Mutex()

	private val _enabled = MutableStateFlow(false)

	/** Whether the signed-in account syncs its JE subtitle style to this device. */
	val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

	private val account get() = sessionRepository.currentSession.value?.account

	init {
		val accounts = sessionRepository.currentSession
			.map { it?.account }
			.distinctUntilChanged()
		scope.launch {
			combine(accounts, enhancedRepository.state, ::Pair).collectLatest { (account, state) ->
				_enabled.value = account != null && store.isEnabled(account)
				try {
					sync(account, state)
				} catch (err: CancellationException) {
					throw err
				} catch (err: Exception) {
					Timber.w(err, "Subtitle style sync failed")
				}
			}
		}
	}

	/**
	 * Load the user's JE subtitle style and, if this account syncs, apply it. For the settings page; throws when the
	 * style can't be read. Runs in the sync's own scope so leaving the page doesn't stop a write halfway.
	 */
	suspend fun refresh(): JeSubtitleStyle = scope.async {
		val session = sessionRepository.currentSession.value ?: error("No signed-in user")
		val style = fetchStyle(session) ?: error("The signed-in account changed")
		mutex.withLock {
			if (!isCurrent(session)) error("The signed-in account changed")
			apply(session.account, style)
		}
		style
	}.await()

	/**
	 * Turn the sync on or off for the signed-in account. Turning it on applies the user's JE style (throwing if it
	 * can't be read; the choice is kept and retried on the next sync) and returns the style, turning it off puts the
	 * user's own values back.
	 */
	suspend fun setEnabled(enabled: Boolean): JeSubtitleStyle? {
		val account = account ?: return null
		store.setEnabled(account, enabled)
		_enabled.value = enabled
		if (enabled) return refresh()
		scope.async { mutex.withLock { apply(account, null) } }.await()
		return null
	}

	private suspend fun sync(account: SubtitleSyncAccount?, state: EnhancedState) {
		// Signed out: keep everything as it is until someone signs in
		if (account == null) return
		// A session that isn't the one this emission is for is mid-switch: its own emission syncs it
		val session = sessionRepository.currentSession.value?.takeIf { it.account == account } ?: return
		val style = if (store.isEnabled(account) && state.available) fetchStyle(session) ?: return else null
		mutex.withLock {
			// The account may have changed while the documents loaded
			if (isCurrent(session)) apply(account, style)
		}
	}

	/**
	 * The user's `settings.json` and the server defaults from `public-config`; both are needed to resolve. Null when
	 * the shared client no longer belongs to [session] (a switch is under way), so nothing of another account is read.
	 */
	private suspend fun fetchStyle(session: Session): JeSubtitleStyle? = coroutineScope {
		val api = enhancedApi.pinned()
		if (api.accessToken != session.accessToken) return@coroutineScope null
		val userKey = session.userId.toString().replace("-", "")
		val public = async { api.get("public-config").jsonObject }
		val user = async { api.get("user-settings/$userKey/${EnhancedSettingsRepository.SETTINGS}").jsonObject }
		JeSubtitleStyle.resolve(user.await(), public.await())
	}

	/**
	 * Whether the app is still signed in as [session] everywhere a switch passes through: the shared API client (updated
	 * first), the current user that the plugin state is loaded for (updated next) and the published session (last).
	 */
	private fun isCurrent(session: Session) =
		enhancedApi.accessToken == session.accessToken &&
			userRepository.currentUser.value?.id == session.userId &&
			sessionRepository.currentSession.value == session

	private val Session.account get() = SubtitleSyncAccount(serverId, userId)

	private fun apply(account: SubtitleSyncAccount, style: JeSubtitleStyle?) {
		val action = SubtitleSyncPlanner.plan(
			enabled = store.isEnabled(account),
			style = style,
			current = readNative(),
			backup = store.backup,
			lastApplied = store.lastApplied,
		)
		when (action) {
			SubtitleSyncAction.None -> Unit
			is SubtitleSyncAction.Apply -> {
				// Backup first, so an interrupted write can still be undone
				store.saveApplied(action.target, action.backup)
				writeNative(action.target)
				Timber.i("Applied the Jellyfin Enhanced subtitle style")
			}

			is SubtitleSyncAction.Restore -> {
				writeNative(action.values)
				store.clear()
				Timber.i("Restored the subtitle settings from before the Jellyfin Enhanced sync")
			}
		}
	}

	private fun readNative() = NativeSubtitleStyle(
		textColor = userPreferences[UserPreferences.subtitlesTextColor].toInt(),
		backgroundColor = userPreferences[UserPreferences.subtitlesBackgroundColor].toInt(),
		strokeColor = userPreferences[UserPreferences.subtitleTextStrokeColor].toInt(),
		textSize = userPreferences[UserPreferences.subtitlesTextSize],
		offsetPosition = userPreferences[UserPreferences.subtitlesOffsetPosition],
	)

	/** Writes only what differs, so upstream's own values (and their Long encoding) stay untouched otherwise. */
	private fun writeNative(style: NativeSubtitleStyle) {
		val current = readNative()
		if (current.textColor != style.textColor) userPreferences[UserPreferences.subtitlesTextColor] = style.textColor.toArgbLong()
		if (current.backgroundColor != style.backgroundColor) userPreferences[UserPreferences.subtitlesBackgroundColor] = style.backgroundColor.toArgbLong()
		if (current.strokeColor != style.strokeColor) userPreferences[UserPreferences.subtitleTextStrokeColor] = style.strokeColor.toArgbLong()
		if (current.textSize != style.textSize) userPreferences[UserPreferences.subtitlesTextSize] = style.textSize
		if (current.offsetPosition != style.offsetPosition) userPreferences[UserPreferences.subtitlesOffsetPosition] = style.offsetPosition
	}

	/** Upstream's defaults are unsigned ARGB Longs (`0xFFFFFFFF`); readers use `toInt()`, so either sign works. */
	private fun Int.toArgbLong(): Long = toLong() and 0xFFFFFFFFL
}
