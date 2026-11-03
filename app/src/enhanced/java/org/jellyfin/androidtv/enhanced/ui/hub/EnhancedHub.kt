package org.jellyfin.androidtv.enhanced.ui.hub

import org.jellyfin.androidtv.enhanced.data.EnhancedState

/**
 * Registry of the hub pages. Feature modules add their [HubEntry] here (one line per feature, kept by the
 * integration owner) so the hub, the toolbar and the settings screen all see the same list.
 */
object EnhancedHub {
	private val registered = mutableListOf<HubEntry>()

	/** All entries in display order. */
	val entries: List<HubEntry> get() = registered.sortedBy { it.order }

	fun register(vararg entries: HubEntry) {
		entries.forEach { entry -> if (registered.none { it.id == entry.id }) registered += entry }
	}

	fun entry(id: String): HubEntry? = registered.firstOrNull { it.id == id }

	/** Entries that apply to the current state. */
	fun available(state: EnhancedState): List<HubEntry> = entries.filter { state.available && it.enabled(state) }
}
