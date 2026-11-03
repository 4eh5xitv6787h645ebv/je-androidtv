package org.jellyfin.androidtv.enhanced.maintenance

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import org.jellyfin.androidtv.enhanced.api.EnhancedApi
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

class MaintenanceLogicTest : FunSpec({
	val now = Instant.parse("2026-11-03T20:00:00Z")
	val inAnHour = "2026-11-03T21:05:00Z"

	fun info(
		enabled: Boolean = true,
		endsAt: String? = inAnHour,
		action: String? = "disable_accounts",
		affected: String? = "all",
		message: String? = "Upgrading the server.",
	) = MaintenanceInfo(enabled = enabled, message = message, endsAt = endsAt, action = action, affectedUsers = affected)

	test("decodes the maintenance fields of public-config") {
		// Trimmed from the lab server's public-config; EndsAt is left out while open-ended
		val json = """{"BookmarksEnabled":true,"MaintenanceModeEnabled":true,"MaintenanceModeMessage":"Back soon",
			"MaintenanceModeEndsAt":"2026-11-03T21:05:00Z","MaintenanceModeAction":"none","MaintenanceModeAffectedUsers":"selected"}"""
		val decoded = EnhancedApi.json.decodeFromString<MaintenanceInfo>(json)
		decoded shouldBe MaintenanceInfo(true, "Back soon", "2026-11-03T21:05:00Z", "none", "selected")
		EnhancedApi.json.decodeFromString<MaintenanceInfo>("""{"MaintenanceModeEnabled":false}""").enabled shouldBe false
	}

	test("no notice while maintenance is off or once its end time has passed") {
		MaintenanceLogic.notice(info(enabled = false), isAdmin = false, now) shouldBe null
		MaintenanceLogic.notice(info(endsAt = "2026-11-03T19:59:00Z"), isAdmin = false, now) shouldBe null
		MaintenanceLogic.notice(info(endsAt = "2026-11-03T20:00:00Z"), isAdmin = false, now) shouldBe null
	}

	test("applies to every non-admin, with or without an end time") {
		val timed = MaintenanceLogic.notice(info(), isAdmin = false, now).shouldNotBeNull()
		timed.adminOnly shouldBe false
		timed.endsAt shouldBe Instant.parse(inAnHour)
		timed.message shouldBe "Upgrading the server."

		val open = MaintenanceLogic.notice(info(endsAt = null), isAdmin = false, now).shouldNotBeNull()
		open.endsAt shouldBe null
		open.adminOnly shouldBe false
	}

	test("administrators aren't locked out, so a disabling window is banner-only for them") {
		for (action in listOf("disable_accounts", "disable_remote", "both", null)) {
			MaintenanceLogic.notice(info(action = action), isAdmin = true, now).shouldNotBeNull().adminOnly shouldBe true
		}
	}

	test("a window that disables nothing applies to administrators too") {
		MaintenanceLogic.notice(info(action = "none"), isAdmin = true, now).shouldNotBeNull().adminOnly shouldBe false
		MaintenanceLogic.notice(info(action = "None"), isAdmin = true, now).shouldNotBeNull().adminOnly shouldBe false
	}

	test("selected users only: non-admins are still told, with a 'may not affect you' note") {
		val selected = MaintenanceLogic.notice(info(affected = "selected"), isAdmin = false, now).shouldNotBeNull()
		selected.adminOnly shouldBe false
		selected.selectedUsersOnly shouldBe true
		// Nobody is locked out when nothing is disabled, so the note doesn't apply
		MaintenanceLogic.notice(info(affected = "selected", action = "none"), isAdmin = false, now).shouldNotBeNull().selectedUsersOnly shouldBe false
		MaintenanceLogic.notice(info(affected = "all"), isAdmin = false, now).shouldNotBeNull().selectedUsersOnly shouldBe false
	}

	test("a blank message falls back to the default text") {
		MaintenanceLogic.notice(info(message = "  "), isAdmin = false, now).shouldNotBeNull().message shouldBe null
	}

	test("end times without an offset are UTC; unreadable ones count as open-ended") {
		MaintenanceLogic.endsAt(info(endsAt = "2026-11-03T21:05:00")) shouldBe Instant.parse(inAnHour)
		MaintenanceLogic.endsAt(info(endsAt = "2026-11-03T21:05:00.1234567Z")) shouldBe Instant.parse("2026-11-03T21:05:00.1234567Z")
		MaintenanceLogic.endsAt(info(endsAt = "soon")) shouldBe null
		MaintenanceLogic.endsAt(info(endsAt = "")) shouldBe null
	}

	test("a window is known by its end time, or by its message while open-ended") {
		val a = MaintenanceLogic.notice(info(), isAdmin = false, now)!!.windowKey
		a shouldBe MaintenanceLogic.notice(info(message = "Other text"), isAdmin = false, now)!!.windowKey
		a shouldNotBe MaintenanceLogic.notice(info(endsAt = "2026-11-03T22:00:00Z"), isAdmin = false, now)!!.windowKey

		val open = MaintenanceLogic.notice(info(endsAt = null), isAdmin = false, now)!!.windowKey
		open shouldBe MaintenanceLogic.notice(info(endsAt = null), isAdmin = false, now.plusSeconds(3600))!!.windowKey
		open shouldNotBe MaintenanceLogic.notice(info(endsAt = null, message = "Other text"), isAdmin = false, now)!!.windowKey
		open shouldNotBe a
	}

	test("the dialog shows once per window and is forgotten when maintenance ends") {
		val notice = MaintenanceLogic.notice(info(), isAdmin = false, now)!!
		MaintenanceLogic.dialogDecision(notice, seenWindow = null) shouldBe MaintenanceDialogDecision.SHOW
		MaintenanceLogic.dialogDecision(notice, seenWindow = notice.windowKey) shouldBe MaintenanceDialogDecision.NONE
		// A new window (another end time) shows again
		MaintenanceLogic.dialogDecision(notice, seenWindow = "ends:1") shouldBe MaintenanceDialogDecision.SHOW
		// Maintenance off: forget, so the same open-ended text shows next time it's turned on
		MaintenanceLogic.dialogDecision(null, seenWindow = notice.windowKey) shouldBe MaintenanceDialogDecision.FORGET
		MaintenanceLogic.dialogDecision(null, seenWindow = null) shouldBe MaintenanceDialogDecision.NONE
	}

	test("administrators get no dialog for a window that doesn't lock them out") {
		val notice = MaintenanceLogic.notice(info(), isAdmin = true, now)!!
		MaintenanceLogic.dialogDecision(notice, seenWindow = null) shouldBe MaintenanceDialogDecision.NONE
	}

	test("countdown reads like the plugin's, rounding minutes up") {
		MaintenanceLogic.countdown(Instant.parse(inAnHour), now) shouldBe "1h 05m"
		MaintenanceLogic.countdown(now.plusSeconds(12 * 60 + 1), now) shouldBe "13m"
		MaintenanceLogic.countdown(now.plusSeconds(60), now) shouldBe "1m"
		MaintenanceLogic.countdown(now, now) shouldBe "0m"
		MaintenanceLogic.countdown(now.minusSeconds(60), now) shouldBe "0m"
	}

	test("message tokens are replaced, or dropped while open-ended") {
		val endsAt = Instant.parse(inAnHour)
		MaintenanceLogic.formatMessage("Updating. Time remaining: {countdown}, back at {ENDS_AT}.", endsAt, now, "9:05 PM") shouldBe
			"Updating. Time remaining: 1h 05m, back at 9:05 PM."
		MaintenanceLogic.formatMessage("Updating {Countdown}", null, now, null) shouldBe "Updating"
		MaintenanceLogic.formatMessage("{countdown}", null, now, null) shouldBe null
		MaintenanceLogic.formatMessage("  ", endsAt, now, "9:05 PM") shouldBe null
		MaintenanceLogic.formatMessage(null, endsAt, now, "9:05 PM") shouldBe null
	}

	test("the end time is local, with the weekday on another day and the date beyond a week") {
		val zone = ZoneId.of("America/New_York")
		// The JDK's CLDR data separates "PM" with a narrow no-break space
		fun format(endsAt: String) = MaintenanceLogic.endTime(Instant.parse(endsAt), now, zone, Locale.US).replace(' ', ' ')

		format("2026-11-04T02:30:00Z") shouldBe "9:30 PM"
		format("2026-11-05T02:30:00Z") shouldBe "Wed 9:30 PM"
		format("2026-11-20T02:30:00Z") shouldContain "Nov 19, 2026"
	}

	test("player screens are recognised by their package") {
		MaintenanceLogic.isPlaybackScreen("org.jellyfin.androidtv.ui.playback.CustomPlaybackOverlayFragment") shouldBe true
		MaintenanceLogic.isPlaybackScreen("org.jellyfin.androidtv.ui.player.video.VideoPlayerFragment") shouldBe true
		MaintenanceLogic.isPlaybackScreen("org.jellyfin.androidtv.ui.home.HomeFragment") shouldBe false
		MaintenanceLogic.isPlaybackScreen("org.jellyfin.androidtv.enhanced.ui.hub.EnhancedHubFragment") shouldBe false
	}
})
