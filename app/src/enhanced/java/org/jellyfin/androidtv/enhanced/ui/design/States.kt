package org.jellyfin.androidtv.enhanced.ui.design

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.enhanced.ui.hub.EmptyAction
import org.jellyfin.androidtv.enhanced.ui.hub.LoadState
import org.jellyfin.androidtv.enhanced.ui.hub.rememberBackAction
import org.jellyfin.androidtv.ui.base.CircularProgressIndicator
import org.jellyfin.androidtv.ui.base.Icon
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.design.Tokens

/**
 * Loading / error / empty / content switch (spec §6.10–6.12), the design-system version of
 * `ui.hub.LoadStateContent` with the same leading parameters, so migrating is an import change. Loading shows a
 * [LoadingSkeleton] shaped like the page ([loading]) after 300 ms; errors show an [ErrorState] with Retry focused;
 * an empty value (when [empty] says so) shows an [EmptyState] whose [emptyAction] takes the focus unless the page's
 * own controls already have it.
 */
@Composable
fun <T> LoadStateContent(
	state: LoadState<T>,
	modifier: Modifier = Modifier,
	empty: ((T) -> Boolean)? = null,
	emptyMessage: String = stringResource(R.string.je_design_nothing_here),
	emptyAction: EmptyAction? = rememberBackAction(),
	emptyBody: String? = null,
	@DrawableRes emptyIcon: Int? = null,
	loading: SkeletonShape = SkeletonShape.Rows(),
	content: @Composable (T) -> Unit,
) {
	Box(modifier = modifier.fillMaxSize()) {
		when (state) {
			LoadState.Loading -> LoadingSkeleton(loading)
			is LoadState.Error -> ErrorState(message = state.message, onRetry = state.retry)
			is LoadState.Ready -> if (empty != null && empty(state.value)) {
				EmptyState(title = emptyMessage, icon = emptyIcon, body = emptyBody, action = emptyAction)
			} else content(state.value)
		}
	}
}

/**
 * A centred empty page (spec §6.10): optional 48 dp [icon], [title], a [body] that says how to fill the page
 * ("Press Bookmark in the player to save a moment."), and a focusable [action] (Back by default) so a D-pad user
 * always lands on something. The action takes the focus unless something else on the [PageScaffold] has it.
 * [secondary] adds a second button after it (Catch Up's Undo).
 */
@Composable
fun EmptyState(
	title: String,
	modifier: Modifier = Modifier,
	@DrawableRes icon: Int? = null,
	body: String? = null,
	action: EmptyAction? = rememberBackAction(),
	secondary: EmptyAction? = null,
) {
	val actionFocus = remember { FocusRequester() }
	val pageFocused = LocalPageFocused.current
	StateLayout(
		modifier = modifier,
		icon = icon?.let { { Icon(painterResource(it), contentDescription = null, tint = JeColors.textTertiary, modifier = Modifier.size(48.dp)) } },
		title = title,
		body = body,
	) {
		if (action == null) return@StateLayout
		Row(horizontalArrangement = Arrangement.spacedBy(JeSpace.actionGap)) {
			JeButton(text = action.label, onClick = action.onClick, modifier = Modifier.focusRequester(actionFocus))
			if (secondary != null) JeButton(text = secondary.label, onClick = secondary.onClick)
		}
		LaunchedEffect(Unit) {
			// Let the focus settle first: a removed row's focus may still be moving to the page's own controls
			withFrameNanos { }
			if (pageFocused?.value != true) runCatching { actionFocus.requestFocus() }
		}
	}
}

/**
 * A centred error (spec §6.11): warning icon, a generic title, the server's [message] (when it sent one) and a
 * focused Retry, plus an optional [secondary] action (Back, "Open settings"…). [retrying] shows a spinner on Retry
 * while it runs, keeping the focus there.
 */
@Composable
fun ErrorState(
	message: String?,
	onRetry: () -> Unit,
	modifier: Modifier = Modifier,
	secondary: EmptyAction? = null,
	retrying: Boolean = false,
) {
	val retryFocus = remember { FocusRequester() }
	StateLayout(
		modifier = modifier,
		icon = {
			Icon(painterResource(R.drawable.je_design_ic_warning), contentDescription = null, tint = Tokens.Color.colorOrange400, modifier = Modifier.size(48.dp))
		},
		title = stringResource(R.string.je_design_error_generic),
		body = message,
	) {
		Row(horizontalArrangement = Arrangement.spacedBy(JeSpace.actionGap)) {
			JeButton(text = stringResource(R.string.je_design_retry), onClick = onRetry, busy = retrying, modifier = Modifier.focusRequester(retryFocus))
			if (secondary != null) JeButton(text = secondary.label, onClick = secondary.onClick)
		}
		LaunchedEffect(Unit) { runCatching { retryFocus.requestFocus() } }
	}
}

