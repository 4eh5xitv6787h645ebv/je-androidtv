package org.jellyfin.androidtv.enhanced.metadata

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.ui.base.Icon
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.list.ListButton

/** Wins first, newest first, as the web lists them. */
fun sortAwards(awards: List<Award>): List<Award> =
	awards.sortedWith(compareByDescending<Award> { it.won }.thenByDescending { it.year ?: Int.MIN_VALUE })

/** The full awards list of a title or person, in a side panel. */
@Composable
fun AwardsDialog(awards: Awards) {
	val sorted = remember(awards) { sortAwards(awards.awards) }
	val firstFocus = remember { FocusRequester() }

	MetadataDialogPanel(title = stringResource(R.string.je_metadata_awards), subtitle = awardsSummary(awards)) {
		LazyColumn {
			items(sorted) { award ->
				val people = (award.recipients + award.works).joinToString(", ")
				val caption = listOfNotNull(
					stringResource(if (award.won) R.string.je_metadata_award_won else R.string.je_metadata_award_nominated),
					award.year?.toString(),
					people.ifEmpty { null },
				).joinToString(" · ")
				ListButton(
					onClick = {},
					leadingContent = if (award.won) {
						{ Icon(painterResource(R.drawable.je_ic_metadata_awards), contentDescription = null) }
					} else null,
					headingContent = { Text(award.name) },
					captionContent = { Text(caption) },
					modifier = if (award === sorted.first()) Modifier.focusRequester(firstFocus) else Modifier,
				)
			}
		}
	}

	LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }
}
