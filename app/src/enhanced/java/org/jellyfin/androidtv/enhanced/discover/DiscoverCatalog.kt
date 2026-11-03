package org.jellyfin.androidtv.enhanced.discover

import androidx.annotation.StringRes
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.enhanced.seerr.SeerrDiscoverFeed
import org.jellyfin.androidtv.enhanced.seerr.SeerrSource

/** A curated studio or network tile. [logoPath] is the TMDB `logo_path`, so the tiles need no lookups. */
data class DiscoverCompany(
	val name: String,
	val id: Int,
	val logoPath: String?,
	/** For networks: the network's own production company, whose movies its page shows beside its series. */
	val companyId: Int? = null,
)

/**
 * The Discover screen's fixed rows and tiles. Port of `js/jellyseerr/recommendations/recommendations-catalog.js`
 * (ROWS, STUDIOS, NETWORKS); keep the ids in sync with it.
 */
object DiscoverCatalog {
	/** The media rows, in display order. */
	val feeds: List<Pair<SeerrDiscoverFeed, Int>> = listOf(
		SeerrDiscoverFeed.TRENDING to R.string.je_discover_trending,
		SeerrDiscoverFeed.POPULAR_MOVIES to R.string.je_discover_popular_movies,
		SeerrDiscoverFeed.POPULAR_TV to R.string.je_discover_popular_tv,
		SeerrDiscoverFeed.UPCOMING_MOVIES to R.string.je_discover_upcoming_movies,
		SeerrDiscoverFeed.UPCOMING_TV to R.string.je_discover_upcoming_tv,
	)

	@StringRes
	fun title(feed: SeerrDiscoverFeed): Int = feeds.first { it.first == feed }.second

	val studios = listOf(
		DiscoverCompany("Marvel Studios", 420, "/hUzeosd33nzE5MCNsZxCGEKTXaQ.png"),
		DiscoverCompany("Pixar", 3, "/1TjvGVDMYsj6JBxOAkUHpPEwLf7.png"),
		DiscoverCompany("Walt Disney Pictures", 2, "/wdrCwmRnLFJhEoH8GSfymY85KHT.png"),
		DiscoverCompany("Warner Bros. Pictures", 174, "/zhD3hhtKB5qyv7ZeL4uLpNxgMVU.png"),
		DiscoverCompany("Universal Pictures", 33, "/8lvHyhjr8oUKOOy2dKXoALWKdp0.png"),
		DiscoverCompany("Paramount Pictures", 4, "/jay6WcMgagAklUt7i9Euwj1pzTF.png"),
		DiscoverCompany("Lucasfilm", 1, "/tlVSws0RvvtPBwViUyOFAO0vcQS.png"),
		DiscoverCompany("Illumination", 6704, "/fOG2oY4m1YuYTQh4bMqqZkmgOAI.png"),
		DiscoverCompany("DreamWorks Animation", 521, "/3BPX5VGBov8SDqTV7wC1L1xShAS.png"),
		DiscoverCompany("Sony Pictures", 34, "/xAb1o9HrSvKBo9mnXC8fJKDNu00.png"),
		DiscoverCompany("20th Century Studios", 127928, "/h0rjX5vjW5r8yEnUBStFarjcLT4.png"),
		DiscoverCompany("Legendary Pictures", 923, "/5UQsZrfbfG2dYJbx8DxfoTr2Bvu.png"),
		DiscoverCompany("A24", 41077, "/1ZXsGaFPgrgS6ZZGS37AqD5uU12.png"),
		DiscoverCompany("Blumhouse Productions", 3172, "/rzKluDcRkIwHZK2pHsiT667A2Kw.png"),
		DiscoverCompany("Metro-Goldwyn-Mayer", 21, "/usUnaYV6hQnlVAXP6r4HwrlLFPG.png"),
		DiscoverCompany("Columbia Pictures", 5, "/71BqEFAF4V3qjjMPCpLuyJFB9A.png"),
	)

	val networks = listOf(
		DiscoverCompany("Netflix", 213, "/wwemzKWzjKYJFfCeiB57q3r4Bcm.png", companyId = 178464),
		DiscoverCompany("HBO", 49, "/tuomPhY2UtuPTqqFnKMVHvSb724.png", companyId = 3268),
		DiscoverCompany("Disney+", 2739, "/1edZOYAfoyZyZ3rklNSiUpXX30Q.png"),
		DiscoverCompany("Apple TV+", 2552, "/bngHRFi794mnMq34gfVcm9nDxN1.png", companyId = 194232),
		DiscoverCompany("Amazon Prime Video", 1024, "/w7HfLNm9CWwRmAMU58udl2L7We7.png", companyId = 210099),
		DiscoverCompany("Hulu", 453, "/pqUTCleNUiTLAVlelGxUgWn1ELh.png"),
		DiscoverCompany("Paramount+", 4330, "/fi83B1oztoS47xxcemFdPMhIzK.png"),
		DiscoverCompany("FX", 88, "/aexGjtcs42DgRtZh7zOxayiry4J.png", companyId = 15990),
		DiscoverCompany("BBC", 4, "/uJjcCg3O4DMEjM0xtno9OWFciRP.png", companyId = 3324),
		DiscoverCompany("Showtime", 67, "/Allse9kbjiP6ExaQrnSpIhkurEi.png", companyId = 148935),
		DiscoverCompany("Starz", 318, "/qx3Y9LCaK4mq1ykFuDIfjshlo3U.png", companyId = 8034),
		DiscoverCompany("AMC", 174, "/pmvRmATOCaDykE6JrVoeYxlFHw3.png", companyId = 122304),
		DiscoverCompany("Adult Swim", 80, "/tHZPHOLc6iF27G34cAZGPsMtMSy.png", companyId = 6759),
		DiscoverCompany("Nickelodeon", 13, "/i0e7qsKhCcB6EJy0qvjhnFB2lgp.png", companyId = 2348),
		DiscoverCompany("Crunchyroll", 1112, "/qqyXcZlJQKlRmAD1TCKV7mGLQlt.png", companyId = 198847),
		DiscoverCompany("The CW", 71, "/hEpcdJ4O6eitG9ADSnDXNUrlovS.png", companyId = 218482),
	)

	fun studioSource(studio: DiscoverCompany): SeerrSource = SeerrSource.Company(studio.id, studio.name)

	fun networkSource(network: DiscoverCompany): SeerrSource = SeerrSource.Network(network.id, network.companyId, network.name)

	/**
	 * TMDB logo rendered white, as Seerr's own studio/network sliders do, so the mostly dark logos read on the
	 * app's dark tiles.
	 */
	fun logoUrl(path: String?): String? =
		path?.takeIf { it.startsWith("/") }?.let { "https://image.tmdb.org/t/p/w300_filter(duotone,ffffff,bababa)$it" }
}
