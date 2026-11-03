package org.jellyfin.androidtv.enhanced.ui.design

import android.widget.ImageView
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.ui.base.Icon
import org.jellyfin.androidtv.ui.base.JellyfinTheme
import org.jellyfin.androidtv.ui.base.LocalTextStyle
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.button.ButtonBase
import org.jellyfin.androidtv.ui.base.button.ButtonDefaults
import org.jellyfin.androidtv.ui.base.popover.Popover
import org.jellyfin.androidtv.ui.base.popover.PopoverMenu
import org.jellyfin.androidtv.ui.base.popover.PopoverMenuCheckboxItem
import org.jellyfin.androidtv.ui.composable.AsyncImage

/** One tab of a [JeTabRow]; [count] shows after the label when set ("Requests 12"). */
@Immutable
data class TabSpec(val label: String, val count: Int? = null)

/**
 * Text tabs with an underline (spec §6.6): the selected tab is light with a 3 dp cyan underline, the focused tab gets
 * upstream's inversion pill (its underline stays), and a divider runs under the row. [onSelect] runs on click, not on
 * focus, so moving along the row with the D-pad doesn't reload pages. Entering the row from below lands on the last
 * focused tab, or on the selected one the first time. Tabs wider than the page scroll horizontally, keeping the focused
 * tab in view.
 *
 * Use it for page-level tabs (Requests / Watchlist / Queue…, Activity's Watched / Favorited / Reviewed).
 */
@Composable
fun JeTabRow(
	tabs: List<TabSpec>,
	selected: Int,
	onSelect: (Int) -> Unit,
	modifier: Modifier = Modifier,
) {
	val requesters = remember(tabs.size) { List(tabs.size) { FocusRequester() } }
	val fallback = requesters.getOrNull(selected) ?: FocusRequester.Default
	Column(modifier = modifier.fillMaxWidth()) {
		Row(
			// The pill's padding sits outside the page edge so the labels line up with the page title
			modifier = Modifier
				.offset(x = -TabPadding)
				// Focused tabs bring themselves into view inside the scroll container
				.horizontalScroll(rememberScrollState())
				.focusRestorer(fallback)
				.focusGroup(),
			horizontalArrangement = Arrangement.spacedBy(4.dp),
		) {
			tabs.forEachIndexed { index, tab ->
				JeTab(tab, index == selected, { onSelect(index) }, Modifier.focusRequester(requesters[index]))
			}
		}
		Box(
			modifier = Modifier
				.fillMaxWidth()
				.height(1.dp)
				.background(JeColors.divider),
		)
	}
}

private val TabPadding = 14.dp

@Composable
private fun JeTab(tab: TabSpec, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
	val focus = rememberFocusedState()
	val scheme = JellyfinTheme.colorScheme
	val content = when {
		focus.focused -> scheme.onButtonFocused
		selected -> JeColors.onSurface
		else -> JeColors.textTertiary
	}
	Column(modifier = Modifier.width(IntrinsicSize.Max)) {
		Row(
			modifier = modifier
				.height(36.dp)
				.background(if (focus.focused) scheme.buttonFocused else Color.Transparent, JeShapes.pill)
				.clickable(interactionSource = focus.interactionSource, indication = null, role = Role.Tab, onClick = onClick)
				.padding(horizontal = TabPadding),
			horizontalArrangement = Arrangement.spacedBy(8.dp),
			verticalAlignment = Alignment.CenterVertically,
		) {
			Text(text = tab.label, style = JeType.tab, color = content, maxLines = 1)
			if (tab.count != null) {
				Text(
					text = tab.count.toString(),
					style = JeType.badge.copy(fontWeight = FontWeight.W500),
					color = if (focus.focused) content else JeColors.textTertiary,
					maxLines = 1,
				)
			}
		}
		Spacer(Modifier.height(5.dp))
		Box(
			modifier = Modifier
				.padding(horizontal = TabPadding)
				.fillMaxWidth()
				.height(3.dp)
				.background(if (selected) JeColors.accent else Color.Transparent, JeShapes.badge),
		)
	}
}

/**
 * A pill filter (spec §6.6). Unselected: outlined, light grey text. Selected: `buttonActive` fill, check icon and
 * light text. Focused: upstream inversion, and a selected chip keeps its check, so selected and focused can always be
 * told apart. [count] shows after the label ("Pending 3"); [leadingIcon] after the check ("Only mine" person icon).
 *
 * Use it for every filter or toggle chip row (Requests filters, Calendar sources/types, Catch Up All/Movies/Shows,
 * Activity filters). Put a [ChipSeparator] before toggle chips that aren't part of the exclusive group.
 */
