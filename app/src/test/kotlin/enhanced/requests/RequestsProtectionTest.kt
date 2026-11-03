package org.jellyfin.androidtv.enhanced.requests

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import org.jellyfin.androidtv.enhanced.seerr.SeerrResult

class RequestsProtectionTest : FunSpec({
	test("neutralized rows keep their keys and withhold artwork, years and episode titles") {
		val card = RequestsRepository.RequestCard(ArrRequest(requestId = 7, title = "Silo", year = 2023, posterUrl = "tmdb.jpg"), "tmdb.jpg", protectedTitle = false)
		val request = RequestsNeutral.request(card)
		request.posterUrl shouldBe null
		request.protectedTitle shouldBe true
		request.request shouldBe card.request

		val result = RequestsNeutral.watchlist(SeerrResult(id = 603, mediaType = "movie", title = "The Matrix", posterPath = "/m.jpg"))
		result.libraryArtwork shouldBe true
		result.libraryPosterUrl shouldBe null
		result.dedupeKey shouldBe "movie-603"

		val issue = RequestsNeutral.issue(RequestsRepository.IssueCard(SeerrIssue(issueId = 3), "Silo", "2023", "p.jpg", "tv"))
		issue.posterUrl shouldBe null
		issue.year shouldBe null
		issue.title shouldBe "Silo"

		val item = HistoryItem(source = "Sonarr", title = "Silo", subtitle = "S02E01 - The Engineer", date = "2026-11-01T10:00:00Z", posterUrl = "p.jpg")
		val history = RequestsNeutral.history(item)
		history.posterUrl shouldBe null
		history.subtitle shouldBe "S02E01"
		RequestsNeutral.historyKey(history) shouldBe RequestsNeutral.historyKey(item)
	}

	test("neutralized queue rows lose posters and episode titles, season packs included") {
		val episodes = (1..3).map { QueueItem(source = "Sonarr", title = "Silo", subtitle = "S02E0$it - Title $it", seasonNumber = 2, episodeNumber = it, posterUrl = "p.jpg") }
		val queue = RequestsRepository.QueueResult(groupQueue(episodes) + QueueEntry.Single(QueueItem(source = "Radarr", title = "Dune", posterUrl = "d.jpg")), emptyList())
		val neutral = RequestsNeutral.queue(queue)
		neutral.entries.size shouldBe 2
		val pack = neutral.entries[0] as QueueEntry.SeasonPack
		pack.episodes.map { it.subtitle } shouldContainExactly listOf("S02E01", "S02E02", "S02E03")
		pack.episodes.all { it.posterUrl == null } shouldBe true
		neutral.entries[1].item.posterUrl shouldBe null
	}

	test("a neutralized list keeps its rows until revalidated, and a failed revalidation drops them") {
		val scope = CoroutineScope(Dispatchers.Unconfined)
		var fail = false
		val loader = PagedLoader(scope, { it.message ?: "error" }, key = { it.substringBefore(':') }) { page ->
			if (fail) throw IllegalStateException("offline")
			PagedLoader.Page(listOf("a:poster", "b:poster"), totalPages = 1)
		}
		loader.ensureStarted()
		loader.neutralize { it.substringBefore(':') + ":neutral" }
		loader.state.value.items shouldContainExactly listOf("a:neutral", "b:neutral")
		loader.stale shouldBe true

		fail = true
		loader.show()
		loader.state.value.items shouldBe emptyList()
		loader.state.value.error shouldBe "offline"

		// Retry starts over
		fail = false
		loader.reload()
		loader.state.value.items shouldContainExactly listOf("a:poster", "b:poster")
		loader.state.value.error shouldBe null
		scope.cancel()
	}

	test("without a protection change a failed refresh keeps the rows") {
		val scope = CoroutineScope(Dispatchers.Unconfined)
		var fail = false
		val loader = PagedLoader(scope, { it.message ?: "error" }, key = { it }) {
			if (fail) throw IllegalStateException("offline")
			PagedLoader.Page(listOf("a", "b"), totalPages = 1)
		}
		loader.ensureStarted()
		fail = true
		loader.invalidate()
		loader.show()
		loader.state.value.items shouldContainExactly listOf("a", "b")
		loader.state.value.error shouldBe "offline"

		// A successful revalidation after a neutralization clears the fail-closed mode
		fail = false
		loader.neutralize { "$it!" }
		loader.show()
		loader.state.value.items shouldContainExactly listOf("a", "b")
		fail = true
		loader.refresh()
		loader.state.value.items shouldContainExactly listOf("a", "b")
		scope.cancel()
	}

	test("neutralizing drops a load in flight, which may have read the list before the change") {
		val scope = CoroutineScope(Dispatchers.Unconfined)
		val gate = CompletableDeferred<Unit>()
		var gated = false
		var version = 1
		val loader = PagedLoader(scope, { "error" }, key = { it.substringBefore(':') }) {
			if (gated) gate.await()
			PagedLoader.Page(listOf("a:v$version"), totalPages = 1)
		}
		loader.ensureStarted()
		gated = true
		loader.refresh()
		loader.neutralize { it.substringBefore(':') + ":neutral" }
		gate.complete(Unit)
		loader.state.value.items shouldContainExactly listOf("a:neutral")
		loader.state.value.loading shouldBe false

		gated = false
		version = 2
		loader.show()
		loader.state.value.items shouldContainExactly listOf("a:v2")
		scope.cancel()
	}

	test("neutralizing a list with nothing shown yet starts it over when it is shown") {
		val scope = CoroutineScope(Dispatchers.Unconfined)
		val gate = CompletableDeferred<Unit>()
		var gated = true
		val loader = PagedLoader(scope, { "error" }, key = { it }) {
			if (gated) gate.await()
			PagedLoader.Page(listOf("a"), totalPages = 1)
		}
		loader.ensureStarted()
		loader.neutralize { it }
		loader.state.value.started shouldBe false
		gated = false
		loader.show()
		loader.state.value.items shouldContainExactly listOf("a")
		scope.cancel()
	}
})
