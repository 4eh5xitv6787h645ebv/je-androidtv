package org.jellyfin.androidtv.enhanced.metadata

import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.delay
import org.jellyfin.androidtv.ui.base.Icon
import org.jellyfin.androidtv.ui.base.JellyfinTheme
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.dialog.DialogBase
import org.jellyfin.design.Tokens

/**
 * A small focusable pill for the details info rows. Every chip can take focus so the row can be reached and
 * scrolled to with the D-pad; [onClick] makes it actionable.
 */
@Composable
fun MetadataChip(
	text: String,
	modifier: Modifier = Modifier,
	icon: Int? = null,
	leading: (@Composable () -> Unit)? = null,
	highlight: Boolean = false,
	onClick: (() -> Unit)? = null,
) {
	val interactionSource = remember { MutableInteractionSource() }
	val focused by interactionSource.collectIsFocusedAsState()
	val shape = RoundedCornerShape(8.dp)
	val background = when {
		focused -> JellyfinTheme.colorScheme.buttonFocused
		highlight -> Tokens.Color.colorRed300.copy(alpha = 0.35f)
		else -> Color.White.copy(alpha = 0.08f)
	}
	val content = if (focused) JellyfinTheme.colorScheme.onButtonFocused else Tokens.Color.colorGrey100
	val interaction = if (onClick != null) {
		Modifier.clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
	} else {
		Modifier.focusable(interactionSource = interactionSource)
	}

	Row(
		modifier = modifier
			.then(interaction)
			.background(background, shape)
			.border(1.dp, if (highlight && !focused) Tokens.Color.colorRed300 else Color.Transparent, shape)
			.heightIn(min = 32.dp)
			.padding(horizontal = 10.dp, vertical = 6.dp),
		horizontalArrangement = Arrangement.spacedBy(6.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		if (icon != null) Icon(painterResource(icon), contentDescription = null, tint = content, modifier = Modifier.size(18.dp))
		leading?.invoke()
		Text(text = text, color = content, fontSize = 14.sp, maxLines = 1)
	}
}

/** The surface of our dialogs: a titled column on the side of the screen, like the settings panel. */
@Composable
fun MetadataDialogPanel(
	title: String,
	modifier: Modifier = Modifier,
	subtitle: String? = null,
	width: Dp = 560.dp,
	content: @Composable ColumnScope.() -> Unit,
) {
	Column(
		modifier = modifier
			.padding(Tokens.Space.spaceMd)
			.width(width)
			.fillMaxHeight()
			.background(JellyfinTheme.colorScheme.surface, JellyfinTheme.shapes.large)
			.padding(24.dp),
		verticalArrangement = Arrangement.spacedBy(12.dp),
	) {
		Text(text = title, fontSize = 22.sp, color = Tokens.Color.colorWhite)
		if (subtitle != null) Text(text = subtitle, fontSize = 14.sp, color = Tokens.Color.colorGrey300)
		content()
	}
}

/**
 * Show a Compose dialog over a View-based screen (the leanback details screen). [content] gets a `dismiss` callback;
 * Back and clicks outside dismiss too. The host view is dropped with the fragment's view.
 */
fun showComposeDialog(
	fragment: Fragment,
	alignment: Alignment = Alignment.CenterEnd,
	content: @Composable (dismiss: () -> Unit) -> Unit,
) {
	val activity = fragment.activity ?: return
	val root = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
	val owner = fragment.viewLifecycleOwnerLiveData.value ?: return
	val host = ComposeView(activity)
	val visible = mutableStateOf(true)
	val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_DESTROY) root.removeView(host) }

	host.setContent {
		JellyfinTheme {
			DialogBase(
				visible = visible.value,
				onDismissRequest = { visible.value = false },
				contentAlignment = alignment,
			) {
				content { visible.value = false }
			}
		}
		LaunchedEffect(visible.value) {
			if (!visible.value) {
				// Let the exit animation finish before dropping the host
				delay(500)
				owner.lifecycle.removeObserver(observer)
				root.removeView(host)
			}
		}
	}
	root.addView(host, ViewGroup.LayoutParams(0, 0))
	owner.lifecycle.addObserver(observer)
}