@Composable
fun FilterChip(
	label: String,
	selected: Boolean,
	onClick: () -> Unit,
	modifier: Modifier = Modifier,
	count: Int? = null,
	@DrawableRes leadingIcon: Int? = null,
	@DrawableRes trailingIcon: Int? = null,
	enabled: Boolean = true,
) {
	val focus = rememberFocusedState()
	val scheme = JellyfinTheme.colorScheme
	val colors = ButtonDefaults.colors(
		containerColor = if (selected) scheme.buttonActive else Color.Transparent,
		contentColor = if (selected) JeColors.onSurface else JeColors.textSecondary,
		disabledContainerColor = if (selected) scheme.buttonDisabled else Color.Transparent,
	)
	val outlined = !selected && !focus.focused
	ButtonBase(
		onClick = onClick,
		enabled = enabled,
		shape = JeShapes.pill,
		colors = colors,
		interactionSource = focus.interactionSource,
		modifier = modifier
			.height(ChipHeight)
			.then(if (outlined) Modifier.border(1.dp, JeColors.outline, JeShapes.pill) else Modifier),
	) {
		Row(
			modifier = Modifier
				.fillMaxHeight()
				.padding(horizontal = 13.dp),
			horizontalArrangement = Arrangement.spacedBy(6.dp),
			verticalAlignment = Alignment.CenterVertically,
		) {
			if (selected) Icon(painterResource(R.drawable.je_design_ic_check), contentDescription = null, modifier = Modifier.size(16.dp))
			if (leadingIcon != null) Icon(painterResource(leadingIcon), contentDescription = null, modifier = Modifier.size(16.dp))
			Text(
				text = label,
				style = LocalTextStyle.current.merge(JeType.chip),
				fontWeight = if (selected) FontWeight.W500 else FontWeight.W400,
				maxLines = 1,
			)
			if (count != null) {
				Text(
					text = count.toString(),
					style = LocalTextStyle.current.merge(JeType.chip),
					color = if (focus.focused) LocalTextStyle.current.color else LocalTextStyle.current.color.copy(alpha = 0.7f),
					maxLines = 1,
				)
			}
			if (trailingIcon != null) Icon(painterResource(trailingIcon), contentDescription = null, modifier = Modifier.size(18.dp))
		}
	}
}

private val ChipHeight = 30.dp

/**
 * A [FilterChip]-shaped pill showing "[label]: value ▾" that opens upstream's popover menu with [options] (checked:
 * the [selected] one). Replaces chips that cycle through values on each press (Hidden Content's Where / Type).
 */
@Composable
fun ChoiceChip(
	label: String,
	options: List<String>,
	selected: Int,
	onSelect: (Int) -> Unit,
	modifier: Modifier = Modifier,
	enabled: Boolean = true,
) {
	var expanded by remember { mutableStateOf(false) }
	Box(modifier = modifier) {
		FilterChip(
			label = stringResource(R.string.je_design_choice_value, label, options.getOrNull(selected).orEmpty()),
			selected = false,
			onClick = { expanded = true },
			trailingIcon = R.drawable.je_design_ic_arrow_drop_down,
			enabled = enabled,
		)
		Popover(
			expanded = expanded,
			onDismissRequest = { expanded = false },
			alignment = Alignment.BottomCenter,
			offset = DpOffset(0.dp, 4.dp),
		) {
			PopoverMenu {
				options.forEachIndexed { index, option ->
					PopoverMenuCheckboxItem(
						selected = index == selected,
						onClick = {
							expanded = false
							onSelect(index)
						},
					) {
						Text(option)
					}
				}
			}
		}
	}
}

/**
 * A chip row: 8 dp gaps, and re-entering it with the D-pad returns to the chip that last had the focus. Chips wider
 * than the row scroll horizontally, keeping the focused chip in view (don't give children a weight). A [modifier] that
 * already scrolls sideways keeps working: the row then leaves the scrolling to it.
 */
@Composable
fun ChipRow(
	modifier: Modifier = Modifier,
	content: @Composable RowScope.() -> Unit,
) {
	BoxWithConstraints(modifier = modifier) {
		// Nesting two sideways scrolls throws, and an unbounded width means the caller scrolls already
		val scroll = if (constraints.hasBoundedWidth) Modifier.horizontalScroll(rememberScrollState()) else Modifier
		Row(
			modifier = scroll
				.focusRestorer()
				.focusGroup(),
			horizontalArrangement = Arrangement.spacedBy(JeSpace.chipGap),
			verticalAlignment = Alignment.CenterVertically,
			content = content,
		)
	}
}

