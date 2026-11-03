package org.jellyfin.androidtv.enhanced.catchup

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

private fun movie(id: String) = CatchUpItem(id = id, name = "Movie $id", type = "Movie")
private fun show(id: String) = CatchUpItem(id = id, name = "Show $id", type = "Series")

class CatchUpDeckTest : FunSpec({
	test("batches are de-duplicated against the deck and each other") {
		val deck = CatchUpDeck()
			.withBatch(listOf(movie("a"), movie("b"), movie("a")))
			.withBatch(listOf(movie("b"), movie("c")))
		deck.cards.map { it.id } shouldBe listOf("a", "b", "c")
	}

	test("ids are compared dashless and lowercase") {
		val deck = CatchUpDeck().withBatch(listOf(movie("AAAA-BBBB")))
			.withBatch(listOf(movie("aaaabbbb")))
		deck.cards.size shouldBe 1
		deck.cards.first().key shouldBe "aaaabbbb"
	}

	test("locally dismissed titles are skipped") {
		val deck = CatchUpDeck(dismissed = setOf("b")).withBatch(listOf(movie("a"), movie("B"), movie("c")))
		deck.cards.map { it.id } shouldBe listOf("a", "c")
	}

	test("a short batch with nothing new exhausts the deck") {
		val deck = CatchUpDeck().withBatch(listOf(movie("a")), requested = 20).withBatch(listOf(movie("a")), requested = 20)
		deck.exhausted shouldBe true
		deck.needsMore shouldBe false
	}

	test("full batches of repeats are retried a few times before giving up") {
		val full = (1..20).map { movie("m$it") }
		var deck = CatchUpDeck().withBatch(full, requested = 20)
		repeat(CatchUpDeck.MAX_EMPTY_BATCHES - 1) {
			deck = deck.withBatch(full, requested = 20)
			deck.exhausted shouldBe false
		}
		deck = deck.withBatch(full, requested = 20)
		deck.exhausted shouldBe true
	}

	test("anything new resets the empty batch count") {
		val full = (1..20).map { movie("m$it") }
		val deck = CatchUpDeck().withBatch(full, 20).withBatch(full, 20).withBatch(full + movie("new"), 21)
		deck.emptyBatches shouldBe 0
		deck.exhausted shouldBe false
	}

	test("the deck asks for more when it runs low") {
		val deck = CatchUpDeck().withBatch((1..CatchUpDeck.REFILL_AT).map { movie("m$it") })
		deck.needsMore shouldBe false
		deck.after(deck.current!!, CatchUpAction.Dismiss).needsMore shouldBe true
	}

	test("an action advances to the next card and can be undone") {
		val deck = CatchUpDeck().withBatch(listOf(movie("a"), movie("b")))
		val watched = CatchUpAction.Watched(listOf("a"), seasonNumbers = null)
		val next = deck.after(deck.current!!, watched)
		next.current?.id shouldBe "b"
		next.canUndo shouldBe true

		val (undone, last) = next.undo()!!
		last shouldBe CatchUpDone(movie("a"), watched)
		undone.cards.map { it.id } shouldBe listOf("a", "b")
		undone.canUndo shouldBe false
	}

	test("dismiss adds to the dismissed set and undo removes it") {
		val deck = CatchUpDeck().withBatch(listOf(movie("A"), movie("b")))
		val dismissed = deck.after(deck.current!!, CatchUpAction.Dismiss)
		dismissed.dismissed shouldBe setOf("a")

		val (undone, _) = dismissed.undo()!!
		undone.dismissed.shouldBeEmpty()
		undone.current?.id shouldBe "A"
	}

	test("undo restores in reverse order") {
		var deck = CatchUpDeck().withBatch(listOf(movie("a"), movie("b"), movie("c")))
		deck = deck.after(deck.current!!, CatchUpAction.Dismiss)
		deck = deck.after(deck.current!!, CatchUpAction.Watchlist("b", alreadyLiked = false))
		deck.current?.id shouldBe "c"

		val (once, first) = deck.undo()!!
		first.item.id shouldBe "b"
		val (twice, second) = once.undo()!!
		second.item.id shouldBe "a"
		twice.cards.map { it.id } shouldBe listOf("a", "b", "c")
		twice.undo() shouldBe null
	}

	test("undo history is capped") {
		var deck = CatchUpDeck().withBatch((1..CatchUpDeck.MAX_HISTORY + 5).map { movie("m$it") }, requested = 100)
		repeat(CatchUpDeck.MAX_HISTORY + 5) { deck = deck.after(deck.current!!, CatchUpAction.Dismiss) }
		deck.history.size shouldBe CatchUpDeck.MAX_HISTORY
		deck.history.first().item.id shouldBe "m6"
	}

	test("dropping a card leaves no undo entry") {
		val deck = CatchUpDeck().withBatch(listOf(movie("a"), movie("b"))).drop("A")
		deck.cards.map { it.id } shouldBe listOf("b")
		deck.canUndo shouldBe false
	}

	test("switching filter keeps matching cards and returns the others to the pool") {
		val deck = CatchUpDeck().withBatch(listOf(movie("a"), show("s"), movie("b")))
		val shows = deck.restart(CatchUpFilter.SHOWS)
		shows.cards.map { it.id } shouldBe listOf("s")
		shows.seen shouldBe setOf("s")
		// The returned movies can be dealt again
		shows.withBatch(listOf(movie("a"))).cards.map { it.id } shouldBe listOf("s", "a")
	}

	test("clearing dismissals lets those titles come back") {
		val deck = CatchUpDeck(dismissed = setOf("x")).withBatch(listOf(movie("x"), movie("a")))
		deck.cards.map { it.id } shouldBe listOf("a")
		val cleared = deck.clearDismissed()
		cleared.dismissed.shouldBeEmpty()
		cleared.withBatch(listOf(movie("x"))).cards.map { it.id } shouldBe listOf("a", "x")
	}

	test("look again forgets what was dealt but keeps dismissals and undo") {
		var deck = CatchUpDeck(dismissed = setOf("x")).withBatch(listOf(movie("a"), movie("b")))
		deck = deck.after(deck.current!!, CatchUpAction.Watchlist("a", alreadyLiked = false))
		val again = deck.reshuffle()
		again.cards.shouldBeEmpty()
		again.dismissed shouldBe setOf("x")
		again.canUndo shouldBe true
		again.withBatch(listOf(movie("b"), movie("x"))).cards.map { it.id } shouldBe listOf("b")
	}
})

