package rs.timok.player.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class XtreamAuthResponse(
    @SerialName("user_info") val userInfo: XtreamUserInfo? = null,
    @SerialName("server_info") val serverInfo: XtreamServerInfo? = null,
)

@Serializable
data class XtreamUserInfo(
    @Serializable(LenientStringSerializer::class) val auth: String? = null,
    @Serializable(LenientStringSerializer::class) val status: String? = null,
    @SerialName("exp_date") @Serializable(LenientStringSerializer::class) val expDate: String? = null,
    @SerialName("max_connections") @Serializable(LenientStringSerializer::class) val maxConnections: String? = null,
    @SerialName("active_cons") @Serializable(LenientStringSerializer::class) val activeConnections: String? = null,
)

@Serializable
data class XtreamServerInfo(
    @Serializable(LenientStringSerializer::class) val url: String? = null,
    @Serializable(LenientStringSerializer::class) val port: String? = null,
    @SerialName("https_port") @Serializable(LenientStringSerializer::class) val httpsPort: String? = null,
    @SerialName("server_protocol") @Serializable(LenientStringSerializer::class) val protocol: String? = null,
    @Serializable(LenientStringSerializer::class) val timezone: String? = null,
)

@Serializable
data class XtreamCategoryDto(
    @SerialName("category_id") @Serializable(LenientStringSerializer::class) val categoryId: String? = null,
    @SerialName("category_name") @Serializable(LenientStringSerializer::class) val categoryName: String? = null,
)

@Serializable
data class XtreamLiveStreamDto(
    @Serializable(LenientStringSerializer::class) val num: String? = null,
    @Serializable(LenientStringSerializer::class) val name: String? = null,
    @SerialName("stream_id") @Serializable(LenientStringSerializer::class) val streamId: String? = null,
    @SerialName("stream_icon") @Serializable(LenientStringSerializer::class) val streamIcon: String? = null,
    @SerialName("epg_channel_id") @Serializable(LenientStringSerializer::class) val epgChannelId: String? = null,
    @SerialName("category_id") @Serializable(LenientStringSerializer::class) val categoryId: String? = null,
    @SerialName("tv_archive") @Serializable(LenientStringSerializer::class) val tvArchive: String? = null,
    @SerialName("tv_archive_duration") @Serializable(LenientStringSerializer::class) val tvArchiveDuration: String? = null,
)
