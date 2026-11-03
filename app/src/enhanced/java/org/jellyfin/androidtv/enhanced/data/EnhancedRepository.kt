package org.jellyfin.androidtv.enhanced.data

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
) {
	private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
	private val mutex = Mutex()
	private var loadedFor: UUID? = null

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
					loadedFor = null
					_state.value = EnhancedState()
				}
				if (userId != null) ensureLoaded()
			}
			.launchIn(scope)
	}

	/** Load the state for the current user if it has not been loaded yet, then return it. */
	suspend fun ensureLoaded(force: Boolean = false): EnhancedState = mutex.withLock {
		val userId = userRepository.currentUser.value?.id ?: return@withLock _state.value
		if (!force && loadedFor == userId) return@withLock _state.value

		val newState = runCatching { load(userId) }
			.onFailure { Timber.i(it, "Jellyfin Enhanced not available") }
			.getOrDefault(EnhancedState())

		// Don't publish if the user changed while loading
		if (userRepository.currentUser.value?.id == userId) {
			loadedFor = userId
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
		val settingsCall = async { runCatching { enhancedApi.get("user-settings/$userKey/settings.json") }.getOrNull() }
		val versionCall = async { runCatching { enhancedApi.getText("version").text.trim().ifEmpty { null } }.getOrNull() }

		val config = EnhancedApi.json.decodeFromJsonElement<EnhancedPublicConfig>(configCall.await())
		val settings = settingsCall.await()
			?.let { runCatching { EnhancedApi.json.decodeFromJsonElement<EnhancedUserSettings>(it) }.getOrNull() }
			?: EnhancedUserSettings()

		val seerr = if (config.jellyseerrEnabled) {
			runCatching {
				EnhancedApi.json.decodeFromJsonElement<SeerrUserStatus>(enhancedApi.get("jellyseerr/user-status"))
			}.getOrDefault(SeerrUserStatus())
		} else SeerrUserStatus()

		val hiddenSettings = if (config.hiddenContentEnabled) {
			runCatching {
				HiddenContentRepository.Snapshot(enhancedApi.get("user-settings/$userKey/hidden-content.json").jsonObject).settings
			}.getOrNull()
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
