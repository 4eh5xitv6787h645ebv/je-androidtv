package org.jellyfin.androidtv.enhanced.management.settings

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.enhanced.ui.hub.LoadState
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.form.Checkbox
import org.jellyfin.androidtv.ui.base.list.ListButton
import org.jellyfin.androidtv.ui.base.list.ListMessage
import org.jellyfin.androidtv.ui.base.list.ListSection
import org.jellyfin.androidtv.ui.navigation.LocalRouter
import org.jellyfin.androidtv.ui.navigation.focus.focusKey

/** The heading of a Jellyfin Enhanced settings page, styled like upstream's settings screens. */
fun LazyListScope.enhancedSettingsHeader(title: @Composable () -> String, caption: (@Composable () -> String)? = null) {
	item {
		ListSection(
			overlineContent = { Text(stringResource(R.string.je_settings_title).uppercase()) },
			headingContent = { Text(title()) },
			captionContent = caption?.let { { Text(it()) } },
		)
	}
}

/** A section heading between groups of rows. */
fun LazyListScope.enhancedSettingsSection(title: @Composable () -> String, caption: (@Composable () -> String)? = null) {
	item {
		ListSection(
			headingContent = { Text(title()) },
			captionContent = caption?.let { { Text(it()) } },
		)
	}
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
 * A checkbox row. [saving] shows the row as busy and ignores clicks until the save finishes. Give the page's first
 * row [initialFocus] so the focus starts there, not on the page's heading.
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
	ListButton(
		headingContent = { Text(title) },
		captionContent = when {
			saving -> {
				{ Text(stringResource(R.string.je_management_saving)) }
			}

			caption != null -> {
				{ Text(caption) }
			}

			else -> null
		},
		trailingContent = { Checkbox(checked = checked) },
		onClick = { if (!saving) onToggle() },
		modifier = Modifier.focusKey("je_$key", initialFocus = initialFocus),
	)
}

/** A row that cycles through a few values on click, showing the current one as its caption. */
@Composable
fun EnhancedChoiceRow(
	key: String,
	title: String,
	value: String,
	saving: Boolean,
	initialFocus: Boolean = false,
	onNext: () -> Unit,
) {
	ListButton(
		headingContent = { Text(title) },
		captionContent = { Text(if (saving) stringResource(R.string.je_management_saving) else value) },
		onClick = { if (!saving) onNext() },
		modifier = Modifier.focusKey("je_$key", initialFocus = initialFocus),
	)
}
