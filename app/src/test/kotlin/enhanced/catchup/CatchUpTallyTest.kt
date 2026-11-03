package org.jellyfin.androidtv.enhanced.catchup

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

private fun movie(id: String) = CatchUpItem(id = id, name = "Movie $id", type = "Movie")

class CatchUpTallyTest : FunSpec({
	val deck = CatchUpDeck().withBatch(listOf(movie("a"), movie("b"), movie("c")), requested = 3)

	test("nothing is known before the first count") {
		CatchUpTally().left(deck) shouldBe null
	}

	test("dismissed titles are taken off the server count") {
		CatchUpTally().read(47).left(deck.copy(dismissed = setOf("x", "y"))) shouldBe 45
	}

	test("watched titles count until the next read, watchlisted ones for the whole round") {
		val tally = CatchUpTally().read(47)
			.handled(movie("a"), CatchUpAction.Watched(listOf("a"), seasonNumbers = null))
			.handled(movie("b"), CatchUpAction.Watchlist(likedId = "b", alreadyLiked = false))
		val after = deck.after(movie("a"), CatchUpAction.Dismiss).copy(dismissed = emptySet())
		tally.left(after) shouldBe 45
		// The server no longer counts the watched title; the watchlisted one is still unwatched
		tally.read(46).left(after) shouldBe 45
	}

	test("undo puts the title back in the count") {
		val watched = CatchUpAction.Watched(listOf("a"), seasonNumbers = null)
		CatchUpTally().read(10).handled(movie("a"), watched).undone(movie("a"), watched).left(deck) shouldBe 10
	}

	test("never less than the cards already dealt, and exactly those once the deck is exhausted") {
		CatchUpTally().read(1).left(deck) shouldBe 3
		CatchUpTally().read(47).left(deck.copy(exhausted = true)) shouldBe 3
	}
})
