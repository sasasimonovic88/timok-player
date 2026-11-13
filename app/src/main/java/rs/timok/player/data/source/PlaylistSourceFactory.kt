package rs.timok.player.data.source

import rs.timok.player.data.db.PlaylistEntity
import rs.timok.player.data.remote.XtreamApi
import javax.inject.Inject
import kotlinx.serialization.json.Json
import javax.inject.Singleton

@Singleton
class PlaylistSourceFactory @Inject constructor(private val api: XtreamApi, private val json: Json) {
    fun xtream(server: String, username: String, password: String): PlaylistSource =
        XtreamSource(api, json, server, username, password)

    fun create(playlist: PlaylistEntity): PlaylistSource =
        xtream(playlist.server, playlist.username, playlist.password)
}
