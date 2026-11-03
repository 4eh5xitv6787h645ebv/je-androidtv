package org.jellyfin.androidtv.enhanced.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.res.stringResource
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.enhanced.data.EnhancedRepository
import org.jellyfin.androidtv.enhanced.ui.hub.EnhancedHub
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.button.Button
import org.jellyfin.androidtv.ui.base.button.ButtonColors
import org.jellyfin.androidtv.ui.base.button.ButtonDefaults
import org.jellyfin.androidtv.ui.navigation.NavigationRepository
import org.koin.compose.koinInject

/**
 * Whether the toolbar belongs to the hub itself rather than to one of its feature pages: both show the Enhanced button
 * active, but only on the hub does pressing it have nothing to do. Provided by
 * [org.jellyfin.androidtv.enhanced.ui.hub.EnhancedHubFragment] (and `EnhancedScreen(hub = true)`).
 */
val LocalOnEnhancedHub = staticCompositionLocalOf { false }

/**
 * The "Enhanced" button in the main toolbar, next to Home and Search. Only shown when the server runs Jellyfin
 * Enhanced and at least one hub page applies to the user, so stock servers see the stock toolbar.
 *
 * Elsewhere it opens the hub. On a feature page it returns to the hub, replacing the page the way the Home button
 * replaces the current screen with home.
 */
@Composable
fun EnhancedToolbarButton(active: Boolean, activeButtonColors: ButtonColors) {
	val enhancedRepository = koinInject<EnhancedRepository>()
	val navigationRepository = koinInject<NavigationRepository>()
	val state by enhancedRepository.state.collectAsState()
	val onHub = LocalOnEnhancedHub.current
	if (!state.available || EnhancedHub.available(state).isEmpty()) return

	Button(
		onClick = {
			when {
				!active -> navigationRepository.navigate(EnhancedDestinations.hub())
				!onHub -> navigationRepository.navigate(EnhancedDestinations.hub(), replace = true)
			}
		},
		colors = if (active) activeButtonColors else ButtonDefaults.colors(),
		content = { Text(stringResource(R.string.je_toolbar_enhanced)) },
	)
}
