package org.jellyfin.androidtv.enhanced.seerr

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class SeerrModelsTest : FunSpec({
	test("deleted library copies are requestable separately for standard and 4K") {
		val media = SeerrMediaInfo(status = SeerrMediaStatus.AVAILABLE, status4k = SeerrMediaStatus.AVAILABLE, jellyfinMediaId4k = "4k-copy")
		SeerrMediaStatus.isRequestable(media.effectiveStatus) shouldBe true
		media.effectiveStatus4k shouldBe SeerrMediaStatus.AVAILABLE
		SeerrMediaStatus.isRequestable(media.effectiveStatus4k) shouldBe false
		val standardOnly = media.copy(jellyfinMediaId = "standard-copy", jellyfinMediaId4k = null)
		standardOnly.effectiveStatus shouldBe SeerrMediaStatus.AVAILABLE
		SeerrMediaStatus.isRequestable(standardOnly.effectiveStatus4k) shouldBe true
	}

	test("stale available seasons can be requested again without touching the other resolution") {
		val media = SeerrMediaInfo(
			jellyfinMediaId4k = "4k-copy",
			seasons = listOf(SeerrSeasonStatus(seasonNumber = 1, status = SeerrMediaStatus.AVAILABLE, status4k = SeerrMediaStatus.AVAILABLE)),
		)
		SeerrMediaStatus.isRequestable(media.effectiveSeasonStatus(1, is4k = false)) shouldBe true
		SeerrMediaStatus.isRequestable(media.effectiveSeasonStatus(1, is4k = true)) shouldBe false
		SeerrMediaStatus.isRequestable(media.effectiveSeasonStatus(2, is4k = true)) shouldBe true
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
