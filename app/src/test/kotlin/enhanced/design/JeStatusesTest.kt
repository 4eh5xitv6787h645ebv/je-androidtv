package org.jellyfin.androidtv.enhanced.ui.design

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.enhanced.seerr.SeerrMediaStatus

class JeStatusesTest : FunSpec({
	test("Seerr media statuses map to the palette, unknown and never-requested show nothing") {
		JeStatuses.seerrMedia(SeerrMediaStatus.PENDING) shouldBe StatusBadgeSpec(JeStatus.Pending, labelRes = R.string.je_design_status_requested)
		JeStatuses.seerrMedia(SeerrMediaStatus.PROCESSING)?.status shouldBe JeStatus.Processing
		JeStatuses.seerrMedia(SeerrMediaStatus.PARTIALLY_AVAILABLE)?.status shouldBe JeStatus.PartlyAvailable
		JeStatuses.seerrMedia(SeerrMediaStatus.AVAILABLE)?.status shouldBe JeStatus.Available
		JeStatuses.seerrMedia(SeerrMediaStatus.BLOCKLISTED) shouldBe StatusBadgeSpec(JeStatus.Declined, labelRes = R.string.je_design_status_blocklisted)
		JeStatuses.seerrMedia(SeerrMediaStatus.DELETED) shouldBe StatusBadgeSpec(JeStatus.Neutral, labelRes = R.string.je_design_status_deleted)
		JeStatuses.seerrMedia(SeerrMediaStatus.UNKNOWN).shouldBeNull()
		JeStatuses.seerrMedia(0).shouldBeNull()
		JeStatuses.seerrMedia(null).shouldBeNull()
	}

	test("seasons without a status read Not requested") {
		JeStatuses.seerrSeason(null) shouldBe StatusBadgeSpec(JeStatus.Neutral, labelRes = R.string.je_design_status_not_requested)
		JeStatuses.seerrSeason(SeerrMediaStatus.UNKNOWN).labelRes shouldBe R.string.je_design_status_not_requested
		JeStatuses.seerrSeason(SeerrMediaStatus.PROCESSING).status shouldBe JeStatus.Processing
	}

	test("Seerr request statuses") {
		JeStatuses.seerrRequest(1) shouldBe StatusBadgeSpec(JeStatus.Pending)
		JeStatuses.seerrRequest(2) shouldBe StatusBadgeSpec(JeStatus.Approved)
		JeStatuses.seerrRequest(3) shouldBe StatusBadgeSpec(JeStatus.Declined)
		JeStatuses.seerrRequest(4) shouldBe StatusBadgeSpec(JeStatus.Declined, labelRes = R.string.je_design_status_failed)
		JeStatuses.seerrRequest(5) shouldBe StatusBadgeSpec(JeStatus.Available, labelRes = R.string.je_design_status_completed)
		JeStatuses.seerrRequest(null) shouldBe StatusBadgeSpec(JeStatus.Neutral)
	}

	test("Seerr issue statuses") {
		JeStatuses.seerrIssue(1) shouldBe StatusBadgeSpec(JeStatus.Warning, labelRes = R.string.je_design_status_open)
		JeStatuses.seerrIssue(2) shouldBe StatusBadgeSpec(JeStatus.Available, labelRes = R.string.je_design_status_resolved)
		JeStatuses.seerrIssue(9).status shouldBe JeStatus.Neutral
	}

	test("text labels ignore case, spaces and underscores") {
		JeStatuses.label("Partially Available").status shouldBe JeStatus.PartlyAvailable
		JeStatuses.label("PARTIALLY_AVAILABLE").status shouldBe JeStatus.PartlyAvailable
		JeStatuses.label("COMING_SOON").status shouldBe JeStatus.ComingSoon
		JeStatuses.label("Pending") shouldBe StatusBadgeSpec(JeStatus.Pending)
		JeStatuses.label("approved").status shouldBe JeStatus.Approved
		JeStatuses.label("Requested") shouldBe StatusBadgeSpec(JeStatus.Processing, labelRes = R.string.je_design_status_requested)
		JeStatuses.label("ORPHANED") shouldBe StatusBadgeSpec(JeStatus.Warning, labelRes = R.string.je_design_status_orphaned)
		JeStatuses.label("Downloaded") shouldBe StatusBadgeSpec(JeStatus.Available, labelRes = R.string.je_design_status_downloaded)
		JeStatuses.label("Monitored").status shouldBe JeStatus.ComingSoon
		JeStatuses.label("Blocklisted").labelRes shouldBe R.string.je_design_status_blocklisted
		JeStatuses.label("not-requested").labelRes shouldBe R.string.je_design_status_not_requested
		JeStatuses.label(null) shouldBe StatusBadgeSpec(JeStatus.Neutral)
	}

	test("unknown text labels stay visible as neutral badges") {
		JeStatuses.label(" Something New ") shouldBe StatusBadgeSpec(JeStatus.Neutral, text = "Something New")
	}

	test("download queue states") {
		JeStatuses.queue("downloading") shouldBe StatusBadgeSpec(JeStatus.Processing, labelRes = R.string.je_design_status_downloading)
		JeStatuses.queue("Paused").status shouldBe JeStatus.Warning
		JeStatuses.queue("delay").labelRes shouldBe R.string.je_design_status_delayed
		JeStatuses.queue("downloadClientUnavailable") shouldBe StatusBadgeSpec(JeStatus.Declined, labelRes = R.string.je_design_status_failed)
		JeStatuses.queue("completed").status shouldBe JeStatus.Available
		JeStatuses.queue("queued").status shouldBe JeStatus.Neutral
		JeStatuses.queue(null) shouldBe StatusBadgeSpec(JeStatus.Neutral)
		JeStatuses.queue("fallback").text shouldBe "fallback"
	}

	test("only the warning status carries an icon") {
		JeStatus.entries.filter { it.showsIcon } shouldBe listOf(JeStatus.Warning)
	}
})
