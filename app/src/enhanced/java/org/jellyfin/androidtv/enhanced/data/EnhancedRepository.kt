package org.jellyfin.androidtv.enhanced.data

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
 * user changes and reloaded lazily by [ensureLoaded].
 */
class EnhancedRepository(
	private val enhancedApi: EnhancedApi,
	private val userRepository: UserRepository,
	private val clock: () -> Long = System::currentTimeMillis,
) {
	companion object {
		/** After a failure that leaves the configuration unknown, unforced loads wait this long before trying again. */
		const val RETRY_BACKOFF_MS = 30_000L
	}

	private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
	private val mutex = Mutex()
	private var loadedFor: UUID? = null
	/**
	 * The user and time of the last failure that left the configuration unknown, for [RETRY_BACKOFF_MS]. Per user, so
	 * switching accounts loads at once.
	 */
	private var failedAt: Pair<UUID, Long>? = null

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
					}
				}
				if (userId != null) ensureLoaded()
			}
			.launchIn(scope)
	}

	/**
	 * Load the state for the current user if it has not been loaded yet, then return it.
	 *
	 * Only a 404 from `public-config` means "no plugin" and is cached like a successful load. Any other failure leaves
	 * the configuration unknown: a state loaded earlier for this user is kept, otherwise
	 * [EnhancedState.configUnavailable] is returned and nothing is cached. Unforced calls retry at most once per
	 * [RETRY_BACKOFF_MS] (every screen asks, and each attempt can take the whole timeout against a struggling server);
	 * until then they get the unknown state again. [force] always tries at once (an explicit Retry).
	 */
	suspend fun ensureLoaded(force: Boolean = false): EnhancedState = mutex.withLock {
		val userId = userRepository.currentUser.value?.id ?: return@withLock _state.value
		if (!force && loadedFor == userId) return@withLock _state.value
		val backingOff = failedAt?.let { (failedFor, at) -> failedFor == userId && clock() - at in 0 until RETRY_BACKOFF_MS } == true
		if (!force && backingOff) return@withLock EnhancedState(configUnavailable = true)

		val newState = try {
			load(userId)
		} catch (err: CancellationException) {
			throw err
		} catch (err: Exception) {
			if (isPluginAbsent(err)) {
				Timber.i(err, "Jellyfin Enhanced not available")
				EnhancedState()
			} else {
				Timber.w(err, "Jellyfin Enhanced configuration unavailable")
				if (loadedFor == userId) return@withLock _state.value
				failedAt = userId to clock()
				val unknown = EnhancedState(configUnavailable = true)
				if (userRepository.currentUser.value?.id == userId) _state.value = unknown
				return@withLock unknown
			}
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

	/** Publish freshly saved user settings without re-fetching everything. */
	fun updateUserSettings(settings: EnhancedUserSettings) {
		_state.value = _state.value.copy(userSettings = settings)
	}

	/** Publish freshly saved Hidden Content settings without re-fetching everything. */
	fun updateHiddenSettings(settings: HiddenContentSettings?) {
		_state.value = _state.value.copy(hiddenSettings = settings)
	}

	private suspend fun load(userId: UUID): EnhancedState = kotlinx.coroutines.coroutineScope {
		val userKey = userId.toString().replace("-", "")
		val configCall = async { enhancedApi.get("public-config") }
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
