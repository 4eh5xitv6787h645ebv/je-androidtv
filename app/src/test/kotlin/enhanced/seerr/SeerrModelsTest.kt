package org.jellyfin.androidtv.enhanced.seerr

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class SeerrModelsTest : FunSpec({
	test("standard requests remain available when the standard library copy is missing") {
		val media = SeerrMediaInfo(status = SeerrMediaStatus.AVAILABLE, jellyfinMediaId4k = "other-copy")
		SeerrMediaStatus.isRequestable(media.effectiveStatus) shouldBe true
		val available = media.copy(jellyfinMediaId = "standard-copy")
		available.effectiveStatus shouldBe SeerrMediaStatus.AVAILABLE
		SeerrMediaStatus.isRequestable(available.effectiveStatus) shouldBe false
	}

	test("stale available seasons can be requested again when the standard copy is missing") {
		val media = SeerrMediaInfo(
			jellyfinMediaId4k = "other-copy",
			seasons = listOf(SeerrSeasonStatus(seasonNumber = 1, status = SeerrMediaStatus.AVAILABLE)),
		)
		SeerrMediaStatus.isRequestable(media.effectiveSeasonStatus(1)) shouldBe true
		SeerrMediaStatus.isRequestable(media.copy(jellyfinMediaId = "standard-copy").effectiveSeasonStatus(1)) shouldBe false
		SeerrMediaStatus.isRequestable(media.effectiveSeasonStatus(2)) shouldBe true
	}

	test("pending and processing requests stay unavailable for duplicate requests") {
		listOf(SeerrMediaStatus.PENDING, SeerrMediaStatus.PROCESSING, SeerrMediaStatus.BLOCKLISTED).forEach { status ->
			val media = SeerrMediaInfo(status = status)
			media.effectiveStatus shouldBe status
			SeerrMediaStatus.isRequestable(media.effectiveStatus) shouldBe false
		}
	}

	test("discovery cards do not claim a deleted copy is available") {
		val result = SeerrResult(id = 1, mediaInfo = SeerrMediaInfo(status = SeerrMediaStatus.AVAILABLE))
		result.status shouldBe SeerrMediaStatus.DELETED
	}

	test("protected actor and crew cards withhold credits even when artwork resolution failed") {
		val result = SeerrResult(id = 1, character = "A revealing role", job = "Director")
		result.displayCredit shouldBe "A revealing role"
		result.copy(character = "").displayCredit shouldBe "Director"
		result.copy(libraryArtwork = true, libraryPosterUrl = null).displayCredit shouldBe null
		result.copy(character = null, libraryArtwork = true).displayCredit shouldBe null
	}
})
