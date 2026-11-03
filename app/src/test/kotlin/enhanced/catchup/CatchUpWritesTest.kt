package org.jellyfin.androidtv.enhanced.catchup

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jellyfin.androidtv.enhanced.api.EnhancedApi

private const val USER = "u1"
private const val SERIES = "series"

/**
 * A fake Jellyfin with one show: [seasons] maps season ids to their episodes. [hidden] episodes are left out of
 * listings, like the plugin's Hidden Content filter does. [failOnWrite] makes the Nth write (POST or DELETE) to
 * `PlayedItems` fail; [writeGate] holds every write until it's completed.
 */
private class FakeServer(
	val seasons: Map<String, List<String>>,
	val hidden: Set<String> = emptySet(),
	played: Set<String> = emptySet(),
) {
	val played = played.toMutableSet()
	val writes = mutableListOf<String>()
	var failOnWrite: Int? = null
	var writeGate: CompletableDeferred<Unit>? = null
	var firstWrite = CompletableDeferred<Unit>()

	val api = mockk<EnhancedApi>().also { api ->
		coEvery { api.get(any(), any()) } answers {
			val parentId = secondArg<Map<String, Any?>>()["ParentId"] as String
			val episodes = if (parentId == SERIES) seasons.values.flatten() else seasons[parentId].orEmpty()
			val listed = episodes.filter { it !in hidden && it !in this@FakeServer.played }
			JsonObject(mapOf("Items" to JsonArray(listed.map { JsonObject(mapOf("Id" to JsonPrimitive(it))) })))
		}
		coEvery { api.post(any(), any(), any()) } coAnswers { write(firstArg(), played = true) }
		coEvery { api.delete(any(), any()) } coAnswers { write(firstArg(), played = false) }
	}

	private suspend fun write(path: String, played: Boolean): JsonNull {
		val itemId = path.removePrefix("/Users/$USER/PlayedItems/")
		check(itemId != path) { "unexpected call $path" }
		writes += itemId
		firstWrite.complete(Unit)
		writeGate?.await()
		if (writes.size == failOnWrite) throw IllegalStateException("write ${writes.size} failed")
		if (played) this.played += itemId else this.played -= itemId
		return JsonNull
	}

	fun session() = CatchUpSession(
		api = api,
		serverId = "s1",
		userId = USER,
		dismissStore = mockk(relaxed = true),
		logScope = CoroutineScope(Job()),
	)
}

private val show = CatchUpItem(id = SERIES, name = "Show", type = "Series")

private fun episodes(prefix: String, count: Int) = (1..count).map { "$prefix$it" }

