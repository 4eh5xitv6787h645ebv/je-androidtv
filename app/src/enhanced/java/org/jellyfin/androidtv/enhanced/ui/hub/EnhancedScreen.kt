package org.jellyfin.androidtv.enhanced.ui.hub

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.ui.base.JellyfinTheme
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.button.Button
import org.jellyfin.androidtv.ui.shared.toolbar.MainToolbar
import org.jellyfin.androidtv.ui.shared.toolbar.MainToolbarActiveButton
import org.jellyfin.design.Tokens

/**
 * Loading state shared by the Jellyfin Enhanced screens. [Error.retry] re-runs the load; [Error.message] is the
 * server's message when it sent one.
 */
sealed interface LoadState<out T> {
	data object Loading : LoadState<Nothing>
	data class Ready<T>(val value: T) : LoadState<T>
	data class Error(val message: String?, val retry: () -> Unit) : LoadState<Nothing>
}

/**
 * Page scaffold for Jellyfin Enhanced screens: the main toolbar (with the Enhanced button active), a title row and
 * the page content, all inside the app theme. Use it for Compose pages; leanback-grid pages can use
 * [EnhancedScreenHeader] alone.
 */
@Composable
fun EnhancedScreen(
	title: String,
	modifier: Modifier = Modifier,
	subtitle: String? = null,
	headerActions: (@Composable () -> Unit)? = null,
	content: @Composable () -> Unit,
) {
	JellyfinTheme {
		Column(modifier = modifier.fillMaxSize()) {
			MainToolbar(MainToolbarActiveButton.Enhanced)
			EnhancedScreenHeader(title = title, subtitle = subtitle, actions = headerActions)
			Box(modifier = Modifier.fillMaxSize()) { content() }
		}
	}
}

@Composable
fun EnhancedScreenHeader(
	title: String,
	modifier: Modifier = Modifier,
	subtitle: String? = null,
	actions: (@Composable () -> Unit)? = null,
) {
	Column(
		modifier = modifier
			.fillMaxWidth()
			.padding(start = 48.dp, end = 48.dp, bottom = 12.dp),
		verticalArrangement = Arrangement.spacedBy(8.dp),
	) {
		Row(
			horizontalArrangement = Arrangement.spacedBy(16.dp),
			verticalAlignment = Alignment.CenterVertically,
		) {
			Text(text = title, fontSize = 28.sp, color = Tokens.Color.colorWhite)
			if (subtitle != null) Text(text = subtitle, fontSize = 16.sp, color = Tokens.Color.colorGrey300)
		}
		if (actions != null) {
			Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) { actions() }
		}
	}
}

/** Centered loading / error (with a focused Retry button) / content switch. */
@Composable
fun <T> LoadStateContent(
	state: LoadState<T>,
	modifier: Modifier = Modifier,
	empty: ((T) -> Boolean)? = null,
	emptyMessage: String = stringResource(R.string.je_nothing_found),
	content: @Composable (T) -> Unit,
) {
	Box(modifier = modifier.fillMaxSize()) {
		when (state) {
			LoadState.Loading -> Text(
				text = stringResource(R.string.je_loading),
				color = Tokens.Color.colorGrey300,
				fontSize = 18.sp,
				modifier = Modifier.align(Alignment.Center),
			)

			is LoadState.Error -> Column(
				modifier = Modifier.align(Alignment.Center),
				horizontalAlignment = Alignment.CenterHorizontally,
				verticalArrangement = Arrangement.spacedBy(16.dp),
			) {
				val retryFocus = remember { FocusRequester() }
				Text(text = state.message ?: stringResource(R.string.je_error_generic), color = Tokens.Color.colorGrey100, fontSize = 18.sp)
				Button(onClick = state.retry, modifier = Modifier.focusRequester(retryFocus)) { Text(stringResource(R.string.je_retry)) }
				LaunchedEffect(Unit) { runCatching { retryFocus.requestFocus() } }
			}

			is LoadState.Ready -> if (empty != null && empty(state.value)) {
				Text(text = emptyMessage, color = Tokens.Color.colorGrey300, fontSize = 18.sp, modifier = Modifier.align(Alignment.Center))
			} else content(state.value)
		}
	}
}
