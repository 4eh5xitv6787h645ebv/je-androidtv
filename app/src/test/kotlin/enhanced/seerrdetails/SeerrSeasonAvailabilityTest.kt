package org.jellyfin.androidtv.enhanced.seerrdetails

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.jellyfin.androidtv.enhanced.seerr.SeerrDetails
import org.jellyfin.androidtv.enhanced.seerr.SeerrMediaInfo
import org.jellyfin.androidtv.enhanced.seerr.SeerrMediaStatus
import org.jellyfin.androidtv.enhanced.seerr.SeerrSeason
import org.jellyfin.androidtv.enhanced.seerr.SeerrSeasonStatus

class SeerrSeasonAvailabilityTest : FunSpec({
	fun show(
		mediaInfo: SeerrMediaInfo?,
		seasons: List<Int> = listOf(0, 1, 2, 3),
	) = SeerrDetails(
		id = 1,
		name = "Show",
		seasons = seasons.map { SeerrSeason(seasonNumber = it, episodeCount = 8) },
		mediaInfo = mediaInfo,
	)

	fun requestable(details: SeerrDetails, requests: List<SeerrMediaRequest> = emptyList(), library: Set<Int>? = null, specials: Boolean = false) =
		SeerrSeasonAvailability.requestableSeasons(details, requests, library, specials).map { it.seasonNumber }

	test("a show Seerr has never seen can request every regular season") {
		requestable(show(mediaInfo = null)) shouldContainExactly listOf(1, 2, 3)
		requestable(show(mediaInfo = null), specials = true) shouldContainExactly listOf(0, 1, 2, 3)
	}

	test("partially available show with unknown later seasons (live Breaking Bad shape)") {
		val details = show(
			SeerrMediaInfo(
				id = 639,
				status = SeerrMediaStatus.PARTIALLY_AVAILABLE,
				jellyfinMediaId = "6adf7bc8",
				seasons = listOf(
					SeerrSeasonStatus(1, SeerrMediaStatus.PARTIALLY_AVAILABLE),
					SeerrSeasonStatus(2, SeerrMediaStatus.UNKNOWN),
					SeerrSeasonStatus(3, SeerrMediaStatus.UNKNOWN),
				),
			)
		)
		requestable(details, library = setOf(1)) shouldContainExactly listOf(2, 3)
		requestable(details, library = null) shouldContainExactly listOf(2, 3)
	}

	test("seasons held by an open request aren't offered even when mediaInfo.seasons is empty (live Snowpiercer shape)") {
		val details = show(SeerrMediaInfo(id = 628, status = SeerrMediaStatus.PROCESSING))
		val approved = SeerrMediaRequest(
			id = 185,
			status = SeerrRequestStatus.APPROVED,
			seasons = listOf(SeerrRequestSeason(1, SeerrRequestStatus.APPROVED), SeerrRequestSeason(2, SeerrRequestStatus.APPROVED)),
		)
		requestable(details, listOf(approved)) shouldContainExactly listOf(3)
		SeerrSeasonAvailability.seasonStatus(details, listOf(approved), null, 1) shouldBe SeerrMediaStatus.PROCESSING

		val pending = approved.copy(status = SeerrRequestStatus.PENDING)
		SeerrSeasonAvailability.seasonStatus(details, listOf(pending), null, 2) shouldBe SeerrMediaStatus.PENDING
	}

	test("declined, completed and 4K requests don't hold seasons") {
		val details = show(SeerrMediaInfo(id = 1, status = SeerrMediaStatus.UNKNOWN))
		val seasons = listOf(SeerrRequestSeason(1))
		val requests = listOf(
			SeerrMediaRequest(id = 1, status = SeerrRequestStatus.DECLINED, seasons = seasons),
			SeerrMediaRequest(id = 2, status = SeerrRequestStatus.COMPLETED, seasons = seasons),
			SeerrMediaRequest(id = 3, status = SeerrRequestStatus.APPROVED, is4k = true, seasons = seasons),
		)
		requestable(details, requests) shouldContainExactly listOf(1, 2, 3)
	}

	test("an available season missing from the library can be requested again") {
		val details = show(
			SeerrMediaInfo(
				id = 1,
				status = SeerrMediaStatus.AVAILABLE,
				jellyfinMediaId = "abc",
				seasons = listOf(1, 2, 3).map { SeerrSeasonStatus(it, SeerrMediaStatus.AVAILABLE) },
			)
		)
		requestable(details, library = setOf(1, 3)) shouldContainExactly listOf(2)
		// Without the library's seasons Seerr's word stands
		requestable(details, library = null) shouldBe emptyList()
	}

	test("a season already in the library isn't offered even if Seerr hasn't synced it") {
		val details = show(SeerrMediaInfo(id = 1, status = SeerrMediaStatus.UNKNOWN, jellyfinMediaId = "abc"))
		requestable(details, library = setOf(1, 2)) shouldContainExactly listOf(3)
	}

	test("seasons without episodes are never offered") {
		val details = SeerrDetails(
			id = 1,
			seasons = listOf(SeerrSeason(seasonNumber = 1, episodeCount = 10), SeerrSeason(seasonNumber = 2, episodeCount = 0)),
		)
		requestable(details) shouldContainExactly listOf(1)
	}
})
