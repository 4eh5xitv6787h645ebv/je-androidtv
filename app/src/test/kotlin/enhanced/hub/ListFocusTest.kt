package org.jellyfin.androidtv.enhanced.ui.hub

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ListFocusTest : FunSpec({
	val rows = listOf("a", "b", "c", "d")

	test("focus moves to the next row, or the previous one at the end") {
		ListFocus.neighbour(rows, listOf("b")) shouldBe "c"
		ListFocus.neighbour(rows, listOf("a")) shouldBe "b"
		ListFocus.neighbour(rows, listOf("d")) shouldBe "c"
	}

	test("several removed rows: the first remaining one after them, else before them") {
		ListFocus.neighbour(rows, listOf("b", "c")) shouldBe "d"
		ListFocus.neighbour(rows, listOf("c", "d")) shouldBe "b"
		ListFocus.neighbour(rows, listOf("a", "c")) shouldBe "d"
	}

	test("nothing left: no row to focus (the empty state takes it)") {
		ListFocus.neighbour(listOf("a"), listOf("a")) shouldBe null
		ListFocus.neighbour(rows, rows) shouldBe null
		ListFocus.neighbour(emptyList(), listOf("a")) shouldBe null
	}

	test("a key that isn't listed leaves the focus on the first row") {
		ListFocus.neighbour(rows, listOf("x")) shouldBe "a"
	}
})
