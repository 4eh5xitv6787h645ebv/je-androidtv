package org.jellyfin.androidtv.enhanced.playback

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.util.UUID

class PauseScreenTimerTest : FunSpec({
	val itemA = UUID.fromString("11111111-1111-1111-1111-111111111111")
	val itemB = UUID.fromString("22222222-2222-2222-2222-222222222222")

	fun input(
		paused: Boolean = true,
		itemId: UUID? = itemA,
		enabled: Boolean = true,
		blocked: Boolean = false,
		positionMs: Long = 60_000,
		delayMs: Long = 5_000,
	) = PauseScreenTimer.Input(enabled, delayMs, itemId, paused, positionMs, blocked)

	test("shows only after the delay of an uninterrupted pause") {
		val timer = PauseScreenTimer()
		timer.update(input(paused = false), 0) shouldBe false
		timer.update(input(), 1_000) shouldBe false
		timer.update(input(), 5_999) shouldBe false
		timer.update(input(), 6_000) shouldBe true
		timer.visible shouldBe true
	}

	test("never shows while playing") {
		val timer = PauseScreenTimer()
		timer.update(input(paused = false), 0) shouldBe false
		timer.update(input(paused = false), 60_000) shouldBe false
	}

	test("hides instantly on resume and restarts the delay on the next pause") {
		val timer = PauseScreenTimer()
		timer.update(input(), 0)
		timer.update(input(), 5_000) shouldBe true
		timer.update(input(paused = false), 5_100) shouldBe false
		timer.update(input(), 6_000) shouldBe false
		timer.update(input(), 10_999) shouldBe false
		timer.update(input(), 11_000) shouldBe true
	}

	test("input hides it and restarts the idle delay; it comes back when idle again") {
		val timer = PauseScreenTimer()
		timer.update(input(), 0)
		timer.update(input(), 5_000) shouldBe true
		timer.onInput(7_000, dismiss = false) shouldBe true
		timer.visible shouldBe false
		timer.update(input(), 11_999) shouldBe false
		timer.update(input(), 12_000) shouldBe true
	}

	test("input before the delay postpones it") {
		val timer = PauseScreenTimer()
		timer.update(input(), 0)
		timer.onInput(4_000, dismiss = false) shouldBe false
		timer.update(input(), 5_000) shouldBe false
		timer.update(input(), 9_000) shouldBe true
	}

	test("Back dismisses it for the rest of the pause only") {
		val timer = PauseScreenTimer()
		timer.update(input(), 0)
		timer.update(input(), 5_000) shouldBe true
		timer.onInput(6_000, dismiss = true) shouldBe true
		timer.update(input(), 60_000) shouldBe false
		// Resume, pause again: shows again after the delay
		timer.update(input(paused = false), 61_000)
		timer.update(input(), 62_000) shouldBe false
		timer.update(input(), 67_000) shouldBe true
	}

	test("Back before it is showing doesn't suppress it") {
		val timer = PauseScreenTimer()
		timer.update(input(), 0)
		timer.onInput(1_000, dismiss = true) shouldBe false
		timer.update(input(), 6_000) shouldBe true
	}

	test("stays hidden while something else is open and counts idle time from when it closes") {
		val timer = PauseScreenTimer()
		timer.update(input(), 0)
		timer.update(input(blocked = true), 5_000) shouldBe false
		timer.update(input(blocked = true), 20_000) shouldBe false
		timer.update(input(), 20_250) shouldBe false
		timer.update(input(), 25_000) shouldBe true
		// Showing, then a dialog opens on top: hide
		timer.update(input(blocked = true), 26_000) shouldBe false
	}

	test("a seek while paused counts as input") {
		val timer = PauseScreenTimer()
		timer.update(input(positionMs = 60_000), 0)
		timer.update(input(positionMs = 60_000), 5_000) shouldBe true
		timer.update(input(positionMs = 90_000), 5_250) shouldBe false
		timer.update(input(positionMs = 90_000), 10_249) shouldBe false
		timer.update(input(positionMs = 90_000), 10_250) shouldBe true
	}

	test("small position drift after pausing is not a seek") {
		val timer = PauseScreenTimer()
		timer.update(input(positionMs = 60_000), 0)
		timer.update(input(positionMs = 60_400), 5_000) shouldBe true
	}

	test("an item change resets it") {
		val timer = PauseScreenTimer()
		timer.update(input(), 0)
		timer.update(input(), 5_000) shouldBe true
		timer.update(input(itemId = itemB), 5_100) shouldBe false
		timer.update(input(itemId = itemB), 10_099) shouldBe false
		timer.update(input(itemId = itemB), 10_100) shouldBe true
		timer.update(input(itemId = null), 10_200) shouldBe false
	}

	test("the setting turns it off live and back on") {
		val timer = PauseScreenTimer()
		timer.update(input(), 0)
		timer.update(input(enabled = false), 10_000) shouldBe false
		timer.update(input(enabled = true), 10_250) shouldBe false
		timer.update(input(enabled = true), 15_250) shouldBe true
	}

	test("a longer delay from the settings applies to the running pause") {
		val timer = PauseScreenTimer()
		timer.update(input(delayMs = 5_000), 0)
		timer.update(input(delayMs = 30_000), 10_000) shouldBe false
		timer.update(input(delayMs = 30_000), 30_000) shouldBe true
	}
})
