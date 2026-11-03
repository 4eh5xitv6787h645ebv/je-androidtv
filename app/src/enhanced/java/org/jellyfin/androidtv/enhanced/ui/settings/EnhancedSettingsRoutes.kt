package org.jellyfin.androidtv.enhanced.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.enhanced.data.EnhancedRepository
import org.jellyfin.androidtv.ui.base.Icon
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.list.ListButton
import org.jellyfin.androidtv.ui.navigation.LocalRouter
import org.jellyfin.androidtv.ui.navigation.RouteComposable
import org.jellyfin.androidtv.ui.navigation.focus.focusKey
import org.koin.compose.koinInject

/** Routes of the Jellyfin Enhanced pages inside the app's Settings dialog. */
object EnhancedSettingsRoutes {
	const val MAIN = "/enhanced"
	const val SPOILER_GUARD = "/enhanced/spoiler-guard"
	const val HIDDEN_CONTENT = "/enhanced/hidden-content"
	const val DETAILS = "/enhanced/details"
	const val PLAYBACK = "/enhanced/playback"
	const val ABOUT = "/enhanced/about"
}

/**
 * The settings routes contributed by the fork. Feature modules add their screens here through the integration
 * owner; each screen lives in the feature's own file.
 */
val enhancedSettingsRoutes: Map<String, RouteComposable> = mapOf<String, RouteComposable>(
	EnhancedSettingsRoutes.MAIN to { EnhancedSettingsScreen() },
) + featureSettingsRoutes

/** The "Jellyfin Enhanced" entry on the main settings screen; hidden on servers without the plugin. */
@Composable
fun EnhancedSettingsEntry() {
	val enhancedRepository = koinInject<EnhancedRepository>()
	val state by enhancedRepository.state.collectAsState()
	if (!state.available) return
	val router = LocalRouter.current

	ListButton(
		leadingContent = { Icon(painterResource(R.drawable.je_ic_travel_explore), contentDescription = null) },
		headingContent = { Text(stringResource(R.string.je_settings_title)) },
		captionContent = { Text(stringResource(R.string.je_settings_caption)) },
		onClick = { router.push(EnhancedSettingsRoutes.MAIN) },
		modifier = Modifier.focusKey(EnhancedSettingsRoutes.MAIN),
	)
}
