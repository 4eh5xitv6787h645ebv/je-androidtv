package org.jellyfin.androidtv.enhanced.metadata

import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.delay
import org.jellyfin.androidtv.enhanced.ui.design.JeColors
import org.jellyfin.androidtv.enhanced.ui.design.JeShapes
import org.jellyfin.androidtv.enhanced.ui.design.JeType
import org.jellyfin.androidtv.enhanced.ui.design.rememberFocusedState
import org.jellyfin.androidtv.ui.base.Icon
import org.jellyfin.androidtv.ui.base.JellyfinTheme
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.dialog.DialogBase
import org.jellyfin.design.Tokens

/**
 * A fact chip of the details info rows (spec §6.9): the [InfoChip] look (rounded rectangle on `surface`) at rest.
 * Leanback rows can only be reached by focusing something in them, so on the library details screen every chip can
 * take the focus ([focusable]) and inverts like upstream's buttons when it has it. Actionable chips ([onClick]) are
 * pill-shaped with an outline, the "press me" shape of a [FilterChip]. Off leanback (Seerr details) pass
 * [focusable] false so plain facts are skipped by the D-pad.
 */
@Composable
fun MetadataChip(
	text: String,
	modifier: Modifier = Modifier,
	icon: Int? = null,
	leading: (@Composable () -> Unit)? = null,
	onClick: (() -> Unit)? = null,
	focusable: Boolean = true,
) {
	val focus = rememberFocusedState()
	val scheme = JellyfinTheme.colorScheme
	val focused = focus.focused
	val shape = if (onClick != null) JeShapes.pill else JeShapes.image
	val content = if (focused) scheme.onButtonFocused else JeColors.onSurface
	val interaction = when {
		onClick != null -> Modifier.clickable(interactionSource = focus.interactionSource, indication = null, onClick = onClick)
		focusable -> Modifier.focusable(interactionSource = focus.interactionSource)
		else -> Modifier
	}

	Row(
		modifier = modifier
			.then(interaction)
			.background(if (focused) scheme.buttonFocused else JeColors.surfaceOverArt, shape)
			.then(if (onClick != null && !focused) Modifier.border(1.dp, JeColors.outline, shape) else Modifier)
			.height(30.dp)
			.padding(horizontal = if (onClick != null) 13.dp else 10.dp),
		horizontalArrangement = Arrangement.spacedBy(6.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		if (icon != null) Icon(painterResource(icon), contentDescription = null, tint = content, modifier = Modifier.size(16.dp))
		leading?.invoke()
		Text(text = text, style = JeType.meta, color = content, maxLines = 1)
	}
}

/**
 * The surface of our side-panel dialogs (awards, content warnings, rating, translation): a titled column on the side
 * of the screen like the settings drawer, with [JeDialog]'s colours, corners, padding and title style.
 */
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
			.background(JeColors.surface, JeShapes.dialog)
			.padding(24.dp),
		verticalArrangement = Arrangement.spacedBy(12.dp),
	) {
		Text(text = title, style = JeType.dialogTitle, maxLines = 2, overflow = TextOverflow.Ellipsis)
		if (subtitle != null) Text(text = subtitle, style = JeType.caption)
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
) = showDialogHost(fragment) { visible, dismiss ->
	DialogBase(
		visible = visible,
		onDismissRequest = dismiss,
		contentAlignment = alignment,
	) {
		content(dismiss)
	}
}

/**
 * Host a dialog composable that brings its own `DialogBase` ([JeDialog], [JeChoiceDialog]) over any screen, View-based
 * or Compose: [content] gets whether it is showing and a `dismiss` callback to pass as its `onDismissRequest`. Each
 * call gets its own host, so one dialog can open the next from its confirm callback. The host is dropped once the
 * dialog has faded out, or with the fragment's view.
 */
fun showDialogHost(
	fragment: Fragment,
	content: @Composable (visible: Boolean, dismiss: () -> Unit) -> Unit,
) {
	val activity = fragment.activity ?: return
	val owner = fragment.viewLifecycleOwnerLiveData.value ?: return
	showDialogHost(activity, owner, content)
}

/** [showDialogHost] for places that only have the activity (card menus); the host goes with [owner]. */
fun showDialogHost(
	activity: FragmentActivity,
	owner: LifecycleOwner = activity,
	content: @Composable (visible: Boolean, dismiss: () -> Unit) -> Unit,
) {
	val root = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
	val host = ComposeView(activity)
	val visible = mutableStateOf(true)
	val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_DESTROY) root.removeView(host) }

	host.setContent {
		JellyfinTheme {
			content(visible.value) { visible.value = false }
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
