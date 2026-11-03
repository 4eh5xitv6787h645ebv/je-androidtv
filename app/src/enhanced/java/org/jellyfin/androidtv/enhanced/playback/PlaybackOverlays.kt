package org.jellyfin.androidtv.enhanced.playback

import android.widget.ImageView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.enhanced.ui.design.AgeRatingTag
import org.jellyfin.androidtv.enhanced.ui.design.HeroTitle
import org.jellyfin.androidtv.enhanced.ui.design.InfoLeading
import org.jellyfin.androidtv.enhanced.ui.design.JeColors
import org.jellyfin.androidtv.enhanced.ui.design.JePlayerStyle
import org.jellyfin.androidtv.enhanced.ui.design.JeSpace
import org.jellyfin.androidtv.enhanced.ui.design.JeType
import org.jellyfin.androidtv.enhanced.ui.design.OsdInfo
import org.jellyfin.androidtv.enhanced.ui.design.OverlayAgeRating
import org.jellyfin.androidtv.enhanced.ui.design.OverlayPanel
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.composable.AsyncImage
import org.jellyfin.androidtv.util.getTimeFormatter
import java.time.LocalDateTime
import java.util.Locale
import java.util.UUID

/** What the pause screen shows; built from the player's (server-filtered) item DTO. */
data class PauseScreenModel(
	val itemId: UUID,
	val title: String,
	val logoUrl: String?,
	val backdropUrl: String?,
	val episodeLine: String?,
	val year: Int?,
	val officialRating: String?,
	val runtime: String?,
	val overview: String?,
	val positionMs: Long,
	val durationMs: Long,
	/** Playback speed, so "ends at" matches the upstream OSD's. */
	val speed: Float,
)

/** The playback-start age-rating badge. */
data class RatingBadgeModel(val itemId: UUID, val rating: String, val genres: List<String>, val colored: Boolean)

/** Community and critic ratings for the OSD title area. */
data class OsdRatingModel(val communityRating: Float?, val criticPercent: Int?)

private val OverlayBlack = Color.Black

/** Everything the enhancer draws over the video: the pause screen and the rating badge. */
@Composable
fun PlaybackOverlay(pause: PauseScreenModel?, badge: RatingBadgeModel?, osdShowing: Boolean = false) {
	Box(Modifier.fillMaxSize()) {
		AnimatedVisibility(
			visible = badge != null && pause == null && !osdShowing,
			enter = fadeIn(tween(FADE_MS)) + slideInHorizontally(tween(FADE_MS)) { -it / 8 },
			exit = fadeOut(tween(FADE_MS)) + slideOutHorizontally(tween(FADE_MS)) { -it / 8 },
			modifier = Modifier.align(Alignment.TopStart).padding(start = JeSpace.pageHorizontal, top = 64.dp),
		) {
			// Keep the last badge while it fades out
			var shown by remember { mutableStateOf(badge) }
			if (badge != null) shown = badge
			shown?.let { RatingBadge(it) }
		}

		// Fades in, but disappears at once: resuming or pressing a key must uncover the video immediately
		AnimatedVisibility(visible = pause != null, enter = fadeIn(tween(FADE_MS)), exit = ExitTransition.None) {
			pause?.let { PauseScreen(it) }
		}
	}
}

private const val FADE_MS = 400

@Composable
private fun PauseScreen(model: PauseScreenModel) {
	Box(
		Modifier
			.fillMaxSize()
			.background(OverlayBlack)
	) {
		if (model.backdropUrl != null) {
			AsyncImage(
				url = model.backdropUrl,
				scaleType = ImageView.ScaleType.CENTER_CROP,
				modifier = Modifier.fillMaxSize().alpha(0.4f),
			)
		}
		Box(
			Modifier
				.fillMaxSize()
				.background(Brush.horizontalGradient(listOf(OverlayBlack.copy(alpha = 0.92f), OverlayBlack.copy(alpha = 0.35f))))
		)

		PauseClock(Modifier.align(Alignment.TopEnd).padding(top = JeSpace.pageBottom, end = JeSpace.pageHorizontal))

		Column(
			verticalArrangement = Arrangement.spacedBy(14.dp),
			modifier = Modifier
				.fillMaxHeight()
				.fillMaxWidth(0.58f)
				.padding(start = JeSpace.pageHorizontal, top = 72.dp, bottom = 40.dp),
		) {
			if (model.logoUrl != null) {
				AsyncImage(
					url = model.logoUrl,
					scaleType = ImageView.ScaleType.FIT_START,
					modifier = Modifier.sizeIn(maxWidth = 380.dp, maxHeight = 110.dp).height(110.dp).fillMaxWidth(),
				)
			} else {
				HeroTitle(model.title)
			}

			model.episodeLine?.let {
				Text(it, style = JeType.dialogTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
			}

			DetailsLine(model)

			model.overview?.takeIf { it.isNotBlank() }?.let {
				Text(it, style = JePlayerStyle.pauseDetails, maxLines = 6, overflow = TextOverflow.Ellipsis)
			}

			Spacer(Modifier.weight(1f))

			Progress(model)
		}
	}
}

@Composable
private fun DetailsLine(model: PauseScreenModel) {
	val parts = listOfNotNull(model.year?.toString(), model.officialRating, model.runtime)
	if (parts.isEmpty()) return
	val style = JePlayerStyle.pauseDetails.copy(color = JeColors.onSurface)
	Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
		model.year?.let { Text(it.toString(), style = style) }
		model.officialRating?.let { AgeRatingTag(it) }
		model.runtime?.let { Text(it, style = style) }
	}
}

