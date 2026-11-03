package org.jellyfin.androidtv.enhanced.ui.design

import android.widget.ImageView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.jellyfin.androidtv.ui.base.Icon
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.composable.AsyncImage

/*
 * Player overlay helpers (spec §6.16). The video is the background here, so containers are the over-art surface,
 * text without a container gets a small shadow, and nothing is focusable.
 */

/** Styles for text drawn straight on video. */
object JePlayerStyle {
	/** Details on the pause screen (year, runtime, genres). */
	val pauseDetails: TextStyle = JeType.bodyLarge

	/** Genres under the rating badge. */
	val badgeGenres: TextStyle = JeType.meta.copy(color = JeColors.onSurface.copy(alpha = 0.85f))

	/** The age rating label of the rating badge: 20 sp bold. */
	val ageRating: TextStyle = JeType.badge.copy(fontSize = 20.sp, lineHeight = 26.sp)
}

/** [style] with a 1 dp black 60 % shadow, for text on video without a container (the OSD ratings line). */
@Composable
fun onVideoTextStyle(style: TextStyle = JeType.meta): TextStyle {
	val offset = with(LocalDensity.current) { 1.dp.toPx() }
	return style.copy(shadow = Shadow(color = Color.Black.copy(alpha = 0.6f), offset = Offset(0f, offset), blurRadius = offset * 2))
}

/**
 * The rating badge panel shown at the start of playback: [JeColors.surfaceOverArt], 12 dp corners, no accent bar.
 * Put an [OverlayAgeRating] and the genres ([JePlayerStyle.badgeGenres]) inside. Hide it while the OSD shows
 * instead of stacking the two.
 */
@Composable
fun OverlayPanel(
	modifier: Modifier = Modifier,
	content: @Composable ColumnScope.() -> Unit,
) {
	Column(
		modifier = modifier
			.widthIn(max = 520.dp)
			.background(JeColors.surfaceOverArt, JeShapes.container)
			.padding(horizontal = 20.dp, vertical = 14.dp),
		verticalArrangement = Arrangement.spacedBy(6.dp),
		content = content,
	)
}

/**
 * The age rating label of the rating badge, a [StatusBadge]-like 20 sp bold box. Colours come from the caller
 * (`PlaybackRatingColors` buckets); [border] null draws none.
 */
@Composable
fun OverlayAgeRating(
	text: String,
	container: Color,
	content: Color,
	modifier: Modifier = Modifier,
	border: Color? = null,
) {
	Text(
		text = text.uppercase(),
		style = JePlayerStyle.ageRating,
		color = content,
		maxLines = 1,
		modifier = modifier
			.background(container, JeShapes.badge)
			.then(if (border != null) Modifier.border(2.dp, border, JeShapes.badge) else Modifier)
			.padding(horizontal = 10.dp, vertical = 2.dp),
	)
}

/**
 * A short-lived [Notice] for the player (bookmark saved, Spoiler Guard skipped…): an info banner that hides itself
 * after [durationMillis] by calling [onTimeout]. Place it bottom-centre; it is never focusable.
 */
@Composable
fun OverlayToast(
	visible: Boolean,
	text: String,
	onTimeout: () -> Unit,
	modifier: Modifier = Modifier,
	kind: NoticeKind = NoticeKind.Info,
	durationMillis: Long = 3_000,
) {
	val timeout = rememberUpdatedState(onTimeout)
	AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut(), modifier = modifier) {
		Notice(kind = kind, title = text, modifier = Modifier.widthIn(max = 640.dp))
	}
	if (visible) {
		LaunchedEffect(text) {
			delay(durationMillis)
			timeout.value()
		}
	}
}

/**
 * One fact of the OSD ratings line ("● 75%", "★ 8.3"), drawn on video without a container, with the
 * [onVideoTextStyle] shadow. Lay several out in a row under the title, inside the OSD's own layout.
 */
@Composable
fun OsdInfo(
	text: String,
	modifier: Modifier = Modifier,
	leading: InfoLeading? = null,
) {
	Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
		when (leading) {
			is InfoLeading.Dot -> Box(Modifier.size(12.dp).background(leading.color, CircleShape))
			is InfoLeading.Icon -> Icon(
				painterResource(leading.res),
				contentDescription = null,
				tint = if (leading.tint == Color.Unspecified) JeColors.onSurface else leading.tint,
				modifier = Modifier.size(18.dp),
			)

			is InfoLeading.Image -> AsyncImage(url = leading.url, scaleType = ImageView.ScaleType.FIT_CENTER, modifier = Modifier.size(20.dp))
			null -> Unit
		}
		Text(text = text, style = onVideoTextStyle(JeType.meta.copy(color = JeColors.onSurface, fontWeight = FontWeight.W500)), maxLines = 1)
	}
}
