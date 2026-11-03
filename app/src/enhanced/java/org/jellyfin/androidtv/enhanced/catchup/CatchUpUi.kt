package org.jellyfin.androidtv.enhanced.catchup

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.enhanced.calendar.PageImage
import org.jellyfin.androidtv.ui.base.Icon
import org.jellyfin.androidtv.ui.base.JellyfinTheme
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.button.Button
import org.jellyfin.androidtv.ui.base.form.Checkbox
import org.jellyfin.androidtv.ui.base.list.ListButton
import org.jellyfin.design.Tokens
import java.util.Locale

/** The info line under a card's title: year · runtime or seasons · age rating · community rating. */
@Composable
internal fun catchUpSubline(item: CatchUpItem): String = listOfNotNull(
	item.productionYear?.toString(),
	if (item.isSeries) item.childCount?.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.je_catchup_seasons, it, it) }
	else item.runtimeMinutes?.let { stringResource(R.string.je_catchup_minutes, it) },
	item.officialRating?.takeIf { it.isNotBlank() },
	item.communityRating?.takeIf { it > 0 }?.let { "★ " + String.format(Locale.getDefault(), "%.1f", it) },
).joinToString("  ·  ")

/**
 * One card of the deck: backdrop behind, poster, title, details and overview, with the [actions] row underneath
 * so the D-pad moves only between buttons.
 */
@Composable
internal fun CatchUpCard(
	item: CatchUpItem,
	posterUrl: String?,
	backdropUrl: String?,
	actions: @Composable RowScope.() -> Unit,
) {
	Box(modifier = Modifier.fillMaxSize()) {
		if (backdropUrl != null) {
			coil3.compose.AsyncImage(
				model = backdropUrl,
				contentDescription = null,
				contentScale = ContentScale.Crop,
				modifier = Modifier
					.matchParentSize()
					.alpha(0.35f),
			)
			// Keeps the text readable on bright backdrops
			Box(
				modifier = Modifier
					.matchParentSize()
					.background(Brush.horizontalGradient(listOf(Color.Black.copy(alpha = 0.85f), Color.Black.copy(alpha = 0.2f)))),
			)
		}

		Column(
			modifier = Modifier
				.fillMaxSize()
				.padding(start = 48.dp, end = 48.dp, top = 8.dp, bottom = 32.dp),
			verticalArrangement = Arrangement.spacedBy(20.dp),
		) {
			Row(
				modifier = Modifier
					.weight(1f)
					.fillMaxWidth(),
				horizontalArrangement = Arrangement.spacedBy(32.dp),
			) {
				PageImage(
					url = posterUrl,
					modifier = Modifier
						.fillMaxHeight()
						.aspectRatio(2f / 3f),
				)
				Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
					Text(
						text = stringResource(if (item.isSeries) R.string.je_catchup_show else R.string.je_catchup_movie).uppercase(),
						fontSize = 13.sp,
						color = Tokens.Color.colorGrey300,
					)
					Text(
						text = item.name?.takeIf { it.isNotBlank() } ?: stringResource(R.string.je_catchup_untitled),
						fontSize = 30.sp,
						color = Tokens.Color.colorWhite,
						maxLines = 2,
						overflow = TextOverflow.Ellipsis,
					)
					val subline = catchUpSubline(item)
					if (subline.isNotEmpty()) Text(text = subline, fontSize = 16.sp, color = Tokens.Color.colorGrey100)
					if (item.genres.isNotEmpty()) {
						Text(text = item.genres.take(4).joinToString(", "), fontSize = 15.sp, color = Tokens.Color.colorGrey300, maxLines = 1)
					}
					item.overview?.takeIf { it.isNotBlank() }?.let { overview ->
						Text(
							text = overview,
							fontSize = 16.sp,
							color = Tokens.Color.colorGrey100,
							maxLines = 6,
							overflow = TextOverflow.Ellipsis,
						)
					}
				}
			}

			Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically, content = actions)
		}
	}
}

