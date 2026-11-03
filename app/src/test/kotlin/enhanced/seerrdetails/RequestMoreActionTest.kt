package org.jellyfin.androidtv.enhanced.seerrdetails

import android.content.Context
import androidx.fragment.app.Fragment
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import org.jellyfin.androidtv.enhanced.api.EnhancedApi
import org.jellyfin.androidtv.enhanced.data.EnhancedPublicConfig
import org.jellyfin.androidtv.enhanced.data.EnhancedState
import org.jellyfin.androidtv.enhanced.data.SeerrUserStatus
import org.jellyfin.androidtv.enhanced.requests.RequestMoreAction
import org.jellyfin.androidtv.enhanced.ui.details.DetailsContext
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import java.util.UUID

class RequestMoreActionTest : FunSpec({
	val seriesId = UUID.fromString("6adf7bc8-18f8-c525-2a6b-b1c6b2864e55")
	val linked = EnhancedState(
		available = true,
		config = EnhancedPublicConfig(jellyseerrEnabled = true, jellyseerrShowRequestMoreOnSeries = true),
		seerr = SeerrUserStatus(active = true, userFound = true),
	)

	fun context(state: EnhancedState, item: BaseItemDto): DetailsContext {
		val androidContext = mockk<Context> { every { getString(any()) } returns "Request more" }
		val fragment = mockk<Fragment> { every { context } returns androidContext }
		return DetailsContext(fragment, item, state) {}
	}

	fun api(details: String, seasons: String): EnhancedApi = mockk {
		coEvery { get("jellyseerr/tv/1396", any()) } returns EnhancedApi.json.parseToJsonElement(details)
		coEvery { get("/Shows/$seriesId/Seasons", any()) } returns EnhancedApi.json.parseToJsonElement(seasons)
	}

	val series = BaseItemDto(id = seriesId, type = BaseItemKind.SERIES, providerIds = mapOf("Tmdb" to "1396"))

	// Live Breaking Bad shape: season 1 partially available and in the library, the rest never requested
	val breakingBad = """
		{"id": 1396, "name": "Breaking Bad",
		 "seasons": [{"seasonNumber": 0, "episodeCount": 9}, {"seasonNumber": 1, "episodeCount": 7}, {"seasonNumber": 2, "episodeCount": 13}],
		 "mediaInfo": {"id": 639, "status": 4, "jellyfinMediaId": "6adf7bc818f8c5252a6bb1c6b2864e55",
		   "seasons": [{"seasonNumber": 1, "status": 4}, {"seasonNumber": 2, "status": 1}], "requests": []}}
	""".trimIndent()
	val librarySeasonOne = """{"Items": [{"Name": "Season 1", "IndexNumber": 1}]}"""

	test("offers Request More when a regular season can still be requested") {
		val actions = RequestMoreAction(api(breakingBad, librarySeasonOne), mockk(relaxed = true)).actions(context(linked, series))
		actions shouldHaveSize 1
		actions.single().label shouldBe "Request more"
	}

	test("nothing when every regular season is in the library or requested") {
		val complete = breakingBad.replace(""""status": 1}""", """"status": 2}""")
		RequestMoreAction(api(complete, librarySeasonOne), mockk(relaxed = true)).actions(context(linked, series)).shouldBeEmpty()
	}

	test("still decides from Seerr's statuses when the library seasons can't be loaded") {
		val api = api(breakingBad, librarySeasonOne)
		coEvery { api.get("/Shows/$seriesId/Seasons", any()) } throws EnhancedApi.EnhancedApiException(500, "boom")
		RequestMoreAction(api, mockk(relaxed = true)).actions(context(linked, series)) shouldHaveSize 1
	}

	test("a failed Seerr lookup hides the button instead of failing the details screen") {
		val api = api(breakingBad, librarySeasonOne)
		coEvery { api.get("jellyseerr/tv/1396", any()) } throws EnhancedApi.EnhancedApiException(503, "down", code = "unreachable")
		RequestMoreAction(api, mockk(relaxed = true)).actions(context(linked, series)).shouldBeEmpty()
	}

	test("gated by the plugin setting, the Seerr link, the item type and a TMDB id") {
		val api = api(breakingBad, librarySeasonOne)
		val action = RequestMoreAction(api, mockk(relaxed = true))
		action.actions(context(linked.copy(config = linked.config.copy(jellyseerrShowRequestMoreOnSeries = false)), series)).shouldBeEmpty()
		action.actions(context(linked.copy(seerr = SeerrUserStatus(active = true, userFound = false)), series)).shouldBeEmpty()
		action.actions(context(linked.copy(available = false), series)).shouldBeEmpty()
		action.actions(context(linked, series.copy(type = BaseItemKind.MOVIE))).shouldBeEmpty()
		action.actions(context(linked, series.copy(providerIds = emptyMap()))).shouldBeEmpty()
		coVerify(exactly = 0) { api.get(any(), any()) }
	}
})
