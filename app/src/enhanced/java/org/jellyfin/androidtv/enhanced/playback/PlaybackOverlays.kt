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
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.browsing.composable.inforow.InfoRowCommunityRating
import org.jellyfin.androidtv.ui.browsing.composable.inforow.InfoRowCriticRating
import org.jellyfin.androidtv.ui.composable.AsyncImage
import org.jellyfin.androidtv.util.getTimeFormatter
import org.jellyfin.design.Tokens
import java.time.LocalDateTime
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

private val OverlayBlack = Color(0xFF000000)
private val Accent = Tokens.Color.colorBlue400

/** Everything the enhancer draws over the video: the pause screen and the rating badge. */
@Composable
fun PlaybackOverlay(pause: PauseScreenModel?, badge: RatingBadgeModel?) {
	Box(Modifier.fillMaxSize()) {
		AnimatedVisibility(
			visible = badge != null && pause == null,
			enter = fadeIn(tween(FADE_MS)) + slideInHorizontally(tween(FADE_MS)) { -it / 8 },
			exit = fadeOut(tween(FADE_MS)) + slideOutHorizontally(tween(FADE_MS)) { -it / 8 },
			modifier = Modifier.align(Alignment.TopStart).padding(top = 64.dp),
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

		PauseClock(Modifier.align(Alignment.TopEnd).padding(top = 32.dp, end = 48.dp))

		Column(
			verticalArrangement = Arrangement.spacedBy(14.dp),
			modifier = Modifier
				.fillMaxHeight()
				.fillMaxWidth(0.58f)
				.padding(start = 64.dp, top = 72.dp, bottom = 40.dp),
		) {
			if (model.logoUrl != null) {
				AsyncImage(
					url = model.logoUrl,
					scaleType = ImageView.ScaleType.FIT_START,
					modifier = Modifier.sizeIn(maxWidth = 380.dp, maxHeight = 110.dp).height(110.dp).fillMaxWidth(),
				)
			} else {
				Text(model.title, fontSize = 38.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
			}

			model.episodeLine?.let {
				Text(it, fontSize = 20.sp, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
			}

			DetailsLine(model)

			model.overview?.takeIf { it.isNotBlank() }?.let {
				Text(it, fontSize = 16.sp, lineHeight = 24.sp, color = Tokens.Color.colorGrey100, maxLines = 6, overflow = TextOverflow.Ellipsis)
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
	Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
		model.year?.let { Text(it.toString(), fontSize = 18.sp, color = Color.White) }
		model.officialRating?.let {
			Text(
				it,
				fontSize = 15.sp,
				fontWeight = FontWeight.Bold,
				color = Color.White,
				modifier = Modifier
					.border(1.dp, Color.White.copy(alpha = 0.7f), RoundedCornerShape(4.dp))
					.padding(horizontal = 6.dp, vertical = 1.dp),
			)
		}
		model.runtime?.let { Text(it, fontSize = 18.sp, color = Color.White) }
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
				Text("${PlaybackItemInfo.clock(model.positionMs)} / ${PlaybackItemInfo.clock(model.durationMs)}", fontSize = 15.sp, color = Color.White)
				Text(stringResource(R.string.je_playback_remaining, PlaybackItemInfo.clock(remainingMs)), fontSize = 15.sp, color = Tokens.Color.colorGrey300)
				Text(stringResource(R.string.je_playback_ends_at, context.getTimeFormatter().format(endsAt)), fontSize = 15.sp, color = Tokens.Color.colorGrey300)
			}
		}
	}
}

@Composable
private fun PauseClock(modifier: Modifier) {
	val context = LocalContext.current
	val now = rememberNow()
	Column(horizontalAlignment = Alignment.End, modifier = modifier) {
		Text(context.getTimeFormatter().format(now), fontSize = 28.sp, color = Color.White)
		Text(
			stringResource(R.string.je_playback_paused),
			fontSize = 15.sp,
			color = Tokens.Color.colorGrey300,
			modifier = Modifier.padding(top = 2.dp),
		)
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
	Row(
		modifier = Modifier
			.height(IntrinsicSize.Min)
			.widthIn(max = 520.dp)
			.clip(RoundedCornerShape(topEnd = 8.dp, bottomEnd = 8.dp))
			.background(Color(0xB8101010))
	) {
		Box(Modifier.width(4.dp).fillMaxHeight().background(Accent))
		Column(
			verticalArrangement = Arrangement.spacedBy(6.dp),
			modifier = Modifier.padding(start = 18.dp, end = 24.dp, top = 12.dp, bottom = 12.dp),
		) {
			RatingBox(model)
			if (model.genres.isNotEmpty()) {
				Text(
					model.genres.joinToString(" • "),
					fontSize = 16.sp,
					fontWeight = FontWeight.Medium,
					color = Color.White.copy(alpha = 0.85f),
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
				)
			}
		}
	}
}

@Composable
private fun RatingBox(model: RatingBadgeModel) {
	val shape = RoundedCornerShape(5.dp)
	if (model.colored) {
		val style = PlaybackRatingColors.bucket(model.rating).style
		Text(
			PlaybackRatingColors.displayText(model.rating).uppercase(),
			fontSize = 26.sp,
			fontWeight = FontWeight.Bold,
			color = Color(style.text),
			modifier = Modifier
				.background(Color(style.background), shape)
				.border(2.dp, Color(style.border), shape)
				.padding(horizontal = 10.dp, vertical = 2.dp),
		)
	} else {
		Text(
			model.rating,
			fontSize = 26.sp,
			fontWeight = FontWeight.Bold,
			color = Color.White,
			modifier = Modifier
				.border(2.dp, Color.White, shape)
				.padding(horizontal = 10.dp, vertical = 2.dp),
		)
	}
}

/** Critic then community rating, as `osd-rating.js` orders them; reuses the upstream info-row items. */
@Composable
fun OsdRating(model: OsdRatingModel?) {
	if (model == null || (model.communityRating == null && model.criticPercent == null)) return
	// The info-row items fill their parent's height, so the row needs a fixed one or it grows to the OSD panel
	Row(
		horizontalArrangement = Arrangement.spacedBy(12.dp),
		verticalAlignment = Alignment.CenterVertically,
		modifier = Modifier.height(24.dp),
	) {
		model.criticPercent?.let { InfoRowCriticRating(it / 100f) }
		model.communityRating?.let { InfoRowCommunityRating(it / 10f) }
	}
}
