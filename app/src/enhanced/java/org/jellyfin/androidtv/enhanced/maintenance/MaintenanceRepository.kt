package org.jellyfin.androidtv.enhanced.maintenance

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.decodeFromJsonElement
import org.jellyfin.androidtv.enhanced.api.EnhancedApi
import timber.log.Timber
import java.util.UUID

/**
 * Reads the live maintenance state. The plugin state in [org.jellyfin.androidtv.enhanced.data.EnhancedRepository] is
 * loaded once per sign-in, but a maintenance window can start or end at any time, so this reads `public-config`
 * fresh each time it is asked.
 */
class MaintenanceRepository(private val enhancedApi: EnhancedApi) {
	/** The current maintenance fields, or null when they can't be read (no notice is shown then). */
	suspend fun fetch(): MaintenanceInfo? = try {
		EnhancedApi.json.decodeFromJsonElement<MaintenanceInfo>(enhancedApi.get("public-config"))
	} catch (err: CancellationException) {
		throw err
	} catch (err: Exception) {
		Timber.w(err, "Failed to read the maintenance state")
		null
	}
}

/**
 * Remembers, on this device, the maintenance window whose app-start dialog each user of each server dismissed
 * ([MaintenanceNotice.windowKey]), so it shows once per window.
 */
class MaintenanceDismissStore(context: Context) {
	private val prefs = context.getSharedPreferences("je_maintenance", Context.MODE_PRIVATE)

	fun seen(serverId: UUID, userId: UUID): String? = prefs.getString(key(serverId, userId), null)

	fun remember(serverId: UUID, userId: UUID, windowKey: String) = prefs.edit { putString(key(serverId, userId), windowKey) }

	fun forget(serverId: UUID, userId: UUID) = prefs.edit { remove(key(serverId, userId)) }

	private companion object {
		fun key(serverId: UUID, userId: UUID) = "seen_${serverId.toString().replace("-", "")}_${userId.toString().replace("-", "")}"
	}
}
