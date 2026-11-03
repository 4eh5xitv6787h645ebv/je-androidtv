package org.jellyfin.androidtv.enhanced.ui.design

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.ui.base.Icon
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.form.Checkbox
import org.jellyfin.androidtv.ui.base.list.ListButton
import org.jellyfin.androidtv.ui.base.list.ListSection

/*
 * Settings drawer rows (spec §6.15). The drawer is upstream's, so these add no visuals: they are upstream's
 * ListButton / ListSection with the drawer rules baked in (one-line captions, checkboxes not switches, chevrons on
 * navigation rows, overline section headings instead of bold rows).
 */

/**
 * A section heading in a settings page: upstream `ListSection` with the [overline] ("HIDDEN ON PROTECTED TITLES")
 * and an optional [heading]. Never use a bold row as a heading.
 */
@Composable
fun SettingsSection(
	overline: String,
	modifier: Modifier = Modifier,
	heading: String? = null,
) {
	ListSection(
		modifier = modifier,
		overlineContent = { Text(overline.uppercase()) },
		headingContent = { if (heading != null) Text(heading) },
	)
}

/** The page intro of a settings page: at most two caption lines, said once instead of in every caption. */
@Composable
fun SettingsIntro(text: String, modifier: Modifier = Modifier) {
	Text(
		text = text,
		style = JeType.caption,
		maxLines = 2,
		overflow = TextOverflow.Ellipsis,
		modifier = modifier.padding(horizontal = 12.dp, vertical = 8.dp),
	)
}

/** A checkbox setting: the whole row toggles. [caption] is one line, without "Uncheck to…" prefixes. */
@Composable
fun SettingsToggle(
	title: String,
	checked: Boolean,
	onToggle: (Boolean) -> Unit,
	modifier: Modifier = Modifier,
	caption: String? = null,
	enabled: Boolean = true,
) {
	ListButton(
		onClick = { onToggle(!checked) },
		enabled = enabled,
		modifier = modifier,
		headingContent = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
		captionContent = caption?.let { { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
		trailingContent = { Checkbox(checked = checked) },
	)
}

/**
 * A setting with a few values: the current one is the caption, pressing opens a [JeChoiceDialog]. [captions]
 * describes each option in the dialog.
 */
@Composable
fun SettingsChoice(
	title: String,
	options: List<String>,
	selected: Int,
	onSelect: (Int) -> Unit,
	modifier: Modifier = Modifier,
	enabled: Boolean = true,
	captions: List<String?>? = null,
) {
	var open by remember { mutableStateOf(false) }
	ListButton(
		onClick = { open = true },
		enabled = enabled,
		modifier = modifier,
		headingContent = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
		captionContent = options.getOrNull(selected)?.let { { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
	)
	JeChoiceDialog(
		visible = open,
		title = title,
		options = options,
		selected = setOf(selected),
		captions = captions,
		onConfirm = { picked -> picked.firstOrNull()?.let(onSelect) },
		onDismissRequest = { open = false },
	)
}

/** A row that opens another page: optional leading [icon], one-line [caption], chevron at the end. */
@Composable
fun SettingsNav(
	title: String,
	onClick: () -> Unit,
	modifier: Modifier = Modifier,
	caption: String? = null,
	@DrawableRes icon: Int? = null,
	enabled: Boolean = true,
) {
	ListButton(
		onClick = onClick,
		enabled = enabled,
		modifier = modifier,
		headingContent = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
		captionContent = caption?.let { { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
		leadingContent = icon?.let { { Icon(painterResource(it), contentDescription = null, modifier = Modifier.size(24.dp)) } },
		trailingContent = { Icon(painterResource(R.drawable.je_design_ic_chevron_right), contentDescription = null, modifier = Modifier.size(20.dp)) },
	)
}
