package org.jellyfin.androidtv.enhanced.ui.design

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.unit.dp

/**
 * The focus rule (spec §5), in short:
 * - text/icon only (buttons, chips, tabs, hub tiles, text-only rows): **inversion**, upstream's `buttonFocused` fill
 *   with `onButtonFocused` content;
 * - artwork or coloured badges (cards, rows with posters or status badges): **ring**, [jeFocusRing] plus the
 *   [JeColors.surfaceRaised] fill, content colours unchanged;
 * - selection is never a light fill: a selected tab has the cyan underline, a selected chip the check icon.
 *
 * No focus scaling, and no animated focus colours.
 */
@Stable
class FocusedState internal constructor(
	val interactionSource: MutableInteractionSource,
	private val state: State<Boolean>,
) {
	val focused: Boolean get() = state.value
}

/** An interaction source and whether it is focused; pass [FocusedState.interactionSource] to the clickable. */
@Composable
fun rememberFocusedState(interactionSource: MutableInteractionSource? = null): FocusedState {
	val source = interactionSource ?: remember { MutableInteractionSource() }
	val focused = source.collectIsFocusedAsState()
	return remember(source, focused) { FocusedState(source, focused) }
}

/** The 2 dp focus ring of the "ring" treatment. Apply after any clip so it isn't cut off. */
fun Modifier.jeFocusRing(focused: Boolean, shape: Shape = JeShapes.container): Modifier =
	if (focused) border(2.dp, JeColors.focusRing, shape) else this

/** Container of a ring-focused element: [JeColors.surface] at rest, [JeColors.surfaceRaised] and the ring on focus. */
fun Modifier.jeRingContainer(focused: Boolean, shape: Shape = JeShapes.container): Modifier =
	background(if (focused) JeColors.surfaceRaised else JeColors.surface, shape).jeFocusRing(focused, shape)

/** Dims a disabled element (0.4 alpha, as upstream's list controls). */
fun Modifier.jeDisabled(enabled: Boolean): Modifier = if (enabled) this else alpha(0.4f)

/**
 * Runs [onMenu] when the remote's menu key is released while this element (or a child) has the focus, like the app's
 * own cards. Pair it with `onLongClick` for remotes without a menu key. Null does nothing.
 */
fun Modifier.onMenuKey(onMenu: (() -> Unit)?): Modifier = if (onMenu == null) this else onPreviewKeyEvent { event ->
	if (event.nativeKeyEvent.keyCode != KeyEvent.KEYCODE_MENU) return@onPreviewKeyEvent false
	if (event.nativeKeyEvent.action == KeyEvent.ACTION_UP) onMenu()
	true
}