/** The 1 × 22 dp divider between an exclusive chip group and the toggle chips after it ("Only mine"). */
@Composable
fun ChipSeparator(modifier: Modifier = Modifier) {
	Box(
		modifier = modifier
			.padding(horizontal = 4.dp)
			.size(1.dp, 22.dp)
			.background(JeColors.divider),
	)
}

/** What an [InfoChip] shows before its text. */
sealed interface InfoLeading {
	/** A coloured dot, e.g. a rating's brand colour ([JeColors.ratingFresh]). */
	data class Dot(val color: Color) : InfoLeading

	/** A drawable; [tint] Unspecified keeps the text colour. */
	data class Icon(@DrawableRes val res: Int, val tint: Color = Color.Unspecified) : InfoLeading

	/** A small remote image, e.g. a streaming provider's logo. */
	data class Image(val url: String) : InfoLeading
}

/**
 * A **non-focusable** fact (spec §6.9): ratings, awards, streaming providers, age rating. Rounded rectangle, never a
 * pill, because a pill means "press me". Use [overArt] on backdrops. Group several behind an [Overline] label
 * ("STREAMING IN US"). If a chip must be actionable, use a [FilterChip] instead.
 */
@Composable
fun InfoChip(
	text: String,
	modifier: Modifier = Modifier,
	leading: InfoLeading? = null,
	overArt: Boolean = false,
) {
	Row(
		modifier = modifier
			.height(30.dp)
			.background(if (overArt) JeColors.surfaceOverArt else JeColors.surface, JeShapes.image)
			.padding(horizontal = 10.dp),
		horizontalArrangement = Arrangement.spacedBy(6.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		when (leading) {
			is InfoLeading.Dot -> Box(Modifier.size(10.dp).background(leading.color, CircleShape))
			is InfoLeading.Icon -> Icon(
				painterResource(leading.res),
				contentDescription = null,
				tint = if (leading.tint == Color.Unspecified) JeColors.onSurface else leading.tint,
				modifier = Modifier.size(16.dp),
			)

			is InfoLeading.Image -> AsyncImage(
				url = leading.url,
				scaleType = ImageView.ScaleType.FIT_CENTER,
				modifier = Modifier.size(20.dp),
			)

			null -> Unit
		}
		Text(text = text, style = JeType.meta, color = JeColors.onSurface, maxLines = 1)
	}
}

/**
 * A status label (spec §6.7) in the [JeStatus] palette: 4 dp corners, 12 sp bold, a warning icon for
 * [JeStatus.Warning]. [text] overrides the status's default label. Not focusable; inside a focusable row or card use
 * the ring focus treatment so the colours stay legible.
 */
@Composable
fun StatusBadge(
	status: JeStatus,
	modifier: Modifier = Modifier,
	text: String? = null,
	compact: Boolean = false,
) = StatusBadge(StatusBadgeSpec(status, text), modifier, compact)

/** [StatusBadge] for a mapped status, e.g. `StatusBadge(JeStatuses.seerrMedia(status) ?: return)`. */
@Composable
fun StatusBadge(
	spec: StatusBadgeSpec,
	modifier: Modifier = Modifier,
	compact: Boolean = false,
) {
	val status = spec.status
	Row(
		modifier = modifier
			.background(status.container, JeShapes.badge)
			.padding(horizontal = if (compact) 5.dp else 7.dp, vertical = if (compact) 1.dp else 2.dp),
		horizontalArrangement = Arrangement.spacedBy(4.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		if (status.showsIcon) {
			Icon(painterResource(R.drawable.je_design_ic_warning), contentDescription = null, tint = status.content, modifier = Modifier.size(14.dp))
		}
		Text(
			text = spec.text ?: stringResource(spec.labelRes),
			style = JeType.badge,
			color = status.content,
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
		)
	}
}

/** A full-width 20 dp status band for the bottom edge of a poster (Seerr cards, Discover cards). */
@Composable
fun StatusRibbon(
	spec: StatusBadgeSpec,
	modifier: Modifier = Modifier,
) {
	Box(
		modifier = modifier
			.fillMaxWidth()
			.height(20.dp)
			.background(spec.status.container),
		contentAlignment = Alignment.Center,
	) {
		Text(
			text = spec.text ?: stringResource(spec.labelRes),
			style = JeType.badge,
			color = spec.status.content,
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
			modifier = Modifier.padding(horizontal = 4.dp),
		)
	}
}
