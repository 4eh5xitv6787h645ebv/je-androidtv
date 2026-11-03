package org.jellyfin.androidtv.enhanced.ui.hub

import org.jellyfin.androidtv.enhanced.ui.secondaryTextColor
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.ui.base.JellyfinTheme
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.button.Button
import org.jellyfin.androidtv.ui.navigation.NavigationRepository
import org.jellyfin.androidtv.ui.shared.toolbar.MainToolbar
import org.jellyfin.androidtv.ui.shared.toolbar.MainToolbarActiveButton
import org.jellyfin.design.Tokens
import org.koin.compose.koinInject

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
	val pageFocused = remember { mutableStateOf(false) }
	JellyfinTheme {
		Column(modifier = modifier.fillMaxSize()) {
			MainToolbar(MainToolbarActiveButton.Enhanced)
			// Everything under the toolbar: whether the page itself (header actions included) holds the focus
			Column(modifier = Modifier.fillMaxSize().onFocusChanged { pageFocused.value = it.hasFocus }) {
				EnhancedScreenHeader(title = title, subtitle = subtitle, actions = headerActions)
				CompositionLocalProvider(LocalPageFocused provides pageFocused) {
					Box(modifier = Modifier.fillMaxSize()) { content() }
				}
			}
		}
	}
}

/** Whether something on the current [EnhancedScreen] below the toolbar has the focus; null outside one. */
private val LocalPageFocused = staticCompositionLocalOf<State<Boolean>?> { null }

/** A focusable action under an empty state, so D-pad users don't land on nothing. */
class EmptyAction(val label: String, val onClick: () -> Unit)

/** "Back", the default [EmptyAction]: leaves the page. */
@Composable
fun rememberBackAction(): EmptyAction {
	val navigationRepository = koinInject<NavigationRepository>()
	val label = stringResource(R.string.je_hub_back)
	return remember(navigationRepository, label) { EmptyAction(label) { navigationRepository.goBack() } }
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
			if (subtitle != null) Text(text = subtitle, fontSize = 16.sp, color = secondaryTextColor())
		}
		if (actions != null) {
			Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) { actions() }
		}
	}
}

/**
 * Centered loading / error (with a focused Retry button) / content switch. The empty state shows [emptyMessage] and
 * [emptyAction] (Back unless the page passes its own or null), which takes the focus unless something else on the
 * page already has it (filters, tabs), so a D-pad user always lands on something.
 */
@Composable
fun <T> LoadStateContent(
	state: LoadState<T>,
	modifier: Modifier = Modifier,
	empty: ((T) -> Boolean)? = null,
	emptyMessage: String = stringResource(R.string.je_nothing_found),
	emptyAction: EmptyAction? = rememberBackAction(),
	content: @Composable (T) -> Unit,
) {
	Box(modifier = modifier.fillMaxSize()) {
		when (state) {
			LoadState.Loading -> Text(
				text = stringResource(R.string.je_loading),
				color = secondaryTextColor(),
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
				EmptyState(emptyMessage, emptyAction, Modifier.align(Alignment.Center))
			} else content(state.value)
		}
	}
}

@Composable
private fun EmptyState(message: String, action: EmptyAction?, modifier: Modifier) {
	Column(
		modifier = modifier,
		horizontalAlignment = Alignment.CenterHorizontally,
		verticalArrangement = Arrangement.spacedBy(16.dp),
	) {
		Text(text = message, color = secondaryTextColor(), fontSize = 18.sp)
		if (action == null) return@Column

		val actionFocus = remember { FocusRequester() }
		val pageFocused = LocalPageFocused.current
		Button(onClick = action.onClick, modifier = Modifier.focusRequester(actionFocus)) { Text(action.label) }
		LaunchedEffect(Unit) {
			// Let the focus settle first: a removed row's focus may still be moving to the page's own controls
			withFrameNanos { }
			if (pageFocused?.value != true) runCatching { actionFocus.requestFocus() }
		}
	}
}
