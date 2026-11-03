package org.jellyfin.androidtv.enhanced.ui.seerr

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.jellyfin.androidtv.enhanced.seerr.SeerrSeason

class SeerrSeasonLabelsTest : FunSpec({
	val named = SeerrSeason(seasonNumber = 2, name = "Season 2: The Betrayal", episodeCount = 8)

	test("Seerr's season names show for titles that aren't protected") {
		SeerrSeasonLabels.customName(named, guarded = false) shouldBe "Season 2: The Betrayal"
	}

	test("protected titles get the neutral label, in the season cards and the request picker alike") {
		SeerrSeasonLabels.customName(named, guarded = true) shouldBe null
	}

	test("specials and unnamed seasons always use the neutral label") {
		SeerrSeasonLabels.customName(SeerrSeason(seasonNumber = 0, name = "Behind the scenes"), guarded = false) shouldBe null
		SeerrSeasonLabels.customName(SeerrSeason(seasonNumber = 3, name = " "), guarded = false) shouldBe null
		SeerrSeasonLabels.customName(SeerrSeason(seasonNumber = 3), guarded = false) shouldBe null
	}
})
