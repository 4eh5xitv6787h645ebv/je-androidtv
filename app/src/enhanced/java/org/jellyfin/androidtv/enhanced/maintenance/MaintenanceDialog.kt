package org.jellyfin.androidtv.enhanced.maintenance

import android.view.ViewGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.enhanced.ui.design.JeButton
import org.jellyfin.androidtv.enhanced.ui.design.JeDialog
import org.jellyfin.androidtv.enhanced.ui.design.JeType
import org.jellyfin.androidtv.enhanced.ui.design.NoticeKind
import org.jellyfin.androidtv.ui.base.Icon
import org.jellyfin.androidtv.ui.base.JellyfinTheme
import org.jellyfin.androidtv.ui.base.Text

/**
 * The app-start maintenance dialog as a [JeDialog], hosted in a [ComposeView] added to the activity's content
 * view (the notifier works outside any fragment). [onDismiss] runs once, whether the user closed it (OK or Back) or
 * [dismiss] was called; the caller tells the two apart itself, as with a framework dialog's dismiss listener.
 */
internal class MaintenanceDialog private constructor(
	private val host: ComposeView,
	private val onDismiss: () -> Unit,
) {
	private val visible = mutableStateOf(true)
	private var dismissed = false

	fun dismiss() {
		if (dismissed) return
		dismissed = true
		visible.value = false
		// Not from inside the click that closed it: the view is still dispatching that event
		host.post { (host.parent as? ViewGroup)?.removeView(host) }
		onDismiss()
	}

	companion object {
		fun show(activity: FragmentActivity, text: MaintenanceText, onDismiss: () -> Unit): MaintenanceDialog {
			val host = ComposeView(activity)
			val dialog = MaintenanceDialog(host, onDismiss)
			host.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
			host.setContent {
				JellyfinTheme {
					JeDialog(
						visible = dialog.visible.value,
						onDismissRequest = dialog::dismiss,
						title = text.title,
						actions = { JeButton(text = stringResource(R.string.je_design_ok), onClick = dialog::dismiss) },
					) {
						Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
							Icon(
								painterResource(R.drawable.je_ic_maintenance_construction),
								contentDescription = null,
								tint = NoticeKind.Warning.stripe,
								modifier = Modifier.size(24.dp),
							)
							Text(text = text.message, style = JeType.body)
						}
						if (text.details.isNotEmpty()) Text(text = text.details.joinToString("\n"), style = JeType.caption)
					}
				}
			}
			activity.findViewById<ViewGroup>(android.R.id.content).addView(
				host,
				ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
			)
			return dialog
		}
	}
}
