package org.jellyfin.androidtv.enhanced.data

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import org.jellyfin.androidtv.auth.repository.UserRepository
import org.jellyfin.androidtv.enhanced.api.EnhancedApi
import timber.log.Timber
import java.util.UUID

/**
 * Loads and caches the Jellyfin Enhanced feature flags for the signed-in user. The state is reset whenever the
 * user changes and reloaded lazily by [ensureLoaded]. The first load of a session always fetches the plugin
 * configuration afresh, so a session (a saved-token login included) never starts with a document fetched before it.
 *
 * A loaded state is refreshed in the background once it is older than [STALE_AFTER_MS], when the app returns to the
 * foreground ([processLifecycle]) or when a screen asks for it, so an admin's change to the plugin configuration
 * reaches a TV that is never restarted. A refresh only ever replaces the state with a newer good one: failures keep
 * what is there.
 */
class EnhancedRepository(
	private val enhancedApi: EnhancedApi,
	private val userRepository: UserRepository,
	/** The app's process lifecycle, observed for foreground refreshes; null in tests. */
	processLifecycle: Lifecycle? = ProcessLifecycleOwner.get().lifecycle,
	private val clock: () -> Long = System::currentTimeMillis,
) {
	companion object {
		/** After a failure that leaves the configuration unknown, unforced loads wait this long before trying again. */
		const val RETRY_BACKOFF_MS = 30_000L

		/** A loaded state older than this is refreshed in the background (on foreground or the next [ensureLoaded]). */
		const val STALE_AFTER_MS = 5 * 60_000L
	}

	private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
	private val mutex = Mutex()
	private var loadedFor: UUID? = null
	/**
	 * The user and time of the last failure that left the configuration unknown, for [RETRY_BACKOFF_MS]. Per user, so
	 * switching accounts loads at once.
	 */
	private var failedAt: Pair<UUID, Long>? = null

	/** When the last load attempt for [loadedFor] started, successful or not; drives [STALE_AFTER_MS]. */
	private var attemptedAt = 0L

	/**
	 * Bumped by [updateUserSettings] and [updateHiddenSettings]. A load that started before a local save keeps the
	 * saved settings instead of the ones it read, so a background refresh can't undo a save it raced with.
	 */
	@Volatile
	private var userSettingsRevision = 0L

	@Volatile
	private var hiddenSettingsRevision = 0L

	private val _state = MutableStateFlow(EnhancedState())
	val state: StateFlow<EnhancedState> = _state

	/** Synchronous view of the last loaded state, for Java call sites. */
	val current: EnhancedState get() = _state.value

	init {
		userRepository.currentUser
			.map { it?.id }
			.distinctUntilChanged()
			.onEach { userId ->
				mutex.withLock {
					// The first emission is usually the user a screen already loaded for: keep that state
					if (loadedFor != userId) {
						loadedFor = null
						_state.value = EnhancedState()
						// Features read public-config directly too: none of them may get the previous session's copy
						enhancedApi.invalidatePublicConfig()
					}
				}
				if (userId != null) ensureLoaded()
			}
			.launchIn(scope)

		processLifecycle?.let { lifecycle ->
			// Observers must be added on the main thread
			scope.launch(Dispatchers.Main.immediate) {
				lifecycle.addObserver(object : DefaultLifecycleObserver {
					override fun onStart(owner: LifecycleOwner) = refreshIfStale()
				})
			}
		}
	}

	/**
	 * Load the state for the current user if it has not been loaded yet, then return it.
	 *
	 * Only a 404 from `public-config` means "no plugin" and is cached like a successful load. Any other failure leaves
	 * the configuration unknown: a state loaded earlier for this user is kept, otherwise
	 * [EnhancedState.configUnavailable] is returned and nothing is cached. Unforced calls retry at most once per
	 * [RETRY_BACKOFF_MS] (every screen asks, and each attempt can take the whole timeout against a struggling server);
	 * until then they get the unknown state again. [force] always tries at once (an explicit Retry) and fetches the
	 * plugin configuration afresh. A loaded state older than [STALE_AFTER_MS] is returned at once and refreshed in the
	 * background.
	 */
	suspend fun ensureLoaded(force: Boolean = false): EnhancedState = load(force, background = false)

	private suspend fun load(force: Boolean, background: Boolean): EnhancedState = mutex.withLock {
		val userId = userRepository.currentUser.value?.id ?: return@withLock _state.value
		if (!force && loadedFor == userId) {
			if (clock() - attemptedAt !in 0 until STALE_AFTER_MS) {
				attemptedAt = clock()
				scope.launch { load(force = true, background = true) }
			}
			return@withLock _state.value
		}
		val backingOff = failedAt?.let { (failedFor, at) -> failedFor == userId && clock() - at in 0 until RETRY_BACKOFF_MS } == true
		if (!force && backingOff) return@withLock EnhancedState(configUnavailable = true)

		val previous = _state.value.takeIf { loadedFor == userId }
		val userSettingsAtStart = userSettingsRevision
		val hiddenSettingsAtStart = hiddenSettingsRevision
		attemptedAt = clock()
		val newState = try {
			// The first load for this user (a new session) never adopts a cached copy
			fetch(userId, fresh = force || loadedFor != userId)
		} catch (err: CancellationException) {
			throw err
		} catch (err: Exception) {
			if (isPluginAbsent(err) && !(background && previous?.available == true)) {
				Timber.i(err, "Jellyfin Enhanced not available")
				EnhancedState()
			} else {
				// A background refresh never takes a working state away, not even for a 404 (a proxy or a restart)
				Timber.w(err, "Jellyfin Enhanced configuration unavailable")
				if (previous != null) return@withLock previous
				failedAt = userId to clock()
				val unknown = EnhancedState(configUnavailable = true)
				if (userRepository.currentUser.value?.id == userId) _state.value = unknown
				return@withLock unknown
			}
		}.let { loaded ->
			// Settings saved locally while this was loading are newer than what it read
			val current = _state.value
			if (previous == null || !loaded.available) loaded
			else loaded.copy(
				userSettings = if (userSettingsRevision != userSettingsAtStart) current.userSettings else loaded.userSettings,
				hiddenSettings = if (hiddenSettingsRevision != hiddenSettingsAtStart) current.hiddenSettings else loaded.hiddenSettings,
			)
		}

		// Don't publish if the user changed while loading
		if (userRepository.currentUser.value?.id == userId) {
			loadedFor = userId
			failedAt = null
			_state.value = newState
		}
		newState
	}

	fun refreshInBackground() {
		scope.launch { ensureLoaded(force = true) }
	}

	/**
	 * Refresh in the background if the state is older than [STALE_AFTER_MS] (the app came back to the foreground). A
	 * state that never loaded is retried, within [RETRY_BACKOFF_MS].
	 */
	fun refreshIfStale() {
		scope.launch {
			if (userRepository.currentUser.value?.id != null) load(force = false, background = true)
		}
	}

	/** Publish freshly saved user settings without re-fetching everything. */
	fun updateUserSettings(settings: EnhancedUserSettings) {
		userSettingsRevision++
		_state.value = _state.value.copy(userSettings = settings)
	}

	/** Publish freshly saved Hidden Content settings without re-fetching everything. */
	fun updateHiddenSettings(settings: HiddenContentSettings?) {
		hiddenSettingsRevision++
		_state.value = _state.value.copy(hiddenSettings = settings)
	}

	private suspend fun fetch(userId: UUID, fresh: Boolean): EnhancedState = kotlinx.coroutines.coroutineScope {
		val userKey = userId.toString().replace("-", "")
		val configCall = async { enhancedApi.publicConfig(fresh) }
		val settingsCall = async { orNull { enhancedApi.get("user-settings/$userKey/settings.json") } }
		val versionCall = async { orNull { enhancedApi.getText("version").text.trim().ifEmpty { null } } }

		val config = EnhancedApi.json.decodeFromJsonElement<EnhancedPublicConfig>(configCall.await())
		val settings = settingsCall.await()
			?.let { orNull { EnhancedApi.json.decodeFromJsonElement<EnhancedUserSettings>(it) } }
			?: EnhancedUserSettings()

		val seerr = if (config.jellyseerrEnabled) {
			orNull {
				EnhancedApi.json.decodeFromJsonElement<SeerrUserStatus>(enhancedApi.get("jellyseerr/user-status"))
			} ?: SeerrUserStatus()
		} else SeerrUserStatus()

		val hiddenSettings = if (config.hiddenContentEnabled) {
			orNull {
				HiddenContentRepository.Snapshot(enhancedApi.get("user-settings/$userKey/hidden-content.json").jsonObject).settings
			}
		} else null

		EnhancedState(
			available = true,
			config = config,
			userSettings = settings,
			seerr = seerr,
			hiddenSettings = hiddenSettings,
			pluginVersion = versionCall.await(),
			isAdmin = userRepository.currentUser.value?.policy?.isAdministrator == true,
		)
	}
}

/** A 404 for `public-config` is the only sign that the server has no Jellyfin Enhanced plugin. */
private fun isPluginAbsent(err: Exception) = err is EnhancedApi.EnhancedApiException && err.status == 404

/** For optional parts of the state: null on failure, but cancellation still cancels. */
private inline fun <T> orNull(block: () -> T): T? = try {
	block()
} catch (err: CancellationException) {
	throw err
} catch (err: Exception) {
	Timber.d(err, "Optional Jellyfin Enhanced state unavailable")
	null
}