class SeasonSelectionTest : FunSpec({
	val seasons = (1..4).map { CatchUpSeason(id = "season$it", indexNumber = it, childCount = 10) }
	val series = show("show")

	test("selecting a season selects the earlier ones too") {
		SeasonSelection.toggle(emptySet(), 2) shouldBe setOf(0, 1, 2)
	}

	test("selecting a selected season unticks only that one") {
		SeasonSelection.toggle(setOf(0, 1, 2), 1) shouldBe setOf(0, 2)
		SeasonSelection.toggle(setOf(0, 2), 3) shouldBe setOf(0, 1, 2, 3)
	}

	test("specials are not offered and seasons are in order") {
		val list = listOf(
			CatchUpSeason(id = "s2", indexNumber = 2),
			CatchUpSeason(id = "specials", indexNumber = 0),
			CatchUpSeason(id = "s1", indexNumber = 1),
			CatchUpSeason(id = "unknown"),
		)
		SeasonSelection.pickable(list).map { it.id } shouldBe listOf("s1", "s2")
	}

	test("all seasons mark the whole show and log all") {
		val action = SeasonSelection.action(series, seasons, setOf(0, 1, 2, 3))
		action shouldBe CatchUpAction.Watched(listOf("show"), listOf("all"))
	}

	test("some seasons mark each season and log their numbers") {
		val action = SeasonSelection.action(series, seasons, setOf(2, 0))
		action shouldBe CatchUpAction.Watched(listOf("season1", "season3"), listOf("1", "3"))
	}

	test("nothing selected is no action") {
		SeasonSelection.action(series, seasons, emptySet()) shouldBe null
		SeasonSelection.action(series, seasons, setOf(9)) shouldBe null
	}

	test("season progress counts watched episodes") {
		val season = CatchUpSeason(id = "s", indexNumber = 1, childCount = 10, userData = CatchUpUserData(unplayedItemCount = 7))
		season.watchedCount shouldBe 3
		season.fullyWatched shouldBe false
		season.copy(userData = CatchUpUserData(unplayedItemCount = 0)).fullyWatched shouldBe true
		CatchUpSeason(id = "s", childCount = 10).watchedCount shouldBe 0
	}
})

