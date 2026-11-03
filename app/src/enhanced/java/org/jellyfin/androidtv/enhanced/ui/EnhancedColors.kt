package org.jellyfin.androidtv.enhanced.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import org.jellyfin.androidtv.ui.base.LocalTextStyle
import org.jellyfin.design.Tokens

/**
 * Colour for secondary text (captions, metadata lines). It follows the text colour of the surrounding control, so
 * captions inside a focused button turn dark with its title instead of staying light grey on the light focus
 * background; elsewhere it is the usual grey.
 */
@Composable
fun secondaryTextColor(alpha: Float = 0.72f): Color {
	val base = LocalTextStyle.current.color
	return if (base.isSpecified) base.copy(alpha = alpha) else Tokens.Color.colorGrey300
}