class CatchUpWritesTest : FunSpec({
	test("watched then undo touches only the episodes it marked, never the show") {
		// e3 is hidden and was already watched; the old undo cleared the whole show and lost it
		val server = FakeServer(seasons = mapOf("s1" to episodes("e", 4)), hidden = setOf("e3"), played = setOf("e1", "e3"))
		val session = server.session()

		val applied = session.markWatched(show, SeasonSelection.wholeShow(show))
		applied.markedIds shouldContainExactly listOf("e2", "e4")
		server.played shouldContainExactlyInAnyOrder listOf("e1", "e2", "e3", "e4")

		session.revert(applied)
		server.played shouldContainExactlyInAnyOrder listOf("e1", "e3")
		server.writes.toSet() shouldBe setOf("e2", "e4")
	}

	test("chosen seasons mark only their own unplayed episodes") {
		val server = FakeServer(seasons = mapOf("s1" to episodes("a", 3), "s2" to episodes("b", 3), "s3" to episodes("c", 3)), played = setOf("b2"))
		val action = CatchUpAction.Watched(targetIds = listOf("s1", "s2"), seasonNumbers = listOf("1", "2"))

		val applied = server.session().markWatched(show, action)
		applied.markedIds shouldContainExactlyInAnyOrder listOf("a1", "a2", "a3", "b1", "b3")
		server.played shouldContainExactlyInAnyOrder listOf("a1", "a2", "a3", "b1", "b2", "b3")
	}

	test("a movie marks and unmarks just itself") {
		val server = FakeServer(seasons = emptyMap())
		val movie = CatchUpItem(id = "m1", type = "Movie")
		val session = server.session()
		val applied = session.markWatched(movie, CatchUpAction.Watched(listOf("m1"), seasonNumbers = null))
		applied.markedIds shouldContainExactly listOf("m1")
		server.played shouldBe setOf("m1")
		session.revert(applied)
		server.played shouldBe emptySet()
	}

	test("a failed undo keeps what's left on the stack and a second undo finishes it") {
		val server = FakeServer(seasons = mapOf("s1" to episodes("e", 10)), played = setOf("old"))
		val session = server.session()
		val applied = session.markWatched(show, SeasonSelection.wholeShow(show))
		var deck = CatchUpDeck().after(show, applied)

		server.writes.clear()
		server.failOnWrite = 6
		val partial = shouldThrow<CatchUpPartialUndoException> { session.revert(deck.history.last().action) }
		// Batches of four: the first batch and the rest of the second went through, the third wasn't started
		partial.undone shouldBe 7
		partial.remaining.markedIds shouldContainExactly listOf("e6", "e9", "e10")
		server.played shouldContainExactlyInAnyOrder listOf("old", "e6", "e9", "e10")

		deck = deck.withRemainingUndo(partial.remaining)
		deck.canUndo shouldBe true
		deck.history.last().item shouldBe show

		server.failOnWrite = null
		session.revert(deck.history.last().action)
		server.played shouldBe setOf("old")
	}

	test("an undo that unmarks anything is partial; one that unmarks nothing fails plainly") {
		val server = FakeServer(seasons = mapOf("s1" to episodes("e", 2)))
		val session = server.session()
		val applied = session.markWatched(show, SeasonSelection.wholeShow(show))
		server.writes.clear()
		server.failOnWrite = 1
		// Both episodes are in one batch: the second still goes through
		val partial = shouldThrow<CatchUpPartialUndoException> { session.revert(applied) }
		partial.remaining.markedIds shouldContainExactly listOf("e1")

		val single = FakeServer(seasons = emptyMap())
		val movie = CatchUpItem(id = "m1", type = "Movie")
		val movieSession = single.session()
		val watchedMovie = movieSession.markWatched(movie, CatchUpAction.Watched(listOf("m1"), seasonNumbers = null))
		single.writes.clear()
		single.failOnWrite = 1
		shouldThrow<IllegalStateException> { movieSession.revert(watchedMovie) }
		single.played shouldBe setOf("m1")
	}

	test("a failed watched press rolls back only what it marked") {
		val server = FakeServer(seasons = mapOf("s1" to episodes("e", 6)), played = setOf("e1"))
		server.failOnWrite = 3
		shouldThrow<IllegalStateException> { server.session().markWatched(show, SeasonSelection.wholeShow(show)) }
		server.played shouldBe setOf("e1")
	}

	test("leaving the page mid-write doesn't stop the writes halfway") {
		val server = FakeServer(seasons = mapOf("s1" to episodes("e", 9)))
		val gate = CompletableDeferred<Unit>()
		server.writeGate = gate
		coroutineScope {
			val job = launch { server.session().markWatched(show, SeasonSelection.wholeShow(show)) }
			server.firstWrite.await()
			job.cancel()
			gate.complete(Unit)
			job.join()
		}
		server.played shouldBe episodes("e", 9).toSet()
	}

	test("writes stop after the failing batch and report what's left") {
		val calls = mutableListOf<String>()
		val result = PlayedWrites.run(episodes("x", 10) + "x1") { id ->
			calls += id
			if (id == "x2") error("nope")
		}
		calls shouldContainExactly episodes("x", 4)
		result.done shouldContainExactly listOf("x1", "x3", "x4")
		result.remaining shouldContainExactly listOf("x2", "x5", "x6", "x7", "x8", "x9", "x10")
		result.error?.message shouldBe "nope"
	}
})
