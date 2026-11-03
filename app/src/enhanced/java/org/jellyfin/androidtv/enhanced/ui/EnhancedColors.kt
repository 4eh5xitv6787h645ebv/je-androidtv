package org.jellyfin.androidtv.enhanced.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import org.jellyfin.androidtv.enhanced.ui.design.JeColors
import org.jellyfin.androidtv.ui.base.LocalTextStyle

/**
 * Colour for secondary text (captions, metadata lines) on screens not yet on the design system; new code uses
 * [JeColors.textSecondary] (and `onButtonFocused` inside inverted focus). It follows the text colour of the
 * surrounding control, so captions inside a focused button turn dark with its title instead of staying light grey on
 * the light focus background; elsewhere it is the design system's secondary text colour.
 */
@Composable
fun secondaryTextColor(alpha: Float = 0.72f): Color {
	val base = LocalTextStyle.current.color
	return if (base.isSpecified) base.copy(alpha = alpha) else JeColors.textSecondary
}
