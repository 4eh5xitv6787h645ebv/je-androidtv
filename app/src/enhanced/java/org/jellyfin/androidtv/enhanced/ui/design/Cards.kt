package org.jellyfin.androidtv.enhanced.ui.design

import android.widget.ImageView
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jellyfin.androidtv.ui.base.Icon
import org.jellyfin.androidtv.ui.base.JellyfinTheme
import org.jellyfin.androidtv.ui.base.LocalTextStyle
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.button.ButtonBase
import org.jellyfin.androidtv.ui.base.button.ButtonDefaults
import org.jellyfin.androidtv.ui.composable.AsyncImage
import org.jellyfin.design.Tokens

/** Image shape of a [MediaCard]. */
enum class MediaAspect(val ratio: Float) {
	Poster(2f / 3f),
	Landscape(16f / 9f),
	Square(1f),
}

/**
 * An image card for **Compose** rows (spec §6.4): Seerr details cast, Catch Up's up-next, reviews, Discover genre /
 * studio tiles. Leanback rows keep upstream's card presenters. Sizes: poster 120 dp wide in rows (150 dp beside a
 * hero), landscape 240 dp, cast 120 dp with name and role under the image.
 *
 * Focus: ring around the image, and the title scrolls (marquee) while focused. [onClick] null makes a decorative,
 * non-focusable card (Catch Up's up-next stack); [imageAlpha] fades it. [badge] is drawn as a [StatusRibbon] on the
 * image's bottom edge, [progress] as a bar above it.
 */
@Composable
fun MediaCard(
	imageUrl: String?,
	title: String?,
	onClick: (() -> Unit)?,
	modifier: Modifier = Modifier,
	subtitle: String? = null,
	aspect: MediaAspect = MediaAspect.Poster,
	width: Dp = 120.dp,
	badge: StatusBadgeSpec? = null,
	progress: Float? = null,
	onMenu: (() -> Unit)? = null,
	blurHash: String? = null,
	@DrawableRes fallbackIcon: Int? = null,
	imageAlpha: Float = 1f,
) {
	val focus = rememberFocusedState()
	val focused = onClick != null && focus.focused
	val clickModifier = if (onClick == null) Modifier else Modifier
		.onMenuKey(onMenu)
		.combinedClickable(
			interactionSource = focus.interactionSource,
			indication = null,
			role = Role.Button,
			onClick = onClick,
			onLongClick = onMenu,
		)

	Column(modifier = modifier.width(width).then(clickModifier)) {
		Box(
			modifier = Modifier
				.size(width, width / aspect.ratio)
				.alpha(imageAlpha)
				.clip(JeShapes.image)
				.background(JeColors.imagePlaceholder)
				.jeFocusRing(focused, JeShapes.image),
			contentAlignment = Alignment.Center,
		) {
			if (imageUrl != null || blurHash != null) {
				AsyncImage(
					url = imageUrl,
					blurHash = blurHash,
					aspectRatio = aspect.ratio,
					scaleType = ImageView.ScaleType.CENTER_CROP,
					modifier = Modifier.fillMaxSize(),
				)
			} else if (fallbackIcon != null) {
				Icon(painterResource(fallbackIcon), contentDescription = null, tint = JeColors.textTertiary, modifier = Modifier.size(32.dp))
			}
			Column(modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
				if (progress != null) ProgressLine(progress, Modifier.padding(horizontal = 6.dp, vertical = 6.dp))
				if (badge != null) StatusRibbon(badge)
			}
		}
		if (title != null) {
			Spacer(Modifier.height(6.dp))
			Text(
				text = title,
				style = JeType.caption,
				color = JeColors.onSurface,
				maxLines = 1,
				overflow = if (focused) TextOverflow.Clip else TextOverflow.Ellipsis,
				modifier = if (focused) Modifier.basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 0) else Modifier,
			)
		}
		if (subtitle != null) {
			Text(text = subtitle, style = JeType.caption, color = JeColors.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
		}
	}
}

/**
 * A hub page tile (spec §6.5): 40 dp icon circle, title with an optional [count] pill after it ("3 pending",
 * "47 left"), and the description under both, using the full text width and up to three lines (the tile's minimum
 * height fits three, so tiles of a grid row line up whatever their copy). Inversion focus. Lay them out three per
 * row with 16 dp gaps.
 * Counts must come from already cached state: never block the hub on them.
 */