class CatchUpModelsTest : FunSpec({
	test("decodes an Items API page as the server sends it") {
		val json = """
			{"Items":[{"Name":"Fall 2","Id":"59da481b0df56cf09b7a0ec664675c11","Type":"Movie","ProductionYear":2026,
			"OfficialRating":"PG-13","CommunityRating":7.284,"RunTimeTicks":58428480000,"Genres":["Thriller"],
			"Overview":"On a perilous climb","ImageTags":{"Primary":"c62b-jeu1","Thumb":"48d6"},
			"BackdropImageTags":["df33-jeu1"],"UserData":{"Played":false,"PlayCount":0,"Key":"x"},"Unknown":1}],
			"TotalRecordCount":44,"StartIndex":0}
		""".trimIndent()
		val page = org.jellyfin.androidtv.enhanced.api.EnhancedApi.json.decodeFromString<CatchUpItemsPage>(json)
		page.totalRecordCount shouldBe 44
		val item = page.items.single()
		item.isSeries shouldBe false
		item.runtimeMinutes shouldBe 97
		item.primaryTag shouldBe "c62b-jeu1"
		item.backdropTag shouldBe "df33-jeu1"
		item.userData?.played shouldBe false
		item.userData?.likes shouldBe null
	}

	test("decodes seasons with user data") {
		val json = """{"Items":[{"Id":"bc7d","IndexNumber":1,"ChildCount":13,"Name":"Season 1",
			"UserData":{"PlayedPercentage":0,"UnplayedItemCount":13,"Played":false}}]}"""
		val season = org.jellyfin.androidtv.enhanced.api.EnhancedApi.json.decodeFromString<CatchUpSeasonsPage>(json).items.single()
		season.episodeCount shouldBe 13
		season.watchedCount shouldBe 0
	}

	test("runtime rounds to minutes and ignores zero") {
		movie("a").copy(runTimeTicks = 600_000_000L * 90 + 299_999_999L).runtimeMinutes shouldBe 90
		movie("a").copy(runTimeTicks = 600_000_000L * 90 + 300_000_000L).runtimeMinutes shouldBe 91
		movie("a").copy(runTimeTicks = 0).runtimeMinutes shouldBe null
	}

	test("filters match item types") {
		CatchUpFilter.ALL.matches(show("s")) shouldBe true
		CatchUpFilter.MOVIES.matches(show("s")) shouldBe false
		CatchUpFilter.SHOWS.matches(show("s")) shouldBe true
		CatchUpFilter.MOVIES.matches(movie("m")) shouldBe true
	}

	test("event bodies carry only the fields that apply") {
		val done = CatchUpDone(show("abc"), CatchUpAction.Watched(listOf("x"), listOf("1", "2")))
		CatchUpEvents.forAction(done, ok = true).toString() shouldBe
			"""{"type":"swipe","action":"watched","itemId":"abc","ok":true,"seasons":["1","2"]}"""

		val movieDone = CatchUpDone(movie("m"), CatchUpAction.Watched(listOf("m"), null))
		CatchUpEvents.forAction(movieDone, ok = false).toString() shouldBe
			"""{"type":"swipe","action":"watched","itemId":"m","ok":false}"""

		CatchUpEvents.swipe(CatchUpEvents.UNDO, "m", ok = true, undoOf = "dismiss").toString() shouldBe
			"""{"type":"swipe","action":"undo","undoOf":"dismiss","itemId":"m","ok":true}"""
	}

	test("dismissals are stored per server and user") {
		val key = CatchUpDismissStore.key("0202511B-0EAC-4284-AB04-5B3BA556D72E", "7ef882cd307344279240d0eca8333a9f")
		key shouldBe "dismissed_0202511b0eac4284ab045b3ba556d72e_7ef882cd307344279240d0eca8333a9f"
		key shouldNotBe CatchUpDismissStore.key("0202511b0eac4284ab045b3ba556d72e", "other")
	}
})
