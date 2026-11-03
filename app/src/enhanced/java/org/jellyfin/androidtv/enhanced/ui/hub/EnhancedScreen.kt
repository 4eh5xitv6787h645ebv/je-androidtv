package org.jellyfin.androidtv.enhanced.ui.hub

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.enhanced.ui.LocalOnEnhancedHub
import org.jellyfin.androidtv.enhanced.ui.design.ChipRow
import org.jellyfin.androidtv.enhanced.ui.design.JeSpace
import org.jellyfin.androidtv.enhanced.ui.design.PageScaffold
import org.jellyfin.androidtv.ui.navigation.NavigationRepository
import org.koin.compose.koinInject
import org.jellyfin.androidtv.enhanced.ui.design.LoadStateContent as DesignLoadStateContent

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
 * The old page scaffold, now a thin wrapper over the design system's [PageScaffold] for pages that haven't moved to
 * it yet. [headerActions] keep their old place under the title (as a [ChipRow] at the top of the content); new pages
 * put filters in their content and ≤ 2 buttons in `PageScaffold(actions)`. [hub] is only set by the hub itself: on
 * every other page the Enhanced button leads back to it.
 */
@Composable
fun EnhancedScreen(
	title: String,
	modifier: Modifier = Modifier,
	subtitle: String? = null,
	headerActions: (@Composable () -> Unit)? = null,
	hub: Boolean = false,
	content: @Composable () -> Unit,
) {
	CompositionLocalProvider(LocalOnEnhancedHub provides hub) {
		PageScaffold(title = title, modifier = modifier, subtitle = subtitle) {
			if (headerActions == null) {
				content()
			} else {
				Column(modifier = Modifier.fillMaxSize()) {
					ChipRow(
						modifier = Modifier
							.fillMaxWidth()
							.padding(start = JeSpace.pageHorizontal, end = JeSpace.pageHorizontal, bottom = JeSpace.headerBottom),
					) { headerActions() }
					content()
				}
			}
		}
	}
}

/** A focusable action under an empty state, so D-pad users don't land on nothing. */
class EmptyAction(val label: String, val onClick: () -> Unit)

/** "Back", the default [EmptyAction]: leaves the page. */
@Composable
fun rememberBackAction(): EmptyAction {
	val navigationRepository = koinInject<NavigationRepository>()
	val label = stringResource(R.string.je_hub_back)
	return remember(navigationRepository, label) { EmptyAction(label) { navigationRepository.goBack() } }
}

/**
 * [value], or the last non-null value it had: what a dialog keeps showing while it fades out after its state was
 * cleared, so its texts don't flip to placeholders mid-animation.
 */
@Composable
fun <T : Any> rememberLastNonNull(value: T?): T? {
	val last = remember { LastValue<T>() }
	if (value != null) last.value = value
	return last.value
}

private class LastValue<T : Any> {
	var value: T? = null
}

/**
 * Loading / error (with a focused Retry) / empty / content switch: the design system's
 * [org.jellyfin.androidtv.enhanced.ui.design.LoadStateContent] (skeleton, error state, empty state with a focusable
 * action), kept here for the pages that still import this one.
 */
@Composable
fun <T> LoadStateContent(
	state: LoadState<T>,
	modifier: Modifier = Modifier,
	empty: ((T) -> Boolean)? = null,
	emptyMessage: String = stringResource(R.string.je_nothing_found),
	emptyAction: EmptyAction? = rememberBackAction(),
	content: @Composable (T) -> Unit,
) = DesignLoadStateContent(
	state = state,
	modifier = modifier,
	empty = empty,
	emptyMessage = emptyMessage,
	emptyAction = emptyAction,
	content = content,
)