@Composable
private fun StateLayout(
	modifier: Modifier,
	icon: (@Composable () -> Unit)?,
	title: String,
	body: String?,
	actions: @Composable () -> Unit,
) {
	Box(modifier = modifier.fillMaxSize().padding(horizontal = JeSpace.pageHorizontal), contentAlignment = Alignment.Center) {
		Column(horizontalAlignment = Alignment.CenterHorizontally) {
			if (icon != null) {
				icon()
				Box(Modifier.height(16.dp))
			}
			Text(text = title, style = JeType.sectionHeader, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
			if (body != null) {
				Box(Modifier.height(6.dp))
				Text(
					text = body,
					style = JeType.caption,
					textAlign = TextAlign.Center,
					maxLines = 2,
					overflow = TextOverflow.Ellipsis,
					modifier = Modifier.widthIn(max = 480.dp),
				)
			}
			Box(Modifier.height(20.dp))
			actions()
		}
	}
}

/** An inline error for the end of a paged list (replaces `PagedGrid`'s footer): message and a Retry button. */
@Composable
fun ErrorRow(
	message: String?,
	onRetry: () -> Unit,
	modifier: Modifier = Modifier,
) {
	Row(
		modifier = modifier
			.fillMaxWidth()
			.padding(horizontal = JeSpace.pageHorizontal, vertical = JeSpace.rowPadding),
		horizontalArrangement = Arrangement.spacedBy(JeSpace.actionGap),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Icon(painterResource(R.drawable.je_design_ic_warning), contentDescription = null, tint = Tokens.Color.colorOrange400, modifier = Modifier.size(20.dp))
		Text(
			text = message ?: stringResource(R.string.je_design_error_generic),
			style = JeType.caption,
			maxLines = 2,
			overflow = TextOverflow.Ellipsis,
			modifier = Modifier.weight(1f, fill = false),
		)
		JeButton(text = stringResource(R.string.je_design_retry), onClick = onRetry)
	}
}

/** A small spinner row for "loading more" at the end of a paged list. */
@Composable
fun LoadingRow(modifier: Modifier = Modifier) {
	Box(modifier = modifier.fillMaxWidth().padding(JeSpace.rowPadding), contentAlignment = Alignment.Center) {
		CircularProgressIndicator(color = JeColors.textTertiary, modifier = Modifier.size(24.dp))
	}
}

/** The layout a [LoadingSkeleton] imitates, so nothing jumps when the content arrives. */
sealed interface SkeletonShape {
	/** A list of [ListRow]s. */
	data class Rows(val count: Int = 5, val density: RowDensity = RowDensity.Regular) : SkeletonShape

	/** A grid of [ListRow] cards or tiles of [height]. */
	data class Grid(val columns: Int = 2, val rows: Int = 3, val height: Dp = RowDensity.Large.height) : SkeletonShape

	/** A [HeroScaffold] header. */
	data object Hero : SkeletonShape
}

/**
 * Static [JeColors.surface] blocks shaped like the page (spec §6.12), with one slow alpha pulse for the whole
 * layer. Appears only after [delayMillis] so fast loads don't flash. Not focusable.
 */
@Composable
fun LoadingSkeleton(
	shape: SkeletonShape = SkeletonShape.Rows(),
	modifier: Modifier = Modifier,
	delayMillis: Long = 300,
) {
	var visible by remember { mutableStateOf(delayMillis <= 0) }
	LaunchedEffect(delayMillis) {
		delay(delayMillis)
		visible = true
	}
	if (!visible) return

	val transition = rememberInfiniteTransition()
	val pulse by transition.animateFloat(
		initialValue = 0.6f,
		targetValue = 0.9f,
		animationSpec = infiniteRepeatable(tween(durationMillis = 1200), RepeatMode.Reverse),
	)
	Box(modifier = modifier.fillMaxSize().graphicsLayer { alpha = pulse }) {
		when (shape) {
			is SkeletonShape.Rows -> Column(
				modifier = Modifier.padding(horizontal = JeSpace.pageHorizontal),
				verticalArrangement = Arrangement.spacedBy(JeSpace.rowGap),
			) {
				repeat(shape.count) { Block(Modifier.fillMaxWidth().height(shape.density.height)) }
			}

			is SkeletonShape.Grid -> Column(
				modifier = Modifier.padding(horizontal = JeSpace.pageHorizontal),
				verticalArrangement = Arrangement.spacedBy(JeSpace.gridGap),
			) {
				repeat(shape.rows) {
					Row(horizontalArrangement = Arrangement.spacedBy(JeSpace.gridGap)) {
						repeat(shape.columns) { Block(Modifier.weight(1f).height(shape.height)) }
					}
				}
			}

			SkeletonShape.Hero -> Column(
				modifier = Modifier.padding(start = JeSpace.pageHorizontal, top = JeSpace.heroTop),
				verticalArrangement = Arrangement.spacedBy(14.dp),
			) {
				Block(Modifier.size(220.dp, 20.dp))
				Block(Modifier.size(420.dp, 52.dp))
				Block(Modifier.size(320.dp, 20.dp))
				Block(Modifier.size(560.dp, 66.dp))
				Row(horizontalArrangement = Arrangement.spacedBy(JeSpace.labeledActionGap), verticalAlignment = Alignment.CenterVertically) {
					Block(Modifier.size(160.dp, 44.dp), JeShapes.pill)
					repeat(3) { Block(Modifier.size(48.dp), CircleShape) }
				}
			}
		}
	}
}

@Composable
private fun Block(modifier: Modifier, shape: Shape = JeShapes.container) {
	Box(modifier = modifier.background(JeColors.surface, shape))
}
