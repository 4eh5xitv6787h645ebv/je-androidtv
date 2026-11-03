package org.jellyfin.androidtv.enhanced.ui.hub

import androidx.compose.runtime.Composable
import org.jellyfin.androidtv.enhanced.data.EnhancedState

/**
 * A notice the hub shows between its header and the page grid, such as the maintenance banner. Bind implementations
 * in a feature module with `bind HubNotice::class`. Notices must not be focusable: the hub's first tile keeps the
 * initial focus, and a notice that appears late must not move it.
 */
interface HubNotice {
	/** Draws nothing when the notice doesn't apply. */
	@Composable
	fun Content(state: EnhancedState)
}
