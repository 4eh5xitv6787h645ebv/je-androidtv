package org.jellyfin.androidtv.enhanced.search

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.jellyfin.androidtv.enhanced.api.EnhancedApi
import org.jellyfin.androidtv.enhanced.data.EnhancedPublicConfig
import org.jellyfin.androidtv.enhanced.data.EnhancedRepository
import org.jellyfin.androidtv.enhanced.data.EnhancedState
import org.jellyfin.androidtv.enhanced.data.SeerrUserStatus
import org.jellyfin.androidtv.enhanced.seerr.SeerrPage
import org.jellyfin.androidtv.enhanced.seerr.SeerrResult
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import kotlin.time.Duration

class SeerrSearchSessionTest : FunSpec({
	val enabledState = EnhancedState(
		available = true,
		config = EnhancedPublicConfig(
			jellyseerrEnabled = true,
			jellyseerrShowSearchResults = true,
			jellyseerrShowPersonDiscovery = true,
			showCollectionsInSearch = true,
		),
		seerr = SeerrUserStatus(active = true, userFound = true),
	)

	lateinit var enhancedRepository: EnhancedRepository
	lateinit var repository: SeerrSearchRepository
	lateinit var scope: CoroutineScope

	fun movie(id: Int) = SeerrResult(id = id, mediaType = "movie", title = "Movie $id")
	fun page(page: Int, totalPages: Int, vararg results: SeerrResult) = SeerrPage(page = page, totalPages = totalPages, results = results.toList())

	beforeTest {
		enhancedRepository = mockk()
		repository = mockk()
		coEvery { enhancedRepository.ensureLoaded(any()) } returns enabledState
		coEvery { repository.filter(any(), any()) } returns SeerrSearchFilter()
		coEvery { repository.withLibraryArtwork(any()) } answers { firstArg() }
		coEvery { repository.movieCollections(any()) } returns SeerrMovieCollections()
		startKoin {
			modules(module {
				single { enhancedRepository }
				single { repository }
			})
		}
		// Unconfined runs each step straight away on the calling thread, like Main.immediate
		scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
	}

	afterTest {
		scope.cancel()
		stopKoin()
	}

	test("a late response for an older query never renders") {
		val slow = CompletableDeferred<SeerrPage>()
		coEvery { repository.search("first", 1) } coAnswers { slow.await() }
		coEvery { repository.search("second", 1) } returns page(1, 1, movie(2))

		val session = SeerrSearchSession(scope)
		session.onQuery("first", Duration.ZERO)
		session.onQuery("second", Duration.ZERO)
		slow.complete(page(1, 1, movie(1)))

		session.state.value.query shouldBe "second"
		session.state.value.media.map { it.id } shouldBe listOf(2)
	}

	test("clearing the query clears the rows") {
		coEvery { repository.search("matrix", 1) } returns page(1, 1, movie(603))
		val session = SeerrSearchSession(scope)
		session.onQuery("matrix", Duration.ZERO)
		session.state.value.media.size shouldBe 1
		session.onQuery("", Duration.ZERO)
		session.state.value shouldBe SeerrSearchState()
	}

	test("nothing is fetched when the search gate is off") {
		coEvery { enhancedRepository.ensureLoaded(any()) } returns enabledState.copy(config = enabledState.config.copy(jellyseerrShowSearchResults = false))
		val session = SeerrSearchSession(scope)
		session.onQuery("matrix", Duration.ZERO)
		session.state.value.media shouldBe emptyList()
		coVerify(exactly = 0) { repository.search(any(), any()) }
	}

	test("the row pages in up to its limit, then offers View all") {
		for (p in 1..SeerrSearchSession.ROW_PAGES) coEvery { repository.search("matrix", p) } returns page(p, 7, movie(p))
		val session = SeerrSearchSession(scope)
		session.onQuery("matrix", Duration.ZERO)
		session.state.value.media.map { it.id } shouldBe listOf(1)
		repeat(SeerrSearchSession.ROW_PAGES + 2) { session.loadMore() }
		session.state.value.media.map { it.id } shouldBe (1..SeerrSearchSession.ROW_PAGES).toList()
		session.state.value.viewAll shouldBe true
		coVerify(exactly = 0) { repository.search("matrix", SeerrSearchSession.ROW_PAGES + 1) }
	}

	test("a failed page shows the server's message and Retry loads the same page") {
		coEvery { repository.search("matrix", 1) } throws EnhancedApi.EnhancedApiException(503, "x", code = "unreachable", serverMessage = "Seerr is unreachable")
		val session = SeerrSearchSession(scope)
		session.onQuery("matrix", Duration.ZERO)
		session.state.value.error shouldBe SeerrSearchError("Seerr is unreachable")

		coEvery { repository.search("matrix", 1) } returns page(1, 1, movie(603))
		session.retry()
		session.state.value.error shouldBe null
		session.state.value.media.map { it.id } shouldBe listOf(603)
	}

	test("a page filtered out entirely makes the row look at the next one") {
		coEvery { repository.filter(any(), any()) } returns SeerrSearchFilter(hiddenTmdbIds = setOf("1"))
		coEvery { repository.search("matrix", 1) } returns page(1, 3, movie(1))
		coEvery { repository.search("matrix", 2) } returns page(2, 3, movie(2))
		val session = SeerrSearchSession(scope)
		session.onQuery("matrix", Duration.ZERO)
		session.state.value.media.map { it.id } shouldBe listOf(2)
	}

	test("collections of movie hits are added in their own list") {
		coEvery { repository.search("matrix", 1) } returns page(1, 1, movie(603), movie(604))
		coEvery { repository.movieCollections(listOf(603, 604)) } returns SeerrMovieCollections(
			mapOf("603" to SeerrCollectionRef(2344, "The Matrix Collection"), "604" to SeerrCollectionRef(2344, "The Matrix Collection"))
		)
		val session = SeerrSearchSession(scope)
		session.onQuery("matrix", Duration.ZERO)
		session.state.value.collections.map { it.tmdbCollectionId } shouldBe listOf(2344)
	}
})
