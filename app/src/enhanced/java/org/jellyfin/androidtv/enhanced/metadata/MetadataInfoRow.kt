package org.jellyfin.androidtv.enhanced.metadata

import androidx.annotation.StringRes
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.design.Tokens
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

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

/** A horizontally scrolling line of chips; focus moving along it scrolls it. */
@Composable
private fun ChipLine(content: @Composable RowScope.() -> Unit) {
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.horizontalScroll(rememberScrollState()),
		horizontalArrangement = Arrangement.spacedBy(8.dp),
		verticalAlignment = Alignment.CenterVertically,
		content = content,
	)
}

/**
 * The content of the "Enhanced info" row: stats, release dates, ratings, awards, providers and person facts, a line
 * each. [onProgressMode] persists a new watch-progress display mode; [onAwards] opens the full awards list;
 * [reload] loads everything again (for the retry chip).
 */
@Composable
fun MetadataInfoContent(
	initial: MetadataInfo,
	onProgressMode: (WatchProgressMode) -> Unit,
	onAwards: (Awards) -> Unit,
	reload: suspend () -> MetadataInfo,
) {
	var info by remember { mutableStateOf(initial) }
	var progressMode by remember { mutableStateOf(initial.progressMode) }
	val scope = rememberCoroutineScope()
	val context = LocalContext.current

	Column(
		modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
		verticalArrangement = Arrangement.spacedBy(10.dp),
	) {
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
				MetadataChip(text = stringResource(R.string.je_metadata_size, formatSize(stats.size!!)))
			}
			if (info.audioLanguages.isNotEmpty()) {
				MetadataChip(text = stringResource(R.string.je_metadata_audio, info.audioLanguages.joinToString(", ") { languageDisplayName(it) }))
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
				MetadataChip(text = stringResource(label, date))
			}
		}

		val awards = info.awards?.takeIf { it.found && (it.wins > 0 || it.nominations > 0) }
		if (info.ratings.isNotEmpty() || awards != null) ChipLine {
			for (rating in info.ratings) MetadataChip(text = "${rating.label} ${rating.value}")
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
				Text(text = stringResource(R.string.je_metadata_providers, it), color = Tokens.Color.colorGrey300, fontSize = 14.sp)
			}
			for (provider in info.providers) {
				MetadataChip(
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
			if (chips.isNotEmpty()) ChipLine { chips.forEach { MetadataChip(text = it) } }
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
