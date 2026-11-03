package org.jellyfin.androidtv.enhanced.home

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import org.jellyfin.androidtv.auth.repository.SessionRepository
import org.jellyfin.androidtv.enhanced.catchup.normalizeItemId

/**
 * Which Jellyfin Enhanced home rows the user turned on, per server and user, on this device. JE's `settings.json`
 * has no keys for them (the web has no such rows), so they are kept locally like Catch Up's dismissals.
 */
class HomeRowsStore(
	context: Context,
	private val sessionRepository: SessionRepository,
) {
	private val prefs = context.getSharedPreferences("je_home", Context.MODE_PRIVATE)

	/** Goes up on every change, so [enabled] collectors see it. */
	private val revision = MutableStateFlow(0)

	/** The signed-in user's rows; follows account switches and changes made on the settings page. */
	val enabled: Flow<Set<HomeRowKind>> = combine(sessionRepository.currentSession, revision) { session, _ ->
		session?.let { load(it.serverId.toString(), it.userId.toString()) }.orEmpty()
	}.distinctUntilChanged()

	/** The signed-in user's rows right now. */
	fun current(): Set<HomeRowKind> {
		val session = sessionRepository.currentSession.value ?: return emptySet()
		return load(session.serverId.toString(), session.userId.toString())
	}

	fun setEnabled(kind: HomeRowKind, on: Boolean) {
		val session = sessionRepository.currentSession.value ?: return
		val key = key(session.serverId.toString(), session.userId.toString())
		val rows = load(session.serverId.toString(), session.userId.toString()).let { if (on) it + kind else it - kind }
		prefs.edit().apply {
			if (rows.isEmpty()) remove(key) else putStringSet(key, rows.mapTo(HashSet()) { it.key })
		}.apply()
		revision.update { it + 1 }
	}

	private fun load(serverId: String, userId: String): Set<HomeRowKind> =
		prefs.getStringSet(key(serverId, userId), null).orEmpty().mapNotNullTo(HashSet(), HomeRowKind::fromKey)

	internal companion object {
		fun key(serverId: String, userId: String) = "rows_${normalizeItemId(serverId)}_${normalizeItemId(userId)}"
	}
}
