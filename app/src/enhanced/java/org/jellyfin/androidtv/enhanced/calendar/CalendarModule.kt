package org.jellyfin.androidtv.enhanced.calendar

import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.enhanced.ui.hub.HubEntry
import org.jellyfin.androidtv.ui.navigation.fragmentDestination
import org.koin.dsl.module

/** Koin bindings of the calendar page. */
val calendarModule = module {
	single { CalendarRepository(get()) }
}

/** The calendar's hub page, shown when the plugin's calendar page is enabled. */
val calendarHubEntry = HubEntry(
	id = "calendar",
	title = R.string.je_calendar_title,
	description = R.string.je_calendar_description,
	icon = R.drawable.je_ic_calendar_month,
	enabled = { it.calendar },
	destination = { fragmentDestination<CalendarFragment>() },
	order = 40,
)
