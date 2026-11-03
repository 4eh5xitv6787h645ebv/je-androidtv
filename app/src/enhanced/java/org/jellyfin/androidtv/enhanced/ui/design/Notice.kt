package org.jellyfin.androidtv.enhanced.ui.design

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.ui.base.Icon
import org.jellyfin.androidtv.ui.base.Text

/** The default icon of a notice kind. */
@DrawableRes
fun NoticeKind.defaultIcon(): Int = when (this) {
	NoticeKind.Info -> R.drawable.je_design_ic_info
	NoticeKind.Warning -> R.drawable.je_design_ic_warning
	NoticeKind.Error -> R.drawable.je_design_ic_error
}

/**
 * A banner or inline notice (spec §6.13) in the [NoticeKind] palette: maintenance mode, a missing Seerr link, a
 * calendar source that failed, an orphaned request. **Not focusable**: if it needs an action, put a [JeButton] after
 * it (or in [trailing], which must then hold the only focusable content). The banner has a 4 dp coloured stripe on
 * the left; [inline] drops the container and shows icon + caption text only. The [body] follows the [title] on the
 * same line and wraps under it. [icon] overrides the kind's icon (the maintenance wrench).
 */
@Composable
fun Notice(
	kind: NoticeKind,
	title: String,
	modifier: Modifier = Modifier,
	body: String? = null,
	@DrawableRes icon: Int? = null,
	inline: Boolean = false,
	trailing: (@Composable () -> Unit)? = null,
) {
	val text = buildAnnotatedString {
		withStyle(SpanStyle(color = if (inline) kind.body else kind.title, fontWeight = if (inline) null else FontWeight.W500)) { append(title) }
		if (body != null) {
			append("  ")
			withStyle(SpanStyle(color = kind.body)) { append(body) }
		}
	}

	if (inline) {
		Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
			Icon(painterResource(icon ?: kind.defaultIcon()), contentDescription = null, tint = kind.stripe, modifier = Modifier.size(18.dp))
			Text(text = text, style = JeType.caption)
		}
		return
	}

	Row(
		modifier = modifier
			.fillMaxWidth()
			.height(IntrinsicSize.Min)
			.clip(JeShapes.container)
			.background(kind.container),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Box(Modifier.width(4.dp).fillMaxHeight().background(kind.stripe))
		Row(
			modifier = Modifier
				.weight(1f)
				.padding(horizontal = 14.dp, vertical = 12.dp),
			horizontalArrangement = Arrangement.spacedBy(12.dp),
			verticalAlignment = Alignment.CenterVertically,
		) {
			Icon(painterResource(icon ?: kind.defaultIcon()), contentDescription = null, tint = kind.stripe, modifier = Modifier.size(20.dp))
			Text(text = text, style = JeType.body, modifier = Modifier.weight(1f))
			trailing?.invoke()
		}
	}
}
