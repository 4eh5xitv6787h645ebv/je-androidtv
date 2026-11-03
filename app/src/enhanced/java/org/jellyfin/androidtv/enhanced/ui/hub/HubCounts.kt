package org.jellyfin.androidtv.enhanced.ui.hub

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.jellyfin.androidtv.enhanced.api.EnhancedApi
import org.jellyfin.androidtv.enhanced.data.EnhancedSettingsRepository
import org.jellyfin.androidtv.enhanced.data.EnhancedState
import org.jellyfin.androidtv.enhanced.data.HiddenContentRepository
import org.jellyfin.androidtv.enhanced.data.SpoilerGuardRepository
import timber.log.Timber

/** A count shown on a hub tile: [pending] counts read "3 pending", the others are a bare number. */
data class HubCount(val value: Int, val pending: Boolean = false)

/**
 * The small counts on the hub tiles (pending requests, bookmarks, Spoiler Guard titles, Hidden Content entries).
 * They are loaded after the hub has drawn, never before, each with one small request, and kept per account so the
 * next visit shows the last known value at once. Pending requests are read once per account and session (the
 * slowest call, and the one that changes least often from this client); the others refresh on every visit.
 * A failed read leaves its tile without a count: an error must never look like "0".
 */
class HubCounts(
	private val enhancedApi: EnhancedApi,
	private val settingsRepository: EnhancedSettingsRepository,
	private val spoilerGuardRepository: SpoilerGuardRepository,
	private val hiddenContentRepository: HiddenContentRepository,
) {
	companion object {
		/** Hub entry ids that get a count (the ids registered by the feature modules). */
		const val REQUESTS = "requests"
		const val BOOKMARKS = "bookmarks"
		const val SPOILER_GUARD = "spoiler-guard"
		const val HIDDEN_CONTENT = "hidden-content"
	}

	private var account: String? = null
	private val _counts = MutableStateFlow<Map<String, HubCount>>(emptyMap())

	/** The last known counts of the current account, by hub entry id. */
	val counts: StateFlow<Map<String, HubCount>> = _counts.asStateFlow()

	/** Refresh the counts of the [shown] hub entries in the background of the hub. Never throws (but cancels). */
	suspend fun refresh(state: EnhancedState, shown: Set<String>) {
		val token = enhancedApi.accessToken
		if (token != account) {
			account = token
			_counts.value = emptyMap()
		}

		coroutineScope {
			if (REQUESTS in shown && REQUESTS !in _counts.value) async {
				load(REQUESTS) {
					val page = enhancedApi.get(
						"arr/requests",
						mapOf("take" to 1, "skip" to 0, "filter" to "pending", "userOnly" to state.config.downloadsFilterByUserRequests),
					) as? JsonObject
					page?.get("totalResults")?.jsonPrimitive?.intOrNull?.let { HubCount(it, pending = true) }
				}
			}
			if (BOOKMARKS in shown) async {
				load(BOOKMARKS) {
					val document = settingsRepository.read(EnhancedSettingsRepository.BOOKMARKS).json
					HubCount((document["Bookmarks"] as? JsonObject)?.size ?: 0)
				}
			}
			if (SPOILER_GUARD in shown) async {
				load(SPOILER_GUARD) {
					val guarded = spoilerGuardRepository.getState()
					HubCount(SpoilerGuardRepository.entries(guarded).size + SpoilerGuardRepository.pendingEntries(guarded).size)
				}
			}
			if (HIDDEN_CONTENT in shown) async {
				load(HIDDEN_CONTENT) { HubCount(hiddenContentRepository.get().entries.size) }
			}
		}
	}

	private suspend fun load(id: String, read: suspend () -> HubCount?) {
		val token = account
		val count = try {
			read()
		} catch (e: CancellationException) {
			throw e
		} catch (e: Exception) {
			Timber.w(e, "Hub count %s failed", id)
			null
		}
		// The account changed while this was loading: its count belongs to someone else
		if (enhancedApi.accessToken != token) return
		_counts.update { if (count == null) it - id else it + (id to count) }
	}
}
