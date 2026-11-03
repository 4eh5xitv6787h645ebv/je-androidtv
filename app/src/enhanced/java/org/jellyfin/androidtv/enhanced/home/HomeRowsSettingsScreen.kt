package org.jellyfin.androidtv.enhanced.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.enhanced.data.EnhancedRepository
import org.jellyfin.androidtv.enhanced.management.settings.EnhancedToggleRow
import org.jellyfin.androidtv.enhanced.management.settings.enhancedSettingsHeader
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.list.ListMessage
import org.jellyfin.androidtv.ui.settings.composable.SettingsColumn
import org.koin.compose.koinInject

/**
 * Settings › Jellyfin Enhanced › Home rows: one switch per optional home row, for the rows this server offers. The
 * choices are saved on the device per server and user ([HomeRowsStore]) and the home screen follows them live.
 */
@Composable
fun HomeRowsSettingsScreen() {
	val store = koinInject<HomeRowsStore>()
	val enhancedRepository = koinInject<EnhancedRepository>()
	val state by enhancedRepository.state.collectAsState()
	val initial = remember(store) { store.current() }
	val enabled by store.enabled.collectAsState(initial)
	val offered = HomeRowKind.entries.filter { it.gate(state) }

	SettingsColumn {
		enhancedSettingsHeader(
			title = { stringResource(R.string.je_home_settings_title) },
			caption = { stringResource(R.string.je_home_settings_description) },
		)

		if (!state.available || offered.isEmpty()) {
			item { ListMessage { Text(stringResource(R.string.je_home_settings_none)) } }
		}

		for (kind in offered) {
			item(key = kind.key) {
				EnhancedToggleRow(
					key = "home_${kind.key}",
					title = stringResource(kind.title),
					caption = stringResource(kind.caption),
					checked = kind in enabled,
					saving = false,
					onToggle = { store.setEnabled(kind, kind !in enabled) },
				)
			}
		}
	}
}
