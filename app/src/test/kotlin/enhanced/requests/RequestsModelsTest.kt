package org.jellyfin.androidtv.enhanced.requests

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.decodeFromString
import org.jellyfin.androidtv.enhanced.api.EnhancedApi
import org.jellyfin.androidtv.enhanced.data.SpoilerGuardState
import java.time.LocalDate

class RequestsModelsTest : FunSpec({
	val json = EnhancedApi.json
	val today = LocalDate.of(2026, 11, 3)

	// Trimmed from the live JE 12.13 server
	val requestsSample = """
		{"requests":[
		 {"id":187,"type":"movie","title":"Batman Begins","year":2005,"posterUrl":"https://image.tmdb.org/t/p/w300/a.jpg","tmdbId":272,
		  "mediaStatus":"Pending","requestStatus":1,"requestedBy":"real-viewer",
		  "requestedByAvatar":"/JellyfinEnhanced/proxy/avatar?path=%2Favatarproxy%2Fc799","createdAt":"2026-10-22T13:37:54.0000000Z",
		  "digitalReleaseDate":"2015-01-02","theatricalReleaseDate":"2005-06-10","isComingSoon":false,"isOrphaned":false},
		 {"id":185,"type":"tv","title":"Snowpiercer","year":2020,"tmdbId":79680,"tvdbId":364928,"mediaStatus":"Approved",
		  "requestStatus":2,"requestedBy":"real-admin","createdAt":"2026-10-22T03:58:14.0000000Z","initialAirDate":"2020-05-17",
		  "isComingSoon":false,"isOrphaned":true,"someNewField":[1,2]}
		],"totalPages":7,"totalResults":19,"canApproveRequests":true}
	""".trimIndent()

	test("arr/requests decodes with the request id kept apart from the TMDB id") {
		val page = json.decodeFromString<ArrRequestsPage>(requestsSample)
		page.totalPages shouldBe 7
		page.canApproveRequests shouldBe true
		val first = page.requests[0]
		first.requestId shouldBe 187
		first.tmdbId shouldBe 272
		first.mediaStatus shouldBe "Pending"
		first.requestStatus shouldBe SeerrRequestStatus.PENDING
		first.isPendingApproval shouldBe true
		page.requests[1].isPendingApproval shouldBe false
		page.requests[1].tvdbId shouldBe 364928
	}

	test("approve/decline follows the request status, not the media status") {
		// A pending request for a new season of a show that is already partly in the library
		val request = ArrRequest(requestId = 9, type = "tv", mediaStatus = "Partially Available", requestStatus = SeerrRequestStatus.PENDING)
		request.isPendingApproval shouldBe true
		RequestChip.of(request, today) shouldBe RequestChip.PARTIALLY_AVAILABLE
		request.copy(requestId = null).isPendingApproval shouldBe false
	}

	test("request chips follow the web's resolveRequestStatus") {
		val page = json.decodeFromString<ArrRequestsPage>(requestsSample)
		RequestChip.of(page.requests[0], today) shouldBe RequestChip.PENDING
		RequestChip.of(page.requests[1], today) shouldBe RequestChip.ORPHANED
		RequestChip.of(ArrRequest(mediaStatus = "Approved"), today) shouldBe RequestChip.REQUESTED
		RequestChip.of(ArrRequest(mediaStatus = "Processing"), today) shouldBe RequestChip.PROCESSING
		RequestChip.of(ArrRequest(mediaStatus = "Available"), today) shouldBe RequestChip.AVAILABLE
		RequestChip.of(ArrRequest(mediaStatus = "Declined"), today) shouldBe RequestChip.DECLINED
		RequestChip.of(ArrRequest(mediaStatus = "Something new"), today) shouldBe RequestChip.UNKNOWN
		RequestChip.of(ArrRequest(mediaStatus = null), today) shouldBe RequestChip.UNKNOWN
	}

	test("coming soon needs a future date and an approved or processing request") {
		val movie = ArrRequest(type = "movie", mediaStatus = "Approved", digitalReleaseDate = "2026-12-01")
		RequestChip.of(movie, today) shouldBe RequestChip.COMING_SOON
		RequestChip.of(movie.copy(mediaStatus = "Pending"), today) shouldBe RequestChip.PENDING
		RequestChip.of(movie.copy(mediaStatus = "Partially Available"), today) shouldBe RequestChip.PARTIALLY_AVAILABLE
		val show = ArrRequest(type = "tv", mediaStatus = "Partially Available", nextAirDate = "2026-11-10")
		RequestChip.of(show, today) shouldBe RequestChip.COMING_SOON
	}

	test("release labels use the next episode for shows and the digital release first for movies") {
		releaseLabel(ArrRequest(type = "tv", nextAirDate = "2026-11-04"), today) shouldBe ReleaseLabel(ReleaseKind.NEXT_EPISODE, LocalDate.of(2026, 11, 4), 1)
		releaseLabel(ArrRequest(type = "tv", nextAirDate = "2026-11-02"), today) shouldBe null
		releaseLabel(ArrRequest(type = "movie", theatricalReleaseDate = "2026-11-03T00:00:00Z"), today)?.daysAway shouldBe 0
		// Like the web: a known (past) digital release wins, even with a future cinema date
		releaseLabel(ArrRequest(type = "movie", digitalReleaseDate = "2026-01-01", theatricalReleaseDate = "2027-01-01"), today) shouldBe null
		releaseLabel(ArrRequest(type = "movie", digitalReleaseDate = "garbage"), today) shouldBe null
	}

	test("arr/queue decodes string ids and per-instance errors") {
		val sample = """
			{"items":[{"id":"1953008960","source":"Sonarr","instanceName":"Sonarr","title":"Angelina Ballerina",
			 "subtitle":"S02E01 - The Proposal","seasonNumber":2,"episodeNumber":1,"status":"warning","progress":100,
			 "totalSize":9598166403,"sizeRemaining":0,"timeRemaining":"00:00:00","posterUrl":"https://x/p.jpg","tmdbId":28624},
			 {"id":"7","source":"Radarr","instanceName":"Radarr 4K","title":"Movie","status":"downloading","progress":45.5,
			 "totalSize":1000,"sizeRemaining":545}],
			 "errors":[{"instanceName":"Sonarr Anime","source":"Sonarr","reason":"401 Unauthorized"}]}
		""".trimIndent()
		val queue = json.decodeFromString<QueueResponse>(sample)
		queue.items[0].queueId shouldBe "1953008960"
		queue.items[0].totalSize shouldBe 9598166403L
		queue.items[0].isSonarr shouldBe true
		queue.items[1].progress shouldBe 45.5
		queue.items[1].timeRemaining shouldBe null
		queue.errors.single().reason shouldBe "401 Unauthorized"
	}

	test("three or more episodes downloading together collapse into a season pack") {
		fun episode(n: Int, progress: Double = 50.0, size: Long = 900) = QueueItem(
			queueId = "e$n", source = "Sonarr", instanceName = "Sonarr", title = "Show", seasonNumber = 1,
			episodeNumber = n, progress = progress, totalSize = size, sizeRemaining = size / 2,
		)
		val movie = QueueItem(queueId = "m", source = "Radarr", title = "Movie")
		val entries = groupQueue(listOf(episode(3), movie, episode(1), episode(2), episode(5, progress = 10.0)))
		entries.size shouldBe 3
		val pack = entries[0].shouldBeInstanceOf<QueueEntry.SeasonPack>()
		pack.episodes.map { it.episodeNumber } shouldContainExactly listOf(1, 2, 3)
		pack.firstEpisode shouldBe 1
		pack.lastEpisode shouldBe 3
		// Same size on every episode: one file, counted once
		pack.totalSize shouldBe 900
		pack.sizeRemaining shouldBe 450
		entries[1].item shouldBe movie
		entries[2].item.episodeNumber shouldBe 5

		val mixed = groupQueue(listOf(episode(1, size = 100), episode(2, size = 200), episode(3, size = 300)))
		mixed.single().totalSize shouldBe 600

		groupQueue(listOf(episode(1), episode(2))).all { it is QueueEntry.Single } shouldBe true
	}

	test("arr/history keeps visible:false apart from an empty history") {
		val hidden = json.decodeFromString<HistoryPage>("""{"items":[],"totalPages":0,"visible":false}""")
		hidden.visible shouldBe false
		json.decodeFromString<HistoryPage>("""{"items":[],"totalPages":0}""").visible shouldBe true

		val page = json.decodeFromString<HistoryPage>(
			"""{"items":[{"source":"Sonarr","instanceName":"Sonarr","title":"3 Body Problem","subtitle":"S01 · E01-E08","eventType":"imported",
			"date":"2026-11-02T15:02:39.0000000Z","tmdbId":108545,"episodeCount":8,"importedCount":8,"failedCount":0},
			{"source":"Radarr","title":"X","eventType":"importFailed"},{"source":"Sonarr","title":"Y","eventType":"partial"}],"totalPages":70,"visible":true}"""
		)
		page.items.map { it.outcome } shouldContainExactly listOf(HistoryOutcome.IMPORTED, HistoryOutcome.FAILED, HistoryOutcome.PARTIAL)
		page.items[0].episodeCount shouldBe 8
	}

	test("Seerr issues decode with internal media ids kept apart from TMDB ids") {
		val sample = """
			{"pageInfo":{"pages":3,"pageSize":20,"results":41,"page":1},"results":[
			 {"id":12,"issueType":3,"status":1,"problemSeason":2,"problemEpisode":5,"createdAt":"2026-10-30T10:00:00.000Z",
			  "media":{"id":44,"mediaType":"tv","tmdbId":1399,"status":5,"jellyfinMediaId":"abc"},
			  "createdBy":{"id":1,"displayName":"Jane","jellyfinUsername":"jane"},
			  "comments":[{"id":1,"message":"Subtitles are out of sync"},{"id":2,"message":"Still broken"}]},
			 {"id":13,"issueType":1,"status":2,"problemSeason":0,"problemEpisode":0,"media":{"id":45,"mediaType":"movie","tmdbId":603},"comments":[]}
			]}
		""".trimIndent()
		val page = json.decodeFromString<SeerrIssuePage>(sample)
		page.pageInfo.pages shouldBe 3
		val first = page.results[0]
		first.issueId shouldBe 12
		first.media?.seerrMediaId shouldBe 44
		first.media?.tmdbId shouldBe 1399
		first.issueType shouldBe SeerrIssueType.SUBTITLES
		first.message shouldBe "Subtitles are out of sync"
		first.episodeLabel shouldBe "S2 E5"
		first.createdBy?.name shouldBe "jane"
		first.isResolved shouldBe false
		page.results[1].isResolved shouldBe true
		page.results[1].episodeLabel shouldBe null
		page.results[1].message shouldBe null
	}

	test("watchlist sync result decodes") {
		val result = json.decodeFromString<WatchlistSyncResult>("""{"success":true,"itemsProcessed":12,"itemsAdded":3,"errors":null}""")
		result.itemsProcessed shouldBe 12
		result.itemsAdded shouldBe 3
		result.errors shouldBe null
	}

	test("Spoiler Guard policy for *arr rows is fail-closed") {
		val sonarr = QueueItem(source = "Sonarr", title = "Show", subtitle = "S02E01 - The Proposal", posterUrl = "p", tmdbId = 1)
		val radarr = QueueItem(source = "Radarr", title = "Movie", subtitle = "2024", posterUrl = "m", tmdbId = 603)

		ArrSpoilerPolicy.of(enabled = false, state = null).apply(sonarr) shouldBe sonarr

		// Couldn't load the protection state: withhold everything that could be protected
		val failed = ArrSpoilerPolicy.of(enabled = true, state = null)
		failed.apply(sonarr).subtitle shouldBe "S02E01"
		failed.apply(sonarr).posterUrl shouldBe "p"
		failed.apply(radarr).posterUrl shouldBe null
		failed.apply(radarr).subtitle shouldBe "2024"

		val nothingProtected = ArrSpoilerPolicy.of(enabled = true, state = SpoilerGuardState())
		nothingProtected.apply(sonarr) shouldBe sonarr
		nothingProtected.apply(radarr) shouldBe radarr

		val seriesProtected = ArrSpoilerPolicy.of(enabled = true, state = SpoilerGuardState(series = mapOf("abc" to JsonPrimitive(true))))
		seriesProtected.apply(sonarr).subtitle shouldBe "S02E01"
		seriesProtected.apply(radarr) shouldBe radarr

		val collectionProtected = ArrSpoilerPolicy.of(enabled = true, state = SpoilerGuardState(collections = mapOf("c" to JsonPrimitive(true))))
		collectionProtected.apply(radarr).posterUrl shouldBe null

		val pending = ArrSpoilerPolicy.of(enabled = true, state = SpoilerGuardState(pendingTmdb = mapOf("movie:603" to JsonPrimitive(true), "tv:1" to JsonPrimitive(true))))
		pending.apply(radarr).posterUrl shouldBe null
		pending.apply(radarr.copy(tmdbId = 604)).posterUrl shouldBe "m"
		pending.apply(sonarr).subtitle shouldBe "S02E01"

		val history = HistoryItem(source = "Sonarr", subtitle = "S01E02 - Title", tmdbId = 9)
		failed.apply(history).subtitle shouldBe "S01E02"
		failed.apply(history.copy(subtitle = "S01 · E01-E08")).subtitle shouldBe "S01 · E01-E08"
	}

	test("Hidden Content's requests filter matches TMDB ids and dash-less library ids") {
		val filter = HiddenRequestFilter(setOf("272"), setOf("f494bed919557e62d08c44ee252d877a"))
		filter.isHidden(272, null) shouldBe true
		filter.isHidden(273, null) shouldBe false
		filter.isHidden(null, "F494BED9-1955-7E62-D08C-44EE252D877A") shouldBe true
		filter.isHidden(null, null) shouldBe false
		HiddenRequestFilter.NONE.isHidden(272, null) shouldBe false
	}

	test("sizes and times format like the web") {
		formatBytes(0) shouldBe "0 B"
		formatBytes(512) shouldBe "512 B"
		formatBytes(1536) shouldBe "1.5 KB"
		formatBytes(9598166403) shouldBe "8.9 GB"
		formatDownloadStats(1000, 2000) shouldBe "0 B / 1000 B"
		formatDownloadStats(2048, 1024) shouldBe "1 KB / 2 KB"
		formatDownloadStats(0, 0) shouldBe null

		formatTimeRemaining("00:00:00") shouldBe null
		formatTimeRemaining("00:00:42") shouldBe "42s"
		formatTimeRemaining("00:03:05") shouldBe "3m 5s"
		formatTimeRemaining("02:03:05") shouldBe "2h 3m 5s"
		formatTimeRemaining("1.02:03:05") shouldBe "1d 2h 3m"
		formatTimeRemaining("02:03:05.1234567") shouldBe "2h 3m 5s"
		formatTimeRemaining("soon") shouldBe "soon"
		formatTimeRemaining(null) shouldBe null
	}
})
