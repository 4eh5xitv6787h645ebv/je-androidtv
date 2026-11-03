package org.jellyfin.androidtv.enhanced.requests

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.jellyfin.androidtv.enhanced.api.EnhancedApi
import org.jellyfin.androidtv.enhanced.data.EnhancedPublicConfig
import org.jellyfin.androidtv.enhanced.data.EnhancedRepository
import org.jellyfin.androidtv.enhanced.data.EnhancedState
import org.jellyfin.androidtv.enhanced.data.HiddenContentRepository
import org.jellyfin.androidtv.enhanced.data.SpoilerGuardRepository
import org.jellyfin.androidtv.enhanced.data.SpoilerGuardState
import org.jellyfin.androidtv.enhanced.seerr.SeerrRepository
import org.jellyfin.androidtv.enhanced.seerr.SeerrResult

class RequestsRepositoryTest : FunSpec({
	val json = EnhancedApi.json

	fun state(
		config: EnhancedPublicConfig = EnhancedPublicConfig(),
		isAdmin: Boolean = false,
	) = EnhancedState(available = true, config = config, isAdmin = isAdmin)

	class Fixture(state: EnhancedState) {
		val api = mockk<EnhancedApi>()
		val enhanced = mockk<EnhancedRepository> {
			every { current } returns state
			coEvery { ensureLoaded(any()) } returns state
		}
		val seerr = mockk<SeerrRepository> {
			// Mimic the shared artwork path: library titles get the server's poster
			coEvery { refreshLibraryArtwork(any()) } answers {
				firstArg<List<SeerrResult>>().map {
					if (it.mediaInfo?.jellyfinMediaId != null) it.copy(libraryArtwork = true, libraryPosterUrl = "server-poster") else it
				}
			}
		}
		val hidden = mockk<HiddenContentRepository>()
		val spoilers = mockk<SpoilerGuardRepository>()
		val repository = RequestsRepository(api, enhanced, seerr, hidden, spoilers)
	}

	test("requests are paged with the filter and own-requests flag, hidden titles dropped, library posters protected") {
		val fixture = Fixture(state(EnhancedPublicConfig(hiddenContentEnabled = true, spoilerBlurEnabled = true)))
		val query = slot<Map<String, Any?>>()
		coEvery { fixture.api.get("arr/requests", capture(query)) } returns json.parseToJsonElement(
			"""{"requests":[
				{"id":1,"type":"movie","title":"Hidden","tmdbId":272,"posterUrl":"tmdb-1","requestStatus":1},
				{"id":2,"type":"movie","title":"In library","tmdbId":603,"posterUrl":"tmdb-2","jellyfinMediaId":"aaaa","requestStatus":5},
				{"id":3,"type":"tv","title":"New","tmdbId":1399,"posterUrl":"tmdb-3","requestStatus":2}
			],"totalPages":4,"totalResults":61,"canApproveRequests":false}"""
		)
		coEvery { fixture.hidden.get() } returns HiddenContentRepository.Snapshot(
			json.parseToJsonElement("""{"Settings":{"Enabled":true},"Items":{"x":{"ItemId":"zzz","TmdbId":"272"}}}""").jsonObject
		)

		val result = fixture.repository.requests(RequestFilter.PENDING, userOnly = true, page = 3, pageSize = 20)

		query.captured["take"] shouldBe 20
		query.captured["skip"] shouldBe 40
		query.captured["filter"] shouldBe "pending"
		query.captured["userOnly"] shouldBe true
		result.totalPages shouldBe 4
		result.canApproveRequests shouldBe false
		result.cards.map { it.request.requestId } shouldContainExactly listOf(2, 3)
		result.cards[0].posterUrl shouldBe "server-poster"
		result.cards[0].protectedTitle shouldBe true
		result.cards[1].posterUrl shouldBe "tmdb-3"
		result.cards[1].protectedTitle shouldBe false
	}

	test("the requests surface filter can be turned off by the user") {
		val fixture = Fixture(state(EnhancedPublicConfig(hiddenContentEnabled = true)))
		coEvery { fixture.api.get("arr/requests", any()) } returns json.parseToJsonElement(
			"""{"requests":[{"id":1,"tmdbId":272}],"totalPages":1}"""
		)
		coEvery { fixture.hidden.get() } returns HiddenContentRepository.Snapshot(
			json.parseToJsonElement("""{"Settings":{"Enabled":true,"FilterRequests":false},"Items":{"x":{"ItemId":"zzz","TmdbId":"272"}}}""").jsonObject
		)
		fixture.repository.requests(RequestFilter.ALL, userOnly = false, page = 1, pageSize = 20).cards.size shouldBe 1
	}

	test("a failing Hidden Content lookup doesn't blank the requests") {
		val fixture = Fixture(state(EnhancedPublicConfig(hiddenContentEnabled = true)))
		coEvery { fixture.api.get("arr/requests", any()) } returns json.parseToJsonElement("""{"requests":[{"id":1,"tmdbId":272}],"totalPages":1}""")
		coEvery { fixture.hidden.get() } throws EnhancedApi.EnhancedApiException(500, "boom")
		fixture.repository.requests(RequestFilter.ALL, userOnly = false, page = 1, pageSize = 20).cards.size shouldBe 1
	}

	test("approve and decline post to the request id route without a retry") {
		val fixture = Fixture(state())
		coEvery { fixture.api.post(any(), any(), any()) } returns JsonObject(emptyMap())
		fixture.repository.approve(187)
		fixture.repository.decline(188)
		coVerify(exactly = 1) { fixture.api.post("arr/requests/187/approve", null, any()) }
		coVerify(exactly = 1) { fixture.api.post("arr/requests/188/decline", null, any()) }
	}

	test("queue rows keep instance errors and lose episode titles when the protection state can't be loaded") {
		val fixture = Fixture(state(EnhancedPublicConfig(spoilerBlurEnabled = true)))
		coEvery { fixture.api.get("arr/queue", any()) } returns json.parseToJsonElement(
			"""{"items":[{"id":"1","source":"Sonarr","title":"Show","subtitle":"S01E01 - Pilot","seasonNumber":1,"episodeNumber":1,"progress":5}],
			"errors":[{"instanceName":"Radarr","source":"Radarr","reason":"timeout"}]}"""
		)
		coEvery { fixture.spoilers.getState() } throws EnhancedApi.EnhancedApiException(503, "down")
		val result = fixture.repository.queue()
		result.entries.single().item.subtitle shouldBe "S01E01"
		result.errors.single().reason shouldBe "timeout"
	}

	test("an unknown configuration fails closed: episode titles are withheld when the lists can't be read") {
		val fixture = Fixture(EnhancedState(configUnavailable = true))
		coEvery { fixture.api.get("arr/queue", any()) } returns json.parseToJsonElement(
			"""{"items":[{"id":"1","source":"Sonarr","title":"Show","subtitle":"S01E01 - Pilot","seasonNumber":1,"episodeNumber":1}],"errors":[]}"""
		)
		coEvery { fixture.spoilers.getState() } throws EnhancedApi.EnhancedApiException(0, "offline")
		fixture.repository.queue().entries.single().item.subtitle shouldBe "S01E01"
	}

	test("history is untouched when Spoiler Guard is off and passes visible:false through") {
		val fixture = Fixture(state())
		coEvery { fixture.api.get("arr/history", mapOf("take" to 30, "skip" to 30)) } returns json.parseToJsonElement(
			"""{"items":[{"source":"Sonarr","title":"Show","subtitle":"S01E01 - Pilot","eventType":"imported"}],"totalPages":2,"visible":true}"""
		)
		fixture.repository.history(page = 2, pageSize = 30).items.single().subtitle shouldBe "S01E01 - Pilot"

		coEvery { fixture.api.get("arr/history", mapOf("take" to 30, "skip" to 0)) } returns json.parseToJsonElement(
			"""{"items":[],"totalPages":0,"visible":false}"""
		)
		fixture.repository.history(page = 1, pageSize = 30).visible shouldBe false
	}

	test("Spoiler Guard state is only fetched when the feature is on") {
		val fixture = Fixture(state(EnhancedPublicConfig(spoilerBlurEnabled = true)))
		coEvery { fixture.api.get("arr/queue", any()) } returns json.parseToJsonElement("""{"items":[],"errors":[]}""")
		coEvery { fixture.spoilers.getState() } returns SpoilerGuardState()
		fixture.repository.queue()
		coVerify(exactly = 1) { fixture.spoilers.getState() }

		val off = Fixture(state())
		coEvery { off.api.get("arr/queue", any()) } returns json.parseToJsonElement("""{"items":[],"errors":[]}""")
		off.repository.queue()
		coVerify(exactly = 0) { off.spoilers.getState() }
	}

	test("plugin paths are split for authenticated URLs; foreign URLs are refused") {
		RequestsRepository.splitPluginPath("/JellyfinEnhanced/proxy/avatar?path=%2Favatarproxy%2Fc799%3Fv%3Dnull") shouldBe
			("/JellyfinEnhanced/proxy/avatar" to mapOf("path" to "/avatarproxy/c799?v=null"))
		RequestsRepository.splitPluginPath("/JellyfinEnhanced/proxy/avatar") shouldBe ("/JellyfinEnhanced/proxy/avatar" to emptyMap())
		RequestsRepository.splitPluginPath("https://evil.example/JellyfinEnhanced/x") shouldBe null
		RequestsRepository.splitPluginPath("/Users/Me") shouldBe null
		RequestsRepository.splitPluginPath("/JellyfinEnhanced/../Users") shouldBe null
		RequestsRepository.splitPluginPath(null) shouldBe null
	}

	test("server messages come from message or a textual error") {
		RequestsRepository.serverMessage(EnhancedApi.EnhancedApiException(404, "x", serverMessage = "Not linked")) shouldBe "Not linked"
		val textual = EnhancedApi.EnhancedApiException(400, "x", body = json.parseToJsonElement("""{"error":"Jellyseerr watchlist sync is not enabled"}"""))
		RequestsRepository.serverMessage(textual) shouldBe "Jellyseerr watchlist sync is not enabled"
		val flag = EnhancedApi.EnhancedApiException(503, "x", body = json.parseToJsonElement("""{"error":true}"""))
		RequestsRepository.serverMessage(flag) shouldBe null
		RequestsRepository.serverMessage(IllegalStateException("x")) shouldBe null
	}

	test("tabs follow the server's downloads settings") {
		RequestsTab.available(state()) shouldContainExactly listOf(RequestsTab.REQUESTS, RequestsTab.WATCHLIST)
		val all = EnhancedPublicConfig(showDownloadsInRequests = true, downloadsShowHistory = true, downloadsPageShowIssues = true)
		RequestsTab.available(state(all)) shouldContainExactly RequestsTab.entries
		val adminHistory = all.copy(downloadsHistoryAdminOnly = true)
		RequestsTab.available(state(adminHistory)).contains(RequestsTab.HISTORY) shouldBe false
		RequestsTab.available(state(adminHistory, isAdmin = true)).contains(RequestsTab.HISTORY) shouldBe true

		RequestsTab.canSyncWatchlist(state(EnhancedPublicConfig(syncJellyseerrWatchlist = true))) shouldBe false
		RequestsTab.canSyncWatchlist(state(EnhancedPublicConfig(syncJellyseerrWatchlist = true), isAdmin = true)) shouldBe true
		RequestsTab.canSyncWatchlist(state(isAdmin = true)) shouldBe false

		RequestsTab.pollIntervalSeconds(state(EnhancedPublicConfig(downloadsPollIntervalSeconds = 30))) shouldBe null
		RequestsTab.pollIntervalSeconds(state(EnhancedPublicConfig(downloadsPagePollingEnabled = true, downloadsPollIntervalSeconds = 30))) shouldBe 30
		RequestsTab.pollIntervalSeconds(state(EnhancedPublicConfig(downloadsPagePollingEnabled = true, downloadsPollIntervalSeconds = 0))) shouldBe 5
	}

	test("the pager chains past filtered-out pages, stops at the end and keeps rows on failure") {
		val scope = CoroutineScope(Dispatchers.Unconfined)
		val pages = mapOf(1 to listOf("a"), 2 to emptyList(), 3 to listOf("b"), 4 to listOf("c"))
		var failPage4 = true
		val requested = mutableListOf<Int>()
		val loader = PagedLoader(scope, { it.message ?: "error" }, key = { it }) { page ->
			requested += page
			if (page == 4 && failPage4) throw IllegalStateException("page 4 failed")
			PagedLoader.Page(pages.getValue(page), totalPages = 4)
		}

		loader.ensureStarted()
		loader.state.value.items shouldContainExactly listOf("a")
		loader.loadMore()
		// Page 2 had nothing to show, so page 3 followed straight away
		loader.state.value.items shouldContainExactly listOf("a", "b")
		loader.loadMore()
		loader.state.value.error shouldBe "page 4 failed"
		loader.state.value.items shouldContainExactly listOf("a", "b")
		failPage4 = false
		loader.loadMore()
		loader.state.value.items shouldContainExactly listOf("a", "b", "c")
		loader.state.value.endReached shouldBe true
		loader.loadMore()
		requested shouldContainExactly listOf(1, 2, 3, 4, 4)

		loader.reload()
		loader.state.value.items shouldContainExactly listOf("a")
		scope.cancel()
	}

	test("rows already seen on an earlier page are skipped when the list shifts") {
		val scope = CoroutineScope(Dispatchers.Unconfined)
		val pages = mapOf(1 to listOf("a", "b"), 2 to listOf("b", "c"))
		val loader = PagedLoader(scope, { "error" }, key = { it }) { page -> PagedLoader.Page(pages.getValue(page), totalPages = 2) }
		loader.ensureStarted()
		loader.loadMore()
		loader.state.value.items shouldContainExactly listOf("a", "b", "c")
		scope.cancel()
	}

	test("a reload drops the page still loading for the previous filter") {
		val scope = CoroutineScope(Dispatchers.Unconfined)
		val gate = CompletableDeferred<Unit>()
		var filter = "old"
		val loader = PagedLoader(scope, { "error" }, key = { it }) { _ ->
			val current = filter
			if (current == "old") gate.await()
			PagedLoader.Page(listOf(current), totalPages = 1)
		}
		loader.ensureStarted()
		filter = "new"
		loader.reload()
		gate.complete(Unit)
		loader.state.value.items shouldContainExactly listOf("new")
		scope.cancel()
	}
})
