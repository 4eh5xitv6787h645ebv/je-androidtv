package org.jellyfin.androidtv.enhanced.maintenance

import android.content.Context
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.enhanced.data.EnhancedState
import org.jellyfin.androidtv.enhanced.ui.design.JeSpace
import org.jellyfin.androidtv.enhanced.ui.design.Notice
import org.jellyfin.androidtv.enhanced.ui.design.NoticeKind
import org.jellyfin.androidtv.enhanced.ui.hub.HubNotice
import java.time.Instant
import java.time.ZoneId
import kotlin.time.Duration.Companion.seconds

/**
 * The maintenance banner at the top of the Enhanced hub, read fresh each time the hub opens. Not focusable, so it
 * never takes the focus from the hub's tiles; it counts down while shown and goes away when the window ends.
 */
class MaintenanceHubNotice(private val maintenanceRepository: MaintenanceRepository) : HubNotice {
	@Composable
	override fun Content(state: EnhancedState) {
		if (!state.available) return
		val info by produceState<MaintenanceInfo?>(null, state.isAdmin) { value = maintenanceRepository.fetch() }
		var now by remember { mutableStateOf(Instant.now()) }
		LaunchedEffect(Unit) {
			while (true) {
				delay(30.seconds)
				now = Instant.now()
			}
		}
		val notice = info?.let { MaintenanceLogic.notice(it, state.isAdmin, now) } ?: return
		MaintenanceBanner(notice, now)
	}
}

/** The lines of a notice, shared by the banner and the app-start dialog. */
internal class MaintenanceText(val title: String, val message: String, val details: List<String>)

internal fun maintenanceText(context: Context, notice: MaintenanceNotice, now: Instant): MaintenanceText {
	val locale = context.resources.configuration.locales[0]
	val endTime = notice.endsAt?.let { MaintenanceLogic.endTime(it, now, ZoneId.systemDefault(), locale) }
	val message = MaintenanceLogic.formatMessage(notice.message, notice.endsAt, now, endTime)
		?: context.getString(R.string.je_maintenance_default)
	val details = buildList {
		if (notice.endsAt != null && endTime != null) {
			add(context.getString(R.string.je_maintenance_back_around, endTime))
			add(context.getString(R.string.je_maintenance_left, MaintenanceLogic.countdown(notice.endsAt, now)))
		}
		if (notice.selectedUsersOnly && !notice.adminOnly) add(context.getString(R.string.je_maintenance_selected))
	}
	return MaintenanceText(
		title = context.getString(if (notice.adminOnly) R.string.je_maintenance_title_admin else R.string.je_maintenance_title),
		message = message,
		details = details,
	)
}

@Composable
private fun MaintenanceBanner(notice: MaintenanceNotice, now: Instant) {
	val context = LocalContext.current
	val text = remember(notice, now) { maintenanceText(context, notice, now) }
	Notice(
		kind = NoticeKind.Warning,
		title = text.title,
		body = (listOf(text.message) + text.details).joinToString(" · "),
		icon = R.drawable.je_ic_maintenance_construction,
		modifier = Modifier.padding(start = JeSpace.pageHorizontal, end = JeSpace.pageHorizontal, bottom = JeSpace.headerBottom),
	)
}
