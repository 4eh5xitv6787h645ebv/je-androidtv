package org.jellyfin.androidtv.enhanced.management.settings

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.enhanced.ui.design.SettingsChoice
import org.jellyfin.androidtv.enhanced.ui.design.SettingsIntro
import org.jellyfin.androidtv.enhanced.ui.design.SettingsSection
import org.jellyfin.androidtv.enhanced.ui.design.SettingsToggle
import org.jellyfin.androidtv.enhanced.ui.hub.LoadState
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.list.ListButton
import org.jellyfin.androidtv.ui.base.list.ListMessage
import org.jellyfin.androidtv.ui.navigation.LocalRouter
import org.jellyfin.androidtv.ui.navigation.focus.focusKey

/**
 * The heading of a Jellyfin Enhanced settings page: "JELLYFIN ENHANCED" overline, the [title], and the page intro
 * ([caption], at most two lines, said once instead of in every row).
 */
fun LazyListScope.enhancedSettingsHeader(title: @Composable () -> String, caption: (@Composable () -> String)? = null) {
	item { SettingsSection(overline = stringResource(R.string.je_settings_title), heading = title()) }
	if (caption != null) item { SettingsIntro(caption()) }
}

/** A group of rows under an overline ("HIDDEN ON PROTECTED TITLES"), never a bold row. */
fun LazyListScope.enhancedSettingsSection(title: @Composable () -> String, caption: (@Composable () -> String)? = null) {
	item { SettingsSection(overline = title()) }
	if (caption != null) item { SettingsIntro(caption()) }
}

/**
 * Loading and error rows for a settings page that reads a server document. Returns the loaded value, or null while
 * nothing else should be shown.
 */
fun <T> LazyListScope.enhancedSettingsLoad(state: LoadState<T>): T? = when (state) {
	LoadState.Loading -> {
		item { ListMessage { Text(stringResource(R.string.je_loading)) } }
		null
	}

	is LoadState.Error -> {
		item { ListMessage { Text(state.message ?: stringResource(R.string.je_error_generic)) } }
		item {
			ListButton(
				headingContent = { Text(stringResource(R.string.je_retry)) },
				onClick = state.retry,
				modifier = Modifier.focusKey("je_retry", initialFocus = true),
			)
		}
		null
	}

	is LoadState.Ready -> state.value
}

/**
 * A message row for a page that can't show its settings (the feature is turned off on the server), with a focused
 * Back row so a D-pad user doesn't land on nothing.
 */
fun LazyListScope.enhancedSettingsUnavailable(message: @Composable () -> String) {
	item { ListMessage { Text(message()) } }
	item {
		val router = LocalRouter.current
		ListButton(
			headingContent = { Text(stringResource(R.string.je_hub_back)) },
			onClick = { router.back() },
			modifier = Modifier.focusKey("je_back", initialFocus = true),
		)
	}
}

/**
 * A checkbox row ([SettingsToggle]). [saving] shows the row as busy and ignores clicks until the save finishes. Give
 * the page's first row [initialFocus] so the focus starts there, not on the page's heading.
 */
@Composable
fun EnhancedToggleRow(
	key: String,
	title: String,
	caption: String?,
	checked: Boolean,
	saving: Boolean,
	initialFocus: Boolean = false,
	onToggle: () -> Unit,
) {
	SettingsToggle(
		title = title,
		caption = if (saving) stringResource(R.string.je_management_saving) else caption,
		checked = checked,
		onToggle = { if (!saving) onToggle() },
		modifier = Modifier.focusKey("je_$key", initialFocus = initialFocus),
	)
}

/**
 * A setting with a few values ([SettingsChoice]): the current one is the caption, pressing opens a picker. [selected]
 * is -1 for a stored value that isn't one of the [options]; [value] is then shown as is.
 */
@Composable
fun EnhancedChoiceRow(
	key: String,
	title: String,
	options: List<String>,
	selected: Int,
	value: String,
	saving: Boolean,
	initialFocus: Boolean = false,
	onSelect: (Int) -> Unit,
) {
	SettingsChoice(
		title = title,
		options = options,
		selected = selected,
		caption = if (saving) stringResource(R.string.je_management_saving) else value,
		onSelect = { if (!saving && it != selected) onSelect(it) },
		modifier = Modifier.focusKey("je_$key", initialFocus = initialFocus),
	)
}
