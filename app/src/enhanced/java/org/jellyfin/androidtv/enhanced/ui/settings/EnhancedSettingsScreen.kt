package org.jellyfin.androidtv.enhanced.ui.settings

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.enhanced.data.EnhancedRepository
import org.jellyfin.androidtv.enhanced.data.EnhancedState
import org.jellyfin.androidtv.ui.base.Icon
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.list.ListButton
import org.jellyfin.androidtv.ui.base.list.ListSection
import org.jellyfin.androidtv.ui.navigation.LocalRouter
import org.jellyfin.androidtv.ui.navigation.focus.focusKey
import org.jellyfin.androidtv.ui.settings.composable.SettingsColumn
import org.koin.compose.koinInject

/** One entry on the Jellyfin Enhanced settings screen. */
class SettingsEntry(
	val route: String,
	@StringRes val title: Int,
	@StringRes val caption: Int,
	@DrawableRes val icon: Int = R.drawable.je_ic_travel_explore,
	val enabled: (EnhancedState) -> Boolean = { true },
)

/** The Jellyfin Enhanced page of the Settings dialog: version line plus the feature sub-pages. */
@Composable
fun EnhancedSettingsScreen() {
	val enhancedRepository = koinInject<EnhancedRepository>()
	val state by enhancedRepository.state.collectAsState()
	val router = LocalRouter.current

	SettingsColumn {
		item {
			ListSection(
				overlineContent = { Text(stringResource(R.string.settings).uppercase()) },
				headingContent = { Text(stringResource(R.string.je_settings_title)) },
				captionContent = {
					Text(
						state.pluginVersion?.let { stringResource(R.string.je_hub_version, it) }
							?: stringResource(R.string.je_settings_caption)
					)
				},
			)
		}

		for (entry in featureSettingsEntries.filter { it.enabled(state) }) {
			item {
				ListButton(
					leadingContent = { Icon(painterResource(entry.icon), contentDescription = null) },
					headingContent = { Text(stringResource(entry.title)) },
					captionContent = { Text(stringResource(entry.caption)) },
					onClick = { router.push(entry.route) },
					modifier = Modifier.focusKey(entry.route),
				)
			}
		}
	}
}
