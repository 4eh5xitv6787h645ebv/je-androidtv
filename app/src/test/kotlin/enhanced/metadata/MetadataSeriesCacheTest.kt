package org.jellyfin.androidtv.enhanced.metadata

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json
import org.jellyfin.androidtv.auth.repository.UserRepository
import org.jellyfin.androidtv.enhanced.api.EnhancedApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.UserDto
import java.util.UUID

private fun seriesApi(server: String, tmdbId: Int) = mockk<EnhancedApi>().also {
	every { it.baseUrl } returns server
	coEvery { it.get(any(), any()) } returns Json.parseToJsonElement("""{"ProviderIds":{"Tmdb":"$tmdbId"}}""")
}

class MetadataSeriesCacheTest : FunSpec({
	val seriesId = UUID.randomUUID()
	val season = BaseItemDto(id = UUID.randomUUID(), type = BaseItemKind.SEASON, seriesId = seriesId, indexNumber = 1)
	val user = mockk<UserDto>().also { every { it.id } returns UUID.randomUUID() }
	val userRepository = mockk<UserRepository>().also { every { it.currentUser } returns MutableStateFlow(user) }

	test("a series' TMDB id is cached per server: the same series id on another server is looked up again") {
		val serverA = seriesApi("https://a", 111)
		val serverB = seriesApi("https://b", 222)
		val shared = mockk<EnhancedApi>()
		val repository = MetadataRepository(shared, mockk(), mockk(), userRepository)

		every { shared.pinned() } returns serverA
		repository.tmdbTarget(season)?.tmdbId shouldBe 111
		repository.tmdbTarget(season)?.tmdbId shouldBe 111
		coVerify(exactly = 1) { serverA.get(any(), any()) }

		every { shared.pinned() } returns serverB
		repository.tmdbTarget(season)?.tmdbId shouldBe 222
	}
})