@Composable
fun HubTile(
	@DrawableRes icon: Int,
	title: String,
	description: String,
	onClick: () -> Unit,
	modifier: Modifier = Modifier,
	count: String? = null,
) {
	val focus = rememberFocusedState()
	val scheme = JellyfinTheme.colorScheme
	ButtonBase(
		onClick = onClick,
		shape = JeShapes.container,
		interactionSource = focus.interactionSource,
		colors = ButtonDefaults.colors(containerColor = JeColors.surface, contentColor = JeColors.onSurface),
		// 16 dp padding, 24 sp title, 2 dp gap and three 18 sp caption lines
		modifier = modifier.heightIn(min = 112.dp),
	) {
		Row(
			// Fills a taller tile (equal-height grid rows) and keeps the content centred in it
			modifier = Modifier
				.fillMaxSize()
				.padding(16.dp),
			horizontalArrangement = Arrangement.spacedBy(16.dp),
			verticalAlignment = Alignment.CenterVertically,
		) {
			Box(
				modifier = Modifier
					.size(40.dp)
					.background(if (focus.focused) Color.Black.copy(alpha = 0.12f) else JeColors.accent.copy(alpha = 0.16f), CircleShape),
				contentAlignment = Alignment.Center,
			) {
				Icon(
					painterResource(icon),
					contentDescription = null,
					tint = if (focus.focused) scheme.onButtonFocused else JeColors.accentOnDark,
					modifier = Modifier.size(22.dp),
				)
			}
			Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
				// The badge shares the title's line so the description keeps the full width below it
				Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
					Text(
						text = title,
						style = LocalTextStyle.current.merge(JeType.cardTitle.copy(color = Color.Unspecified, fontSize = 18.sp, lineHeight = 24.sp)),
						maxLines = 1,
						overflow = TextOverflow.Ellipsis,
						modifier = Modifier.weight(1f),
					)
					if (count != null) {
						Text(
							text = count,
							style = JeType.badge,
							color = JeColors.onSurface,
							maxLines = 1,
							modifier = Modifier
								.background(JeColors.countPill, JeShapes.pill)
								.padding(horizontal = 8.dp, vertical = 2.dp),
						)
					}
				}
				Text(
					text = description,
					style = JeType.caption,
					color = if (focus.focused) scheme.onButtonFocused else JeColors.textSecondary,
					maxLines = 3,
					overflow = TextOverflow.Ellipsis,
				)
			}
		}
	}
}

/**
 * A season card (spec §6.4): 160 × 90 dp, the season's art (or a plain surface) with the [name] and [episodes]
 * on a top scrim and the [status] badge bottom-left. Ring focus. [onClick] null makes it non-focusable.
 */
@Composable
fun SeasonCard(
	name: String,
	onClick: (() -> Unit)?,
	modifier: Modifier = Modifier,
	episodes: String? = null,
	status: StatusBadgeSpec? = null,
	imageUrl: String? = null,
) {
	val focus = rememberFocusedState()
	val focused = onClick != null && focus.focused
	val clickModifier = if (onClick == null) Modifier else Modifier.combinedClickable(
		interactionSource = focus.interactionSource,
		indication = null,
		role = Role.Button,
		onClick = onClick,
	)
	Box(
		modifier = modifier
			.size(160.dp, 90.dp)
			.then(clickModifier)
			.clip(JeShapes.container)
			.background(if (focused) JeColors.surfaceRaised else JeColors.surface)
			.jeFocusRing(focused),
	) {
		if (imageUrl != null) {
			AsyncImage(url = imageUrl, aspectRatio = 16f / 9f, scaleType = ImageView.ScaleType.CENTER_CROP, modifier = Modifier.fillMaxSize())
			// Keeps the white text legible on bright art; drawn once per size
			Box(
				modifier = Modifier
					.fillMaxSize()
					.drawWithCache {
						val scrim = Brush.verticalGradient(0f to Tokens.Color.colorBlack.copy(alpha = 0.75f), 0.7f to Color.Transparent)
						onDrawBehind { drawRect(scrim) }
					},
			)
		}
		Column(modifier = Modifier.padding(10.dp)) {
			Text(text = name, style = JeType.cardTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
			if (episodes != null) Text(text = episodes, style = JeType.caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
		}
		if (status != null) StatusBadge(status, Modifier.align(Alignment.BottomStart).padding(8.dp), compact = true)
	}
}
