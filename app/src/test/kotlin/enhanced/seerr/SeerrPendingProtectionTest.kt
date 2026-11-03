package org.jellyfin.androidtv.enhanced.seerr

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.serialization.json.JsonPrimitive
import org.jellyfin.androidtv.enhanced.data.EnhancedPublicConfig
import org.jellyfin.androidtv.enhanced.data.EnhancedRepository
import org.jellyfin.androidtv.enhanced.data.EnhancedState
import org.jellyfin.androidtv.enhanced.data.SpoilerGuardState
import org.jellyfin.androidtv.enhanced.data.SpoilerPolicy
import org.jellyfin.androidtv.enhanced.data.SpoilerProtection
import org.jellyfin.androidtv.enhanced.data.SpoilerSnapshot

private fun snapshot(state: SpoilerGuardState?) = SpoilerSnapshot(
	enabled = true,
	state = state,
	policy = SpoilerPolicy.STRICT,
	collectionMovieIds = emptySet(),
)

private val pendingShow = SpoilerGuardState(pendingTmdb = mapOf("tv:100" to JsonPrimitive("A show")))

private fun repository(snapshot: SpoilerSnapshot, spoilerGuard: Boolean = true): SeerrRepository {
	val state = EnhancedState(available = true, config = EnhancedPublicConfig(spoilerBlurEnabled = spoilerGuard))
	val enhancedRepository = mockk<EnhancedRepository>().also {
		every { it.current } returns state
		coEvery { it.ensureLoaded(any()) } returns state
	}
	val protection = mockk<SpoilerProtection>().also { coEvery { it.snapshot() } returns snapshot }
	return SeerrRepository(mockk(relaxed = true), enhancedRepository, mockk(relaxed = true), lazyOf(protection))
}

class SeerrPendingProtectionTest : FunSpec({
	test("a pending opt-in withholds a title's TMDB artwork, by media type and TMDB id") {
		val snapshot = snapshot(pendingShow)
		SeerrRepository.externalArtworkWithheld(snapshot, SeerrResult(id = 100, mediaType = "tv")) shouldBe true
		// Same TMDB id, other media type: a different title
		SeerrRepository.externalArtworkWithheld(snapshot, SeerrResult(id = 100, mediaType = "movie")) shouldBe false
		SeerrRepository.externalArtworkWithheld(snapshot, SeerrResult(id = 101, mediaType = "tv")) shouldBe false
	}

	test("unreadable lists withhold every title; Spoiler Guard off withholds nothing") {
		SeerrRepository.externalArtworkWithheld(snapshot(null), SeerrResult(id = 5, mediaType = "movie")) shouldBe true
		SeerrRepository.externalArtworkWithheld(SpoilerSnapshot.DISABLED, SeerrResult(id = 100, mediaType = "tv")) shouldBe false
		SeerrRepository.externalArtworkWithheld(SpoilerSnapshot.UNKNOWN, SeerrResult(id = 5, mediaType = "movie")) shouldBe true
	}

	test("cards of pending titles lose their poster and year; others keep them") {
		val results = repository(snapshot(pendingShow)).refreshLibraryArtwork(listOf(
			SeerrResult(id = 100, mediaType = "tv", posterPath = "/a.jpg"),
			SeerrResult(id = 200, mediaType = "movie", posterPath = "/b.jpg"),
		))
		results[0].libraryArtwork shouldBe true
		results[0].libraryPosterUrl shouldBe null
		results[1].libraryArtwork shouldBe false
	}

	test("a card withheld earlier shows its artwork again once the opt-in is gone") {
		val withheld = SeerrResult(id = 100, mediaType = "tv", libraryArtwork = true)
		repository(snapshot(SpoilerGuardState())).refreshLibraryArtwork(listOf(withheld)).single().libraryArtwork shouldBe false
		repository(snapshot(SpoilerGuardState()), spoilerGuard = false).refreshLibraryArtwork(listOf(withheld)).single().libraryArtwork shouldBe false
	}
})
