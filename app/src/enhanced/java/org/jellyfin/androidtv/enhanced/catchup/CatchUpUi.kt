package org.jellyfin.androidtv.enhanced.catchup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.enhanced.ui.design.AgeRatingTag
import org.jellyfin.androidtv.enhanced.ui.design.HeroHeader
import org.jellyfin.androidtv.enhanced.ui.design.InfoLeading
import org.jellyfin.androidtv.enhanced.ui.design.JeButton
import org.jellyfin.androidtv.enhanced.ui.design.JeColors
import org.jellyfin.androidtv.enhanced.ui.design.JeDialog
import org.jellyfin.androidtv.enhanced.ui.design.JeSpace
import org.jellyfin.androidtv.enhanced.ui.design.MediaCard
import org.jellyfin.androidtv.enhanced.ui.design.MetaLine
import org.jellyfin.androidtv.enhanced.ui.design.MetaRating
import org.jellyfin.androidtv.enhanced.ui.design.Overline
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.form.Checkbox
import org.jellyfin.androidtv.ui.base.list.ListButton
import java.util.Locale

/**
 * The hero text of the current card: "SHOW · 1 OF 47", the title, year · seasons or runtime, the age rating, the
 * community rating and genres, then the overview.
 */
@Composable
internal fun CatchUpHeader(item: CatchUpItem, left: Int?) {
	val kind = stringResource(if (item.isSeries) R.string.je_catchup_show else R.string.je_catchup_movie)
	HeroHeader(
		title = item.name?.takeIf { it.isNotBlank() } ?: stringResource(R.string.je_catchup_untitled),
		overline = if (left != null) stringResource(R.string.je_catchup_position, kind, left) else stringResource(R.string.je_catchup_overline, kind),
		meta = {
			MetaLine(
				listOf(
					item.productionYear?.toString(),
					if (item.isSeries) item.childCount?.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.je_catchup_seasons, it, it) }
					else item.runtimeMinutes?.let { stringResource(R.string.je_catchup_minutes, it) },
				),
			)
			item.officialRating?.takeIf { it.isNotBlank() }?.let { AgeRatingTag(it) }
			item.communityRating?.takeIf { it > 0 }?.let { rating ->
				MetaRating(
					text = String.format(Locale.getDefault(), "%.1f", rating),
					leading = InfoLeading.Icon(R.drawable.je_ic_reviews_star, JeColors.ratingStar),
				)
			}
			MetaLine(item.genres.take(3), modifier = Modifier.weight(1f, fill = false))
		},
		overview = item.overview?.takeIf { it.isNotBlank() },
	)
}

/**
 * The next cards, bottom right: three posters fanned out behind each other (the next one in front), not focusable.
 * Nothing when the deck has no more cards.
 */
@Composable
internal fun BoxScope.CatchUpUpNext(posterUrls: List<String?>) {
	if (posterUrls.isEmpty()) return
	Column(
		modifier = Modifier
			.align(Alignment.BottomEnd)
			.padding(end = JeSpace.pageHorizontal, bottom = UpNextBottom),
		verticalArrangement = Arrangement.spacedBy(8.dp),
	) {
		Overline(stringResource(R.string.je_catchup_up_next))
		Box {
			// Drawn back to front; the box takes the size of the front card plus the offsets
			posterUrls.withIndex().reversed().forEach { (index, url) ->
				MediaCard(
					imageUrl = url,
					title = null,
					onClick = null,
					width = UpNextWidth,
					fallbackIcon = R.drawable.je_ic_catchup_style,
					imageAlpha = UpNextAlpha.getOrElse(index) { UpNextAlpha.last() },
					modifier = Modifier
						.padding(end = UpNextOffset * (posterUrls.size - 1 - index))
						.offset(x = UpNextOffset * index),
				)
			}
		}
	}
}

private val UpNextWidth = 96.dp
private val UpNextOffset = 24.dp
private val UpNextBottom = 64.dp
private val UpNextAlpha = listOf(1f, 0.7f, 0.5f)

/** The season picker of a show being marked watched. */
data class SeasonPicker(
	val series: CatchUpItem,
	val seasons: List<CatchUpSeason>,
	val selected: Set<Int> = emptySet(),
)

/**
 * The season picker dialog: "All seasons", one checkbox row per season (selecting one selects the ones before it,
 * see [SeasonSelection.toggle]), then Cancel and "Mark N seasons watched". The dialog focuses its first element,
 * "All seasons".
 */
@Composable
internal fun SeasonPickerDialog(
	picker: SeasonPicker?,
	onToggle: (Int) -> Unit,
	onAll: () -> Unit,
	onConfirm: () -> Unit,
	onCancel: () -> Unit,
) {
	JeDialog(
		visible = picker != null,
		onDismissRequest = onCancel,
		title = stringResource(R.string.je_catchup_picker_title, picker?.series?.name ?: stringResource(R.string.je_catchup_untitled)),
		body = stringResource(R.string.je_catchup_picker_help),
		actions = {
			val count = picker?.selected?.size ?: 0
			JeButton(text = stringResource(R.string.je_catchup_cancel), onClick = onCancel)
			JeButton(
				text = if (count == 0) stringResource(R.string.je_catchup_picker_select)
				else pluralStringResource(R.plurals.je_catchup_picker_mark, count, count),
				enabled = count > 0,
				onClick = onConfirm,
			)
		},
	) {
		if (picker == null) return@JeDialog
		LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
			item {
				ListButton(
					headingContent = { Text(stringResource(R.string.je_catchup_picker_all)) },
					onClick = onAll,
				)
			}
			itemsIndexed(picker.seasons, key = { _, season -> season.id }) { index, season ->
				ListButton(
					headingContent = {
						Text(
							text = season.name?.takeIf { it.isNotBlank() } ?: stringResource(R.string.je_catchup_picker_season, season.indexNumber.toString()),
							maxLines = 1,
							overflow = TextOverflow.Ellipsis,
						)
					},
					captionContent = if (season.episodeCount > 0) {
						{ Text(stringResource(R.string.je_catchup_picker_progress, season.watchedCount, season.episodeCount)) }
					} else null,
					trailingContent = { Checkbox(checked = index in picker.selected) },
					onClick = { onToggle(index) },
				)
			}
		}
	}
}
