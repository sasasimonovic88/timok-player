package rs.timok.player.domain

enum class VodKind { MOVIE, SERIES }

/** A movie or a series as shown in the poster grid. */
data class VodItem(
    val kind: VodKind,
    val id: Long,
    val name: String,
    val poster: String?,
    val rating: String?,
    val year: String?,
    /** Container extension for movies (mp4, mkv...); null for series. */
    val extension: String?,
    val categoryId: String? = null,
    /** When the item was added to the server (epoch seconds), for "recently added" sorting. */
    val added: Long? = null,
) {
    /** Rating as a number (comma or dot decimals); null when the server gives none. */
    val ratingValue: Double? get() = rating?.replace(',', '.')?.toDoubleOrNull()?.takeIf { it > 0 }
}

/** Order of the movie/series grid. The server has no view counts, so popularity is not an option. */
enum class VodSort { DEFAULT, ADDED, RATING, YEAR, NAME }

data class VodDetails(
    val kind: VodKind,
    val id: Long,
    val name: String,
    val poster: String?,
    val backdrop: String?,
    val plot: String?,
    val genre: String?,
    val cast: String?,
    val director: String?,
    val year: String?,
    val rating: String?,
    val durationSecs: Int?,
    val extension: String?,
    val seasons: List<Season> = emptyList(),
)

data class Season(val number: Int, val name: String, val episodes: List<Episode>)

data class Episode(
    val id: Long,
    val season: Int,
    val number: Int,
    val title: String,
    val plot: String?,
    val image: String?,
    val durationSecs: Int?,
    val extension: String,
)
