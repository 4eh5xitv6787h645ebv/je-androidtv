package org.jellyfin.androidtv.enhanced.ui.design

import android.widget.ImageView
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.ui.base.Icon
import org.jellyfin.androidtv.ui.base.JellyfinTheme
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.composable.AsyncImage

/** What a [ListRow] shows on its left. Artwork ([isArtwork]) switches the row to the ring focus treatment. */
sealed interface RowLeading {
	data object None : RowLeading

	/** A 2:3 poster; [fallbackIcon] when there is no (safe) artwork. */
	data class Poster(val url: String?, @DrawableRes val fallbackIcon: Int? = null) : RowLeading

	/** A 16:9 thumbnail, 96 × 54 dp (bookmarks at the saved time). */
	data class Thumb(val url: String?, @DrawableRes val fallbackIcon: Int? = null) : RowLeading

	/** A 48 dp round user picture, or [initial] on a plain circle. */
	data class Avatar(val url: String?, val initial: String) : RowLeading

	/** A 24 dp icon, for text rows (no artwork: the row keeps the inversion focus). */
	data class Icon(@DrawableRes val res: Int) : RowLeading

	val isArtwork: Boolean get() = this is Poster || this is Thumb || this is Avatar
}

/**
 * A trailing action inside a [ListRow], focusable on its own (D-pad right from the row). Icon-only by default
 * ([label] is then the content description); [showLabel] (or no [icon]) makes it a text [JeButton] ("Restore",
 * "Turn off"). [busy] shows a spinner and ignores clicks. [modifier] goes on the button (a focus requester).
 */
@Immutable
class RowAction(
	@DrawableRes val icon: Int?,
	val label: String,
	val onClick: () -> Unit,
	val busy: Boolean = false,
	val showLabel: Boolean = icon == null,
	val modifier: Modifier = Modifier,
)

/**
 * Row heights, fixed for [Regular] and [Large] so every card of a grid row has the same height whatever its content
 * or actions (spec §9.5). [Compact] (no leading poster) grows from 64 dp.
 */
enum class RowDensity(val height: Dp, val posterWidth: Dp, val posterHeight: Dp) {
	Compact(64.dp, 40.dp, 60.dp),
	Regular(108.dp, 56.dp, 84.dp),
	Large(116.dp, 60.dp, 92.dp),
}

/**
 * The list row / list card (spec §6.3) of every list page: Requests, Calendar, Bookmarks, Activity, Spoiler Guard,
 * Hidden Content, the queue. A [JeColors.surface] container with [leading] art, the [title] (+ [titleTrailing], the
 * year), status [badges], an optional orange [warning] line, up to two [supporting] caption lines and an optional
 * [progress] bar; [actions] sit inside the row on the right, each focusable.
 *
 * Focus: the ring treatment when the row has artwork, badges or progress (inversion would wash them out), otherwise
 * upstream's inversion. The main part of the row is one focus target running [onClick] (and [onMenu] on long press
 * or the menu key); [mainModifier] goes on it, e.g. a `ListFocus` requester. [enabled] false disables the [actions]
 * too.
 */
@Composable
fun ListRow(
	title: String,
	onClick: () -> Unit,
	modifier: Modifier = Modifier,
	titleTrailing: String? = null,
	supporting: List<String> = emptyList(),
	leading: RowLeading = RowLeading.None,
	badges: List<StatusBadgeSpec> = emptyList(),
	warning: String? = null,
	progress: Float? = null,
	actions: List<RowAction> = emptyList(),
	onMenu: (() -> Unit)? = null,
	density: RowDensity = RowDensity.Regular,
	enabled: Boolean = true,
	mainModifier: Modifier = Modifier,
) {
	val focus = rememberFocusedState()
	val ring = leading.isArtwork || badges.isNotEmpty() || progress != null
	val inverted = !ring && focus.focused
	val scheme = JellyfinTheme.colorScheme
	val shape = JeShapes.container
	val titleColor = if (inverted) scheme.onButtonFocused else JeColors.onSurface
	// Full strength on the inverted fill: translucent dark text drops below 4.5:1 there
	val captionColor = if (inverted) scheme.onButtonFocused else JeColors.textSecondary
	val padding = if (density == RowDensity.Compact) JeSpace.rowPaddingCompact else JeSpace.rowPadding

	Row(
		modifier = modifier
			.fillMaxWidth()
			.then(if (density == RowDensity.Compact) Modifier.heightIn(min = density.height) else Modifier.height(density.height))
			.then(
				if (ring) Modifier.jeRingContainer(focus.focused, shape)
				else Modifier.background(if (inverted) scheme.buttonFocused else JeColors.surface, shape)
			)
			.jeDisabled(enabled),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Row(
			modifier = mainModifier
				.weight(1f)
				.fillMaxHeight()
				.onMenuKey(onMenu)
				.combinedClickable(
					interactionSource = focus.interactionSource,
					indication = null,
					enabled = enabled,
					role = Role.Button,
					onClick = onClick,
					onLongClick = onMenu,
				)
				.padding(start = padding, top = padding, bottom = padding, end = if (actions.isEmpty()) padding else 0.dp),
			horizontalArrangement = Arrangement.spacedBy(16.dp),
			verticalAlignment = Alignment.CenterVertically,
		) {
			RowLeadingContent(leading, density, if (inverted) scheme.onButtonFocused else JeColors.textSecondary)
			Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
				Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
					Text(
						text = title,
						style = JeType.cardTitle,
						color = titleColor,
						maxLines = 1,
						overflow = TextOverflow.Ellipsis,
						modifier = Modifier.alignByBaseline().weight(1f, fill = false),
					)
					if (titleTrailing != null) {
						Text(
							text = titleTrailing,
							style = JeType.cardTitleTrailing,
							color = if (inverted) scheme.onButtonFocused else JeColors.textTertiary,
							maxLines = 1,
							modifier = Modifier.alignByBaseline(),
						)
					}
				}
				if (badges.isNotEmpty()) {
					Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
						for (badge in badges) StatusBadge(badge)
					}
				}
				if (warning != null) WarningLine(warning, if (inverted) scheme.onButtonFocused else JeColors.warningText)
				for (line in supporting.take(2)) {
					Text(text = line, style = JeType.caption, color = captionColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
				}
				if (progress != null) ProgressLine(progress, Modifier.padding(top = 2.dp))
			}
		}
		if (actions.isNotEmpty()) RowActions(actions, density, padding, enabled)
	}
}

