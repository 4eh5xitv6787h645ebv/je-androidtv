package org.jellyfin.androidtv.enhanced.ui.hub

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import org.jellyfin.androidtv.enhanced.data.EnhancedState
import org.jellyfin.androidtv.ui.navigation.Destination

/**
 * One page of the Jellyfin Enhanced hub. Each feature contributes its own entry (in its own file) and the
 * integration owner registers it in [EnhancedHub.entries].
 */
class HubEntry(
	/** Stable id, also used as the `page` argument of [org.jellyfin.androidtv.enhanced.ui.EnhancedDestinations.hub]. */
	val id: String,
	@StringRes val title: Int,
	@StringRes val description: Int,
	@DrawableRes val icon: Int,
	/** Whether the page applies to the current server/user. Hidden otherwise. */
	val enabled: (EnhancedState) -> Boolean,
	/** The screen to open. */
	val destination: () -> Destination,
	/** Order in the hub grid; lower first. */
	val order: Int = 100,
)
