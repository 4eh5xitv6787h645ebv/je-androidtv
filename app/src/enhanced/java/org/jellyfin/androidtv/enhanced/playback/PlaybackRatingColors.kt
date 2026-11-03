package org.jellyfin.androidtv.enhanced.playback

/** Colours of an age-rating box: background, border and text as ARGB. */
data class RatingStyle(val background: Long, val border: Long, val text: Long)

/** The colour groups of Jellyfin Enhanced's `css/ratings.css`. */
enum class RatingBucket(val style: RatingStyle) {
	/** Blue grey: the stylesheet's fallback for ratings it doesn't list. */
	DEFAULT(RatingStyle(background = 0xFF607D8B, border = 0x33FFFFFF, text = 0xFFFFFFFF)),
	/** Green: general / all audiences. */
	GENERAL(RatingStyle(background = 0xFF2E7D32, border = 0xFF4CAF50, text = 0xFFFFFFFF)),
	/** Dark yellow: parental guidance. */
	PARENTAL_GUIDANCE(RatingStyle(background = 0xFFDAA520, border = 0xFFFFC107, text = 0xFFFFFFFF)),
	/** Orange: parents strongly cautioned. */
	PARENTAL_CAUTION(RatingStyle(background = 0xFFE65100, border = 0xFFFF9800, text = 0xFFFFFFFF)),
	/** Red orange: mature / restricted. */
	MATURE(RatingStyle(background = 0xFFFF0000, border = 0xFFFF5722, text = 0xFFFFFFFF)),
	/** Dark red: restricted / adults only. */
	RESTRICTED(RatingStyle(background = 0xFF800000, border = 0xFFCC2222, text = 0xFFFFFFFF)),
	/** Very dark pink: adults only. */
	ADULTS_ONLY(RatingStyle(background = 0xFF880E4F, border = 0xFFC2185B, text = 0xFFFFFFFF)),
	// Germany's official FSK colours
	FSK_0(RatingStyle(background = 0xFFFFFFFF, border = 0xFFBDBDBD, text = 0xFF000000)),
	FSK_6(RatingStyle(background = 0xFFFFEB00, border = 0xFFD6C600, text = 0xFF000000)),
	FSK_12(RatingStyle(background = 0xFF12B53F, border = 0xFF07912E, text = 0xFF000000)),
	FSK_16(RatingStyle(background = 0xFF1597D4, border = 0xFF0875AB, text = 0xFF000000)),
	FSK_18(RatingStyle(background = 0xFFED0016, border = 0xFFB90011, text = 0xFF000000)),
	/** Blue: exempt / educational, and Australia's M. */
	EXEMPT(RatingStyle(background = 0xFF1565C0, border = 0xFF2196F3, text = 0xFFFFFFFF)),
	/** Black: refused classification / banned, and Australia's R18+. */
	REFUSED(RatingStyle(background = 0xFF000000, border = 0xFF424242, text = 0xFFFFFFFF)),
	/** Purple: special categories. */
	SPECIAL(RatingStyle(background = 0xFF6A1B9A, border = 0xFF8953AA, text = 0xFFFFFFFF)),
	/** Grey: unrated / unknown. */
	UNRATED(RatingStyle(background = 0xFF616161, border = 0xFF9E9E9E, text = 0xFFFFFFFF)),
}

/**
 * The age-rating colours Jellyfin Enhanced's web client uses (`js/extras/colored-ratings.js` with `css/ratings.css`).
 * The table is generated from the stylesheet (Jellyfin-Enhanced origin/main fbab2a6d): each rating maps to the group
 * of the last rule that lists it, and matching is exact and case-sensitive like the CSS attribute selectors.
 */
object PlaybackRatingColors {
	/** `normalizeRating` from colored-ratings.js: the rating the stylesheet is matched against. */
	fun normalize(rating: String): String {
		val upper = rating.replace(WHITESPACE, " ").trim().uppercase()
		return ALIASES[upper] ?: rating.trim()
	}

	fun bucket(rating: String): RatingBucket = BUCKETS[normalize(rating)] ?: RatingBucket.DEFAULT

	/** What the badge shows: FSK ratings are rewritten to their normalized form, as the web does. */
	fun displayText(rating: String): String = normalize(rating).let { if (it.startsWith("FSK-")) it else rating.trim() }

	private val WHITESPACE = Regex("\\s+")

	private val ALIASES = mapOf(
		"NOT RATED" to "NR", "NOT-RATED" to "NR", "UNRATED" to "NR", "NO RATING" to "NR",
		"DE-0" to "FSK-0", "DE-6" to "FSK-6", "DE-12" to "FSK-12", "DE-16" to "FSK-16", "DE-18" to "FSK-18",
		"FSK0" to "FSK-0", "FSK6" to "FSK-6", "FSK12" to "FSK-12", "FSK16" to "FSK-16", "FSK18" to "FSK-18",
		"FSK 0" to "FSK-0", "FSK 6" to "FSK-6", "FSK 12" to "FSK-12", "FSK 16" to "FSK-16", "FSK 18" to "FSK-18",
		"APPROVED" to "APPROVED", "PASSED" to "PASSED",
	)

