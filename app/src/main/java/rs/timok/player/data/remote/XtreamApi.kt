package rs.timok.player.data.remote

import kotlinx.serialization.json.JsonElement
import okhttp3.ResponseBody
import retrofit2.http.Streaming
import retrofit2.http.QueryMap
import retrofit2.http.GET
import retrofit2.http.Query
import retrofit2.http.Url

/** Xtream Codes player API. Every call takes the full `player_api.php` URL since the server is per playlist. */
interface XtreamApi {
    @GET
    suspend fun authenticate(
        @Url url: String,
        @Query("username") username: String,
        @Query("password") password: String,
    ): XtreamAuthResponse

    @GET
    suspend fun liveCategories(
        @Url url: String,
        @Query("username") username: String,
        @Query("password") password: String,
        @Query("action") action: String = "get_live_categories",
    ): List<XtreamCategoryDto>

    @GET
    suspend fun liveStreams(
        @Url url: String,
        @Query("username") username: String,
        @Query("password") password: String,
        @Query("action") action: String = "get_live_streams",
    ): List<XtreamLiveStreamDto>

    /** Any player_api action; VOD responses are too irregular for typed DTOs, so they are parsed by hand. */
    @GET
    suspend fun action(
        @Url url: String,
        @Query("username") username: String,
        @Query("password") password: String,
        @Query("action") action: String,
        @QueryMap extra: Map<String, String> = emptyMap(),
    ): JsonElement

    /** Same as [action] but unparsed, for lists too big to hold as one JSON tree (all movies of a server). */
    @Streaming
    @GET
    suspend fun actionStream(
        @Url url: String,
        @Query("username") username: String,
        @Query("password") password: String,
        @Query("action") action: String,
        @QueryMap extra: Map<String, String> = emptyMap(),
    ): ResponseBody
}
