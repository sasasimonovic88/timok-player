package rs.timok.player.domain

/** Account details returned by the provider at sign-in. */
data class AccountInfo(
    val status: String,
    /** Epoch millis, null when the account never expires. */
    val expiresAt: Long?,
    val maxConnections: Int,
)

data class SourceCategory(val id: String, val name: String, val order: Int)

data class SourceChannel(
    val streamId: Long,
    val number: Int,
    val name: String,
    val logo: String?,
    val categoryId: String?,
    val epgChannelId: String?,
    val hasArchive: Boolean,
    val order: Int,
)

enum class StreamFormat(val extension: String) { HLS("m3u8"), TS("ts") }

/** Typed failures that map 1:1 to user-facing messages. */
sealed class SourceException(message: String) : Exception(message) {
    class InvalidCredentials : SourceException("invalid credentials")
    class AccountExpired : SourceException("account expired")
    class AccountDisabled(val status: String) : SourceException("account status $status")
    class NoNetwork : SourceException("no network")
    class ServerUnreachable(cause: Throwable?) : SourceException("server unreachable: ${cause?.message}")
    class BadResponse(cause: Throwable?) : SourceException("bad response: ${cause?.message}")
    class EmptyList : SourceException("empty channel list")
}

/** A past programme that can be replayed from the channel archive (catch-up). */
data class ArchiveProgram(
    val title: String,
    val description: String?,
    val startMs: Long,
    val endMs: Long,
    /** Start as the server writes it in its own time zone ("yyyy-MM-dd HH:mm:ss"); timeshift URLs need exactly this. */
    val serverStart: String,
)