	private val BUCKETS: Map<String, RatingBucket> by lazy {
		buildMap {
			for ((bucket, ratings) in TABLE) ratings.forEach { put(it, bucket) }
		}
	}

	// Generated from css/ratings.css; regenerate rather than editing by hand
	private val TABLE: List<Pair<RatingBucket, List<String>>> = listOf(
			RatingBucket.GENERAL to listOf("AA", "ATP", "APTA", "TP", "KN", "TE", "AL", "SU", "BG-A", "BG-B", "BR-AL", "BR-LIVRE", "BR-Livre", "CL-TE", "CO-T", "ES-TP", "ES-0+", "ES-ERI", "FR-0+", "GB-0+", "GB-ALL", "GB-All", "GB-E", "GB-G", "GR-E", "ID-P", "ID-P2+", "ID-P-BO", "ID-SU-BO", "ID-Semua Umur", "ID-Prasekolah", "IT-PT", "IT-Per tutti", "JP-A", "KZ-K", "PT-Públicos", "PT-3", "RO-AG", "RO-AP", "SE-0+", "SE-ALLA", "SE-Alla", "SE-Barntillåten", "SK-U", "SK-NR", "TR-Genel İzleyici Kitlesi", "TW-G", "TW-普遍級", "TW-普", "UA-0+", "E", "EC", "CA-TV-Y", "CA-TV-G", "CA-TV-Y7", "CA-TV-Y7-FV", "AR-G", "PL-b.o.", "PL-AL", "AR-ATP", "AU-C", "AU-G", "AU-P", "P", "AT-0", "AT-0+", "BE-AL", "BE-TOUS", "BE-KT", "BR-L", "L", "CA-G", "CA-E", "C8", "CA-C", "CA-C8", "C", "CH-0", "CN-G", "CZ-U", "DK-A", "FI-S", "FI-T", "FR-TP", "FR-U", "GR-0", "GR-K", "HK-I", "HU-KN", "IS-L", "IN-U", "U", "ID-SU", "IE-G", "G", "IL-G", "IT-T", "JP-G", "KR-ALL", "LV-U", "LT-V", "MY-U", "MX-AA", "NL-AL", "NZ-G", "NO-A", "PH-G", "PL-B/O", "PT-M/3", "PT-M/4", "RU-0+", "SG-G", "ZA-A", "ES-APTA", "ES-A", "SE-BTL", "SE-Btl", "TW-0+", "TH-P", "TH-G", "TR-G", "TR-Genel", "GB-U", "GB-Uc", "UK-U", "APPROVED", "PASSED", "TV-G", "TV-Y", "TV-Y7", "TV-Y7-FV", "VN-P"),
			RatingBucket.PARENTAL_GUIDANCE to listOf("AU-7+", "BE-MG6", "BE-KNT", "BG-C", "BR-9", "BR-A10", "BR-ER", "BR-A12", "CA-8+", "CA-TV-PG", "CH-12", "CL-6", "CL-TE+7", "CO-7", "CO-12", "ES-6+", "ES-9+", "ES-10", "FR-10", "FR-6+", "FR-9+", "GB-6+", "GB-7+", "GB-9", "GB-12+", "GB-12PG", "GR-K12", "GR-12", "GR-K13", "GR-K-13", "ID-A", "ID-A7+", "ID-A-BO", "ID-Anak", "IE-12PG", "IN-U/A", "IN-7+", "JP-B", "KZ-БА", "LV-12+", "NO-7", "NO-6 år", "NO-7 år", "NO-9 år", "NO-12 år", "NZ-PG", "PL-od 7 lat", "PT-6", "PT-10", "PT-M/10", "RO-12", "RO-AP-12", "SE-9+", "SE-10+", "SE-7+", "SE-7 År", "SE-Från 7 år", "SK-7", "SK-12", "TR-6", "TR-10", "TW-P", "TW-保護級", "TW-護", "U/A", "U/A 7+", "TV-PG-D", "TV-PG-L", "TV-PG-S", "TV-PG-V", "TV-PG-DL", "TV-PG-DS", "TV-PG-DV", "TV-PG-LS", "TV-PG-LV", "TV-PG-SV", "TV-PG-DLS", "TV-PG-DLV", "TV-PG-DSV", "TV-PG-LSV", "TV-PG-DLSV", "AR-SP", "CZ-12+", "FI-K7", "NL-MG6", "IT-VM 6", "IT-VM-6", "AR-13", "AU-PG", "PG", "AT-6", "AT-6+", "AT-8", "AT-8+", "AT-12+", "12+", "BE-6", "BE-9", "BR-6", "BR-10", "BR-12", "CA-PG", "CH-6", "CH-8", "CZ-12", "DK-7", "FI-7", "FI-K-7", "GR-13", "GR-K-12", "HK-IIA", "HU-6", "IS-6", "IS-9", "IN-UA", "IN-UA-7", "IN-U/A 7+", "IN-UA 7+", "ID-BO", "IE-PG", "IE-12", "IE-12A", "12A", "IL-PG", "IT-6+", "IT-VM6", "JP-PG12", "KR-12", "LV-7+", "LT-N-7", "MY-P12", "MY-P13", "MX-A", "NL-6", "NL-9", "NO-6", "NO-9", "NO-12", "PH-PG", "PL-7", "PT-M/6", "RU-6+", "SG-PG", "SG-PG13", "ZA-PG", "ZA-7-9PG", "ES-7", "SE-7", "TW-6+", "TH-13", "TR-6A", "TR-6+", "TR-PG", "GB-PG", "GB-12", "GB-12A", "UK-PG", "UK-12", "UK-12A", "12", "TV-PG", "GP", "VN-K", "10"),
			RatingBucket.PARENTAL_CAUTION to listOf("AR-+13", "AR-SAM13", "AR-SAM 13", "AR-R-13", "BR-A14", "CA-TV-14", "CL-14", "CO-15", "DK-12", "ES-13", "ES-14", "FR-14+", "GB-13+", "GB-14+", "GB-Teen", "GB-TEEN", "GR-K15", "GR-15", "HK-IIB", "ID-R", "ID-R13+", "ID-R-BO", "ID-Remaja", "IT-12+", "IT-VM12", "IT-VM10", "IT-VM 12", "JP-15A", "JP-15PG", "JP-C", "JP-15+", "KZ-Б14", "LT-N-13", "NO-11", "NO-15 år", "NO-11 år", "NZ-M", "PH-R-13", "PL-od 12 lat", "PT-12", "PT-14", "RO-15", "RO-N-15", "SE-14", "SE-11+", "SE-15+", "SE-15 År", "SE-Från 11 år", "SE-Från 15 år", "SK-15", "TR-13", "TW-PG", "TW-輔導級", "TW-輔12級", "TW-輔導12歲級", "TW-輔15級", "TW-輔導15歲級", "TW-12+", "TW-15+", "UA-12+", "T", "TV-14-D", "TV-14-L", "TV-14-S", "TV-14-V", "TV-14-DL", "TV-14-DS", "TV-14-DV", "TV-14-LS", "TV-14-LV", "TV-14-SV", "TV-14-DLS", "TV-14-DLV", "TV-14-DSV", "TV-14-LSV", "TV-14-DLSV", "CZ-15+", "FI-K12", "IE-15PG", "IT-VM 10", "IT-VM-10", "IT-VM-12", "IT-VM 14", "IT-VM-14", "MX-B-15", "AR-16", "M", "AT-10", "BR-14", "14", "BE-14", "14+", "CA-14+", "CA-13+", "CA-14A", "CH-10", "CN-PG-13", "CZ-15", "DK-11", "DK-F", "FI-12", "FI-K-12", "FR-12", "FR--12", "GR-K-15", "HU-12", "IN-UA-13", "IN-U/A 13+", "IN-UA 13+", "U/A 13+", "ID-13+", "IE-15A", "IE-15", "15A", "IL-14", "IT-10+", "IT-14+", "IT-VM14", "JP-R15+", "KR-15", "MY-13", "MX-B", "NL-12", "NL-14", "NZ-RP13", "NO-15", "PH-PG-13", "PL-12", "PT-M/12", "PT-M/14", "RU-12+", "ZA-10-12PG", "ES-12", "SE-11", "TH-15", "TR-10A", "TR-10+", "TR-12", "TR-13A", "TR-13+", "GB-15", "UK-15", "15", "PG-13", "TV-14", "VN-T13", "-12"),
			RatingBucket.MATURE to listOf("AR-+16", "AR-SAM16", "AR-SAM 16", "AU-AV 15+", "AU-MA", "AU-MA 15+", "AU-16+", "BG-D", "BR-A16", "DK-16", "GB-16", "GR-K-16", "GR-K16", "GR-16", "GR-K17", "JP-16+", "KZ-E16", "LT-N-16", "LV-16+", "NZ-GY", "PL-16", "PL-od 16 lat", "PT-16", "TH-13+", "TH-15+", "TR-16+", "TR-16", "UA-16+", "U/A 16+", "FI-K16", "MA15+", "AU-MA15+", "AT-14", "BE-12", "BR-16", "CA-16+", "CH-14", "FI-16", "FI-K-16", "FR-16", "FR--16", "GR-17", "GR-K-17", "HU-16", "IN-UA-16", "IN-U/A 16+", "IN-UA 16+", "ID-17+", "IE-16", "IL-16", "MY-16", "MX-B15", "NL-16", "NZ-R13", "NZ-RP16", "PH-R-16", "PL-15", "PT-M/16", "RU-16+", "SG-NC16", "NC16", "ZA-13", "ES-16", "SE-15", "TR-15", "VN-T16", "16+", "MA 15+", "16", "-16", "M16"),
			RatingBucket.RESTRICTED to listOf("A", "AR-+18", "AR-SAM18", "AR-SAM 18", "AR-R-17", "AT-18", "AU-18+", "AU-R 18+", "AU-R18", "AU-R", "BE-18", "BR-A18", "CA-NC-17", "CA-TV-MA", "CL-18", "CL-18V", "CL-18S", "CO-18", "GB-Caution", "GB-CAUTION", "GR-K-18", "GR-K18", "GR-18+", "ID-D", "ID-D18+", "ID-Dewasa", "IT-18+", "JP-D", "JP-18+", "JP-Z", "KR-18", "KZ-E18", "KZ-HA", "LT-N-18", "LV-18+", "MY-18SG", "MY-18SX", "MY-18PA", "MY-18PL", "NO-18 år", "PL-R", "PL-od 18 lat", "PT-18", "RO-18", "RO-IM-18", "RO-18+", "SE-18+", "SE-Barnförbjuden", "SE-Bfj", "SE-BFJ", "SK-18", "TH-18+", "TW-18+", "TW-限制級", "TW-限", "TW-輔限", "TW-R", "UA-18+", "TV-MA-L", "TV-MA-S", "TV-MA-V", "TV-MA-LS", "TV-MA-LV", "TV-MA-SV", "TV-MA-LSV", "R 18+", "CZ-18+", "FI-K18", "IT-VM 18", "IT-VM-18", "SE-18", "TR-18+", "AR-18", "AT-16", "BE-16", "BR-18", "18+", "CA-18+", "CA-18A", "CA-R", "CH-16", "CN-R", "CZ-18", "DK-15", "FI-18", "FI-K-18", "FR-18", "FR--18", "GR-18", "HU-18", "IN-A", "ID-21+", "IE-18", "18", "IL-18", "IT-VM18", "JP-R18+", "KR-19", "MY-18", "MX-C", "NL-18", "NZ-R", "NZ-R15", "NZ-R16", "NZ-RP18", "NO-18", "PH-R-18", "PL-18", "PT-M/18", "RU-18+", "SG-M18", "ZA-16", "ES-18", "TH-18", "TR-18", "GB-18", "UK-18", "VN-T18", "R", "TV-MA"),
			RatingBucket.ADULTS_ONLY to listOf("D", "AR-C", "AR-+18 C", "AR-SAM 18 C", "AR-SAM 18C", "AR-SAM18C", "AU-X 18+", "AU-X18", "AU-X", "BG-X", "CA-A", "CO-X", "ES-X", "GB-Mature", "GB-Adult", "GB-MATURE", "GB-ADULT", "HK-III", "HU-X", "KR-Restricted Screening", "NZ-GA", "NZ-MA", "PH-X", "PT-P", "RO-IM-18-XXX", "TH-20+", "ZA-X18", "ZA-XX", "TV-X", "TV-AO", "AO", "XX", "XXX", "XXXX", "AU-X 18", "AU-X18+", "X18+", "CH-18", "FR-X", "MX-D", "NZ-R18", "SG-R21", "ZA-18", "TH-20", "GB-R18", "UK-R18", "VN-C", "NC-17", "X"),
			RatingBucket.FSK_0 to listOf("FSK-0"),
			RatingBucket.FSK_6 to listOf("FSK-6"),
			RatingBucket.FSK_12 to listOf("FSK-12"),
			RatingBucket.FSK_16 to listOf("FSK-16"),
			RatingBucket.FSK_18 to listOf("FSK-18"),
			RatingBucket.EXEMPT to listOf("AU-M", "AU-E", "AU-Exempt", "NZ-Exempt", "EXEMPT", "EDUCATIONAL", "INFORMATIONAL"),
			RatingBucket.REFUSED to listOf("IC", "KK", "CA-Prohibited", "CA-PROHIBITED", "CO-Prohibited", "ES-Banned", "FI-KK", "NO-Not approved", "NZ-Objectionable", "NZ-OBJECTIONABLE", "RO-IC", "RU-Refused classification", "TH-Banned", "AU-RC", "RC", "AU-R18+", "R18+"),
			RatingBucket.SPECIAL to listOf("S", "IN-S"),
			RatingBucket.UNRATED to listOf("RP", "UNRATED", "NOT RATED", "NR", "UR", "NONE"),
	)
}