@Composable
private fun Progress(model: PauseScreenModel) {
	val context = LocalContext.current
	val now = rememberNow()
	val progress = PlaybackItemInfo.progress(model.positionMs, model.durationMs)
	Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth()) {
		Box(
			Modifier
				.fillMaxWidth()
				.height(6.dp)
				.clip(RoundedCornerShape(3.dp))
				.background(Color.White.copy(alpha = 0.18f))
		) {
			Box(
				Modifier
					.fillMaxWidth(progress)
					.fillMaxHeight()
					.background(Color.White.copy(alpha = 0.9f))
			)
		}
		if (model.durationMs > 0) {
			val remainingMs = (model.durationMs - model.positionMs).coerceAtLeast(0)
			val endsAt = now.plusNanos((remainingMs / model.speed.coerceAtLeast(0.1f)).toLong() * 1_000_000)
			Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
				Text("${PlaybackItemInfo.clock(model.positionMs)} / ${PlaybackItemInfo.clock(model.durationMs)}", style = JeType.meta, color = JeColors.onSurface)
				Text(stringResource(R.string.je_playback_remaining, PlaybackItemInfo.clock(remainingMs)), style = JeType.meta)
				Text(stringResource(R.string.je_playback_ends_at, context.getTimeFormatter().format(endsAt)), style = JeType.meta)
			}
		}
	}
}

@Composable
private fun PauseClock(modifier: Modifier) {
	val context = LocalContext.current
	val now = rememberNow()
	Column(horizontalAlignment = Alignment.End, modifier = modifier) {
		Text(context.getTimeFormatter().format(now), style = JeType.pageTitle)
		Text(stringResource(R.string.je_playback_paused), style = JeType.meta, modifier = Modifier.padding(top = 2.dp))
	}
}

/** The wall clock, refreshed every second while shown. */
@Composable
private fun rememberNow(): LocalDateTime {
	var now by remember { mutableStateOf(LocalDateTime.now()) }
	LaunchedEffect(Unit) {
		while (true) {
			delay(1_000)
			now = LocalDateTime.now()
		}
	}
	return now
}

@Composable
private fun RatingBadge(model: RatingBadgeModel) {
	OverlayPanel {
		if (model.colored) {
			val style = PlaybackRatingColors.bucket(model.rating).style
			OverlayAgeRating(
				text = PlaybackRatingColors.displayText(model.rating),
				container = Color(style.background),
				content = Color(style.text),
				border = Color(style.border),
			)
		} else {
			OverlayAgeRating(text = model.rating, container = Color.Transparent, content = JeColors.onSurface, border = JeColors.onSurface)
		}
		if (model.genres.isNotEmpty()) {
			Text(model.genres.joinToString(" · "), style = JePlayerStyle.badgeGenres, maxLines = 1, overflow = TextOverflow.Ellipsis)
		}
	}
}

/** Critic then community rating, as `osd-rating.js` orders them, as text on the video under the OSD title. */
@Composable
fun OsdRating(model: OsdRatingModel?) {
	if (model == null || (model.communityRating == null && model.criticPercent == null)) return
	Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
		model.criticPercent?.let { OsdInfo(text = "$it%", leading = InfoLeading.Dot(JeColors.ratingFresh)) }
		model.communityRating?.let {
			OsdInfo(
				text = String.format(Locale.getDefault(), "%.1f", it),
				leading = InfoLeading.Icon(R.drawable.je_ic_reviews_star, JeColors.ratingStar),
			)
		}
	}
}