@Composable
private fun RowActions(actions: List<RowAction>, density: RowDensity, padding: Dp, enabled: Boolean) {
	val stacked = density != RowDensity.Compact && actions.size == 2 && actions.none { it.showLabel }
	val content: @Composable () -> Unit = {
		for (action in actions) {
			if (action.showLabel || action.icon == null) {
				JeButton(text = action.label, onClick = action.onClick, icon = action.icon, enabled = enabled, busy = action.busy, modifier = action.modifier)
			} else {
				JeIconButton(
					icon = action.icon,
					contentDescription = action.label,
					onClick = action.onClick,
					enabled = enabled,
					busy = action.busy,
					modifier = action.modifier,
				)
			}
		}
	}
	if (stacked) {
		Column(
			modifier = Modifier.padding(horizontal = padding),
			verticalArrangement = Arrangement.spacedBy(8.dp),
		) { content() }
	} else {
		Row(
			modifier = Modifier.padding(horizontal = padding),
			horizontalArrangement = Arrangement.spacedBy(8.dp),
			verticalAlignment = Alignment.CenterVertically,
		) { content() }
	}
}

@Composable
private fun RowLeadingContent(leading: RowLeading, density: RowDensity, iconColor: Color) {
	when (leading) {
		RowLeading.None -> Unit
		is RowLeading.Poster -> RowImage(leading.url, leading.fallbackIcon, Modifier.size(density.posterWidth, density.posterHeight), 2f / 3f)
		is RowLeading.Thumb -> RowImage(leading.url, leading.fallbackIcon, Modifier.size(96.dp, 54.dp), 16f / 9f)
		is RowLeading.Avatar -> Box(
			modifier = Modifier
				.size(48.dp)
				.clip(CircleShape)
				.background(JeColors.countPill),
			contentAlignment = Alignment.Center,
		) {
			if (leading.url != null) {
				AsyncImage(url = leading.url, aspectRatio = 1f, scaleType = ImageView.ScaleType.CENTER_CROP, modifier = Modifier.fillMaxSize())
			} else {
				Text(text = leading.initial.take(1).uppercase(), style = JeType.cardTitle)
			}
		}

		is RowLeading.Icon -> Icon(painterResource(leading.res), contentDescription = null, tint = iconColor, modifier = Modifier.size(24.dp))
	}
}

/** A clipped image with a placeholder fill, or [fallbackIcon] centred when there is no [url]. */
@Composable
internal fun RowImage(url: String?, @DrawableRes fallbackIcon: Int?, modifier: Modifier, aspectRatio: Float) {
	Box(
		modifier = modifier
			.clip(JeShapes.image)
			.background(JeColors.imagePlaceholder),
		contentAlignment = Alignment.Center,
	) {
		if (url != null) {
			AsyncImage(url = url, aspectRatio = aspectRatio, scaleType = ImageView.ScaleType.CENTER_CROP, modifier = Modifier.fillMaxSize())
		} else if (fallbackIcon != null) {
			Icon(painterResource(fallbackIcon), contentDescription = null, tint = JeColors.textTertiary, modifier = Modifier.size(24.dp))
		}
	}
}

/** One orange warning line with its icon ("Approved, but not in Radarr/Sonarr"). */
@Composable
fun WarningLine(text: String, color: Color = JeColors.warningText, modifier: Modifier = Modifier) {
	Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
		Icon(painterResource(R.drawable.je_design_ic_warning), contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
		Text(text = text, style = JeType.caption, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
	}
}

/** A 4 dp progress bar ([fraction] 0–1) in the accent colour on a faint track; queue items, download progress. */
@Composable
fun ProgressLine(fraction: Float, modifier: Modifier = Modifier, color: Color = JeColors.accent) {
	Box(
		modifier = modifier
			.fillMaxWidth()
			.height(4.dp)
			.clip(JeShapes.badge)
			.background(Color.White.copy(alpha = 0.15f)),
	) {
		Box(
			modifier = Modifier
				.fillMaxWidth(fraction.coerceIn(0f, 1f))
				.fillMaxHeight()
				.background(color),
		)
	}
}
