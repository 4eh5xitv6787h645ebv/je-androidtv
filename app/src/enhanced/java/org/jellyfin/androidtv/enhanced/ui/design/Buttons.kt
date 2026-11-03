package org.jellyfin.androidtv.enhanced.ui.design

import androidx.annotation.DrawableRes
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.jellyfin.androidtv.ui.base.CircularProgressIndicator
import org.jellyfin.androidtv.ui.base.Icon
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.button.Button
import org.jellyfin.androidtv.ui.base.button.IconButton

/**
 * The text button (spec §6.8): upstream's pill `Button` with an optional 20 dp leading [icon]. There is one style for
 * every text button, as upstream: emphasis comes from order (first) and the icon, never from a light resting fill
 * (light means focus). Destructive actions use the same style and confirm through [JeConfirmDialog].
 *
 * [busy] swaps the icon for a spinner and ignores clicks and long clicks without disabling the button, so a focused
 * button never loses the focus mid-action.
 */
@Composable
fun JeButton(
	text: String,
	onClick: () -> Unit,
	modifier: Modifier = Modifier,
	@DrawableRes icon: Int? = null,
	enabled: Boolean = true,
	busy: Boolean = false,
	onLongClick: (() -> Unit)? = null,
	interactionSource: MutableInteractionSource? = null,
) {
	Button(
		onClick = { if (!busy) onClick() },
		onLongClick = onLongClick?.let { longClick -> { if (!busy) longClick() } },
		enabled = enabled,
		interactionSource = interactionSource,
		modifier = modifier,
	) {
		if (busy) {
			CircularProgressIndicator(modifier = Modifier.size(20.dp))
			Spacer(Modifier.width(10.dp))
		} else if (icon != null) {
			Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(20.dp))
			Spacer(Modifier.width(10.dp))
		}
		Text(text = text, maxLines = 1, overflow = TextOverflow.Ellipsis)
	}
}

/**
 * Upstream's round `IconButton` at [size] (40 dp in rows, 48 dp in hero action rows) with a [contentDescription]
 * for accessibility. [busy] shows a spinner in place of the icon and ignores clicks (the button stays focusable).
 * Use it for in-row actions (Approve ✓, Decline ✕, ⋯) through [RowAction].
 */
@Composable
fun JeIconButton(
	@DrawableRes icon: Int,
	contentDescription: String,
	onClick: () -> Unit,
	modifier: Modifier = Modifier,
	enabled: Boolean = true,
	busy: Boolean = false,
	size: Dp = 40.dp,
	interactionSource: MutableInteractionSource? = null,
) {
	val iconSize = if (size >= 48.dp) 24.dp else 20.dp
	IconButton(
		onClick = { if (!busy) onClick() },
		enabled = enabled,
		contentPadding = PaddingValues((size - iconSize) / 2),
		interactionSource = interactionSource,
		modifier = modifier,
	) {
		if (busy) CircularProgressIndicator(modifier = Modifier.size(iconSize))
		else Icon(painterResource(icon), contentDescription = contentDescription, modifier = Modifier.size(iconSize))
	}
}

/**
 * A 48 dp round icon button with its label underneath (max 2 lines, 88 dp wide), the look of upstream's details
 * actions. Use it for the secondary actions of a hero screen ([ActionRow]): Spoiler Guard, Report a problem, Hide,
 * More, Catch Up's Details / Watched / Watchlist / Dismiss.
 */
@Composable
fun LabeledIconButton(
	@DrawableRes icon: Int,
	label: String,
	onClick: () -> Unit,
	modifier: Modifier = Modifier,
	enabled: Boolean = true,
	busy: Boolean = false,
) {
	val focus = rememberFocusedState()
	Column(
		modifier = modifier.width(88.dp),
		horizontalAlignment = Alignment.CenterHorizontally,
	) {
		JeIconButton(
			icon = icon,
			contentDescription = label,
			onClick = onClick,
			enabled = enabled,
			busy = busy,
			size = 48.dp,
			interactionSource = focus.interactionSource,
		)
		Spacer(Modifier.height(8.dp))
		Text(
			text = label,
			style = JeType.caption,
			color = if (focus.focused) JeColors.onSurface else JeColors.textSecondary,
			textAlign = TextAlign.Center,
			maxLines = 2,
			overflow = TextOverflow.Ellipsis,
			modifier = Modifier.jeDisabled(enabled),
		)
	}
}

/** One secondary action of an [ActionRow]. */
@Immutable
class LabeledIconButtonSpec(
	@DrawableRes val icon: Int,
	val label: String,
	val onClick: () -> Unit,
	val enabled: Boolean = true,
	val busy: Boolean = false,
	val modifier: Modifier = Modifier,
)

/**
 * The action row of a hero screen (spec §6.8): the [primary] [JeButton] first, then the [secondary]
 * [LabeledIconButton]s, top-aligned. Temporary actions (Catch Up's Undo) are added as the last spec while they
 * apply rather than shown disabled, and never take the focus by themselves. Coming back to the row with the D-pad
 * lands on the button that last had the focus.
 */
@Composable
fun ActionRow(
	primary: @Composable () -> Unit,
	secondary: List<LabeledIconButtonSpec>,
	modifier: Modifier = Modifier,
) {
	Row(
		modifier = modifier
			.focusRestorer()
			.focusGroup(),
		horizontalArrangement = Arrangement.spacedBy(JeSpace.labeledActionGap),
		verticalAlignment = Alignment.Top,
	) {
		// Centre the pill on the 48 dp icon circles
		Box(modifier = Modifier.height(48.dp).padding(end = 4.dp), contentAlignment = Alignment.CenterStart) { primary() }
		for (action in secondary) {
			LabeledIconButton(
				icon = action.icon,
				label = action.label,
				onClick = action.onClick,
				enabled = action.enabled,
				busy = action.busy,
				modifier = action.modifier,
			)
		}
	}
}