/** An icon-and-label action button; [dimmed] keeps it focusable (so focus never jumps) but shows it's a no-op. */
@Composable
internal fun CatchUpActionButton(
	@DrawableRes icon: Int,
	label: String,
	onClick: () -> Unit,
	modifier: Modifier = Modifier,
	dimmed: Boolean = false,
) {
	Button(onClick = onClick, modifier = modifier.alpha(if (dimmed) 0.5f else 1f)) {
		Icon(painter = painterResource(icon), contentDescription = null, modifier = Modifier.size(20.dp))
		Spacer(modifier = Modifier.width(8.dp))
		Text(label)
	}
}

/** Shown when nothing is left to deal: a message and the [actions] (look again, undo, show dismissed again). */
@Composable
internal fun CatchUpEmpty(message: String, actions: @Composable RowScope.() -> Unit) {
	Box(modifier = Modifier.fillMaxSize()) {
		Column(
			modifier = Modifier.align(Alignment.Center),
			horizontalAlignment = Alignment.CenterHorizontally,
			verticalArrangement = Arrangement.spacedBy(16.dp),
		) {
			Text(text = message, color = Tokens.Color.colorGrey100, fontSize = 20.sp)
			Row(horizontalArrangement = Arrangement.spacedBy(12.dp), content = actions)
		}
	}
}

/** The season picker of a show being marked watched. */
data class SeasonPicker(
	val series: CatchUpItem,
	val seasons: List<CatchUpSeason>,
	val selected: Set<Int> = emptySet(),
)

/** The picker's panel: "All seasons", one checkbox row per season, then confirm and cancel. */
@Composable
internal fun SeasonPickerPanel(
	picker: SeasonPicker,
	onToggle: (Int) -> Unit,
	onAll: () -> Unit,
	onConfirm: () -> Unit,
	onCancel: () -> Unit,
) {
	val firstFocus = remember { FocusRequester() }
	Column(
		modifier = Modifier
			.width(560.dp)
			.heightIn(max = 480.dp)
			.background(JellyfinTheme.colorScheme.surface, JellyfinTheme.shapes.large)
			.padding(24.dp),
		verticalArrangement = Arrangement.spacedBy(12.dp),
	) {
		Text(
			text = stringResource(R.string.je_catchup_picker_title, picker.series.name ?: stringResource(R.string.je_catchup_untitled)),
			fontSize = 20.sp,
			color = Tokens.Color.colorWhite,
		)
		Text(text = stringResource(R.string.je_catchup_picker_help), fontSize = 14.sp, color = Tokens.Color.colorGrey300)

		LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
			item {
				ListButton(
					headingContent = { Text(stringResource(R.string.je_catchup_picker_all)) },
					onClick = onAll,
					modifier = Modifier.focusRequester(firstFocus),
				)
			}
			itemsIndexed(picker.seasons, key = { _, season -> season.id }) { index, season ->
				ListButton(
					headingContent = {
						Text(season.name?.takeIf { it.isNotBlank() } ?: stringResource(R.string.je_catchup_picker_season, season.indexNumber.toString()))
					},
					captionContent = if (season.episodeCount > 0) {
						{ Text(stringResource(R.string.je_catchup_picker_progress, season.watchedCount, season.episodeCount)) }
					} else null,
					trailingContent = { Checkbox(checked = index in picker.selected) },
					onClick = { onToggle(index) },
				)
			}
		}

		Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
			val count = picker.selected.size
			Button(onClick = onConfirm, enabled = count > 0) {
				Text(
					if (count == 0) stringResource(R.string.je_catchup_picker_select)
					else pluralStringResource(R.plurals.je_catchup_picker_mark, count, count)
				)
			}
			Button(onClick = onCancel) { Text(stringResource(R.string.je_catchup_cancel)) }
		}
	}

	LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }
}
