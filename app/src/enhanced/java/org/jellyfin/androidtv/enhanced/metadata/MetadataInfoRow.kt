package org.jellyfin.androidtv.enhanced.metadata

import androidx.annotation.StringRes
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.enhanced.ui.design.JeSpace
import org.jellyfin.androidtv.enhanced.ui.design.JeType
import org.jellyfin.androidtv.ui.base.Text

/**
 * Everything the "Enhanced info" row shows, loaded before the row is added. Absent parts are null/empty; [failures]
 * names the sources that failed (string resources) so the row can offer a retry instead of silently dropping them.
 */
data class MetadataInfo(
	val stats: ItemStats? = null,
	val showSize: Boolean = false,
	val showProgress: Boolean = false,
	val progressMode: WatchProgressMode = WatchProgressMode.PERCENTAGE,
	val timeFormat: String? = null,
	val audioLanguages: List<String> = emptyList(),
	val releases: List<ReleaseInfo> = emptyList(),
	/** True for episodes: their single date is "Aired"/"Airs" rather than "Next/Last episode". */
	val releaseIsEpisode: Boolean = false,
	val ratings: List<RatingChip> = emptyList(),
	val awards: Awards? = null,
	val providers: List<WatchProvider> = emptyList(),
	val providerRegion: String? = null,
	val person: PersonInfo? = null,
	@StringRes val failures: List<Int> = emptyList(),
) {
	private val hasStats get() = stats != null && ((showSize && (stats.size ?: 0) > 0) || (showProgress && stats.totalRuntimeTicks > 0))
	private val hasAwards get() = awards != null && awards.found && (awards.wins > 0 || awards.nominations > 0)

	val isEmpty
		get() = !hasStats && audioLanguages.isEmpty() && releases.isEmpty() && ratings.isEmpty() && !hasAwards &&
			providers.isEmpty() && person == null && failures.isEmpty()
}

private val dateFormatter: DateTimeFormatter get() = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)

internal fun formatDate(date: LocalDate): String = date.format(dateFormatter)

internal fun formatDate(value: String?): String? = parseCalendarDate(value)?.let(::formatDate)

/** A line of chips that wraps onto further lines, so every fact is visible at a glance (nothing cut at the edge). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipLine(content: @Composable FlowRowScope.() -> Unit) {
	val focusManager = LocalFocusManager.current
	FlowRow(
		// Left/Right move between chips only: at the end of a wrapped line there is nothing beside the chip, and the
		// view system would otherwise jump to the toolbar (Down reaches the next line)
		modifier = Modifier
			.fillMaxWidth()
			.onPreviewKeyEvent { event ->
				val direction = when (event.key) {
					Key.DirectionRight -> FocusDirection.Right
					Key.DirectionLeft -> FocusDirection.Left
					else -> return@onPreviewKeyEvent false
				}
				if (event.type == KeyEventType.KeyDown) focusManager.moveFocus(direction)
				true
			},
		horizontalArrangement = Arrangement.spacedBy(JeSpace.chipGap),
		verticalArrangement = Arrangement.spacedBy(JeSpace.chipGap),
		content = content,
	)
}

/**
 * The content of the "Enhanced info" row: stats, release dates, ratings, awards, providers and person facts, a line
 * each. The state lives outside the composition ([infoState], [progressModeState]) because leanback rebinds rows
 * when they scroll back into view. [onProgressMode] persists a new watch-progress display mode; [onAwards] opens the full awards list;
 * [reload] loads everything again (for the retry chip). [focusableFacts] false (screens that aren't leanback rows)
 * leaves only the actionable chips focusable.
 */
