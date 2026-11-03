package org.jellyfin.androidtv.enhanced.subtitles

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class SubtitleSyncPlannerTest : FunSpec({
	val own = NativeSubtitleStyle(textColor = 0xFFFFFFFF.toInt(), backgroundColor = 0xFF000000.toInt(), strokeColor = 0, textSize = 30f, offsetPosition = 0.1f)
	val je = JeSubtitleStyle(textColor = "#FFFF00FF", backgroundColor = "#000000B2", fontSizePreset = 3, verticalPosition = 85)
	val mapped = SubtitleStyleMapper.map(je)

	test("the first sync saves the user's own values and applies the JE style") {
		SubtitleSyncPlanner.plan(enabled = true, style = je, current = own, backup = null, lastApplied = null) shouldBe
			SubtitleSyncAction.Apply(mapped, backup = own)
	}

	test("later syncs keep the first backup") {
		SubtitleSyncPlanner.plan(enabled = true, style = je.copy(fontSizePreset = 1), current = mapped, backup = own, lastApplied = mapped) shouldBe
			SubtitleSyncAction.Apply(SubtitleStyleMapper.map(je.copy(fontSizePreset = 1)), backup = null)
	}

	test("nothing is written when the applied style is already in place") {
		SubtitleSyncPlanner.plan(enabled = true, style = je, current = mapped, backup = own, lastApplied = mapped) shouldBe SubtitleSyncAction.None
	}

	test("a style changed by hand in the app's settings is synced again") {
		val edited = mapped.copy(textSize = 12f)
		SubtitleSyncPlanner.plan(enabled = true, style = je, current = edited, backup = own, lastApplied = mapped) shouldBe
			SubtitleSyncAction.Apply(mapped, backup = null)
	}

	test("nothing is written while JE's custom subtitle styles are off or the style is unknown") {
		SubtitleSyncPlanner.plan(enabled = true, style = je.copy(disabled = true), current = own, backup = null, lastApplied = null) shouldBe SubtitleSyncAction.None
		SubtitleSyncPlanner.plan(enabled = true, style = je.copy(disabled = true), current = mapped, backup = own, lastApplied = mapped) shouldBe SubtitleSyncAction.None
		SubtitleSyncPlanner.plan(enabled = true, style = null, current = mapped, backup = own, lastApplied = mapped) shouldBe SubtitleSyncAction.None
	}

	test("turning the sync off restores the backup, and does nothing without one") {
		SubtitleSyncPlanner.plan(enabled = false, style = je, current = mapped, backup = own, lastApplied = mapped) shouldBe SubtitleSyncAction.Restore(own)
		SubtitleSyncPlanner.plan(enabled = false, style = null, current = mapped, backup = own, lastApplied = mapped) shouldBe SubtitleSyncAction.Restore(own)
		SubtitleSyncPlanner.plan(enabled = false, style = je, current = own, backup = null, lastApplied = null) shouldBe SubtitleSyncAction.None
	}

	test("restoring keeps values the user changed by hand after the last sync") {
		val edited = mapped.copy(textSize = 40f, backgroundColor = 0)
		SubtitleSyncPlanner.restored(edited, own, mapped) shouldBe own.copy(textSize = 40f, backgroundColor = 0)
		SubtitleSyncPlanner.restored(edited, own, null) shouldBe own
	}
})
