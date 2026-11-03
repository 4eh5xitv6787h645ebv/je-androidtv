package org.jellyfin.androidtv.enhanced.ui.design

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.jellyfin.androidtv.ui.base.Text

/**
 * A heading for a group of rows or cards (spec §6.2): "Seasons", "Cast", "Today · Tuesday 3 Nov",
 * "Watching now 0". [count] shows after the text in the tertiary colour; [trailing] sits at the end of the line
 * (a "View all" button, say). Not focusable. The caller sets the horizontal page padding.
 */
@Composable
fun SectionHeader(
	text: String,
	modifier: Modifier = Modifier,
	count: Int? = null,
	trailing: (@Composable () -> Unit)? = null,
) {
	Row(
		modifier = modifier.fillMaxWidth(),
		horizontalArrangement = Arrangement.spacedBy(10.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Text(
			text = text,
			style = JeType.sectionHeader,
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
			modifier = Modifier.alignByBaseline().weight(1f, fill = false),
		)
		if (count != null) {
			Text(text = count.toString(), style = JeType.sectionHeader, color = JeColors.textTertiary, modifier = Modifier.alignByBaseline())
		}
		if (trailing != null) {
			Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.End) { trailing() }
		}
	}
}

/**
 * A small uppercase label (spec §6.2) over a group of facts or list rows: "STREAMING IN US", "SHOW · 1 OF 47",
 * "HIDDEN ON PROTECTED TITLES". Inside the settings drawer prefer upstream's `ListSection(overlineContent = …)`
 * ([SettingsSection]) so the inset matches its rows.
 */
@Composable
fun Overline(
	text: String,
	modifier: Modifier = Modifier,
) {
	Text(
		text = text.uppercase(),
		style = JeType.overline,
		maxLines = 1,
		overflow = TextOverflow.Ellipsis,
		modifier = modifier.padding(vertical = 2.dp),
	)
}