@Composable
fun MetadataInfoContent(
	infoState: MutableState<MetadataInfo>,
	progressModeState: MutableState<WatchProgressMode>,
	onProgressMode: (WatchProgressMode) -> Unit,
	onAwards: (Awards) -> Unit,
	reload: suspend () -> MetadataInfo,
	focusableFacts: Boolean = true,
) {
	var info by infoState
	var progressMode by progressModeState
	val scope = rememberCoroutineScope()
	val context = LocalContext.current

	Column(
		modifier = Modifier.padding(vertical = 4.dp),
		verticalArrangement = Arrangement.spacedBy(10.dp),
	) {
		@Composable
		fun Fact(text: String, leading: (@Composable () -> Unit)? = null) = MetadataChip(text = text, leading = leading, focusable = focusableFacts)

		val stats = info.stats
		val statsLine = stats != null && (info.showProgress || info.showSize)
		if (statsLine || info.audioLanguages.isNotEmpty() || info.releases.isNotEmpty()) ChipLine {
			if (stats != null && info.showProgress && stats.totalRuntimeTicks > 0) {
				MetadataChip(
					text = stringResource(R.string.je_metadata_progress, watchProgressText(stats, progressMode, info.timeFormat)),
					onClick = {
						progressMode = progressMode.next()
						onProgressMode(progressMode)
					},
				)
			}
			if (stats != null && info.showSize && (stats.size ?: 0) > 0) {
				Fact(stringResource(R.string.je_metadata_size, formatSize(stats.size!!)))
			}
			if (info.audioLanguages.isNotEmpty()) {
				Fact(stringResource(R.string.je_metadata_audio, info.audioLanguages.joinToString(", ") { languageDisplayName(it) }))
			}
			val today = LocalDate.now()
			for (release in info.releases) {
				val date = formatDate(release.date)
				val label = when (release.kind) {
					ReleaseInfo.Kind.CINEMA -> R.string.je_metadata_release_cinema
					ReleaseInfo.Kind.DIGITAL -> R.string.je_metadata_release_digital
					ReleaseInfo.Kind.PHYSICAL -> R.string.je_metadata_release_physical
					ReleaseInfo.Kind.EPISODE -> when {
						info.releaseIsEpisode && release.date.isAfter(today) -> R.string.je_metadata_airs
						info.releaseIsEpisode -> R.string.je_metadata_aired
						!release.date.isBefore(today) -> R.string.je_metadata_next_episode
						else -> R.string.je_metadata_last_episode
					}
				}
				Fact(stringResource(label, date))
			}
		}

		val awards = info.awards?.takeIf { it.found && (it.wins > 0 || it.nominations > 0) }
		if (info.ratings.isNotEmpty() || awards != null) ChipLine {
			for (rating in info.ratings) Fact("${rating.label} ${rating.value}")
			if (awards != null) {
				MetadataChip(
					text = awardsSummary(awards),
					icon = R.drawable.je_ic_metadata_awards,
					onClick = { onAwards(awards) },
				)
			}
		}

		if (info.providers.isNotEmpty()) ChipLine {
			info.providerRegion?.let {
				Text(text = stringResource(R.string.je_metadata_providers, it), style = JeType.meta)
			}
			for (provider in info.providers) {
				Fact(
					text = provider.providerName,
					leading = provider.logoUrl?.let { url ->
						{
							AsyncImage(
								model = url,
								contentDescription = null,
								modifier = Modifier
									.size(20.dp)
									.clip(RoundedCornerShape(4.dp)),
							)
						}
					},
				)
			}
		}

		info.person?.let { person ->
			val born = formatDate(person.birthDate)
			val died = formatDate(person.deathDate)
			val chips = listOfNotNull(
				born?.let {
					if (!person.isDeceased && person.currentAge != null) stringResource(R.string.je_metadata_born_age, it, person.currentAge)
					else stringResource(R.string.je_metadata_born, it)
				},
				died?.let {
					if (person.ageAtDeath != null) stringResource(R.string.je_metadata_died_age, it, person.ageAtDeath)
					else stringResource(R.string.je_metadata_died, it)
				},
				person.birthPlace?.takeIf { it.isNotBlank() },
			)
			if (chips.isNotEmpty()) ChipLine { chips.forEach { Fact(it) } }
		}

		if (info.failures.isNotEmpty()) ChipLine {
			val names = info.failures.joinToString(", ") { context.getString(it) }
			MetadataChip(
				text = stringResource(R.string.je_metadata_load_failed, names),
				onClick = {
					scope.launch {
						info = reload()
						progressMode = info.progressMode
					}
				},
			)
		}
	}
}

@Composable
internal fun awardsSummary(awards: Awards): String = listOfNotNull(
	awards.wins.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.je_metadata_wins, it, it) },
	awards.nominations.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.je_metadata_nominations, it, it) },
).joinToString(" · ")
