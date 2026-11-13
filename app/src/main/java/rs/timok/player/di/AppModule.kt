package rs.timok.player.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import rs.timok.player.BuildConfig
import rs.timok.player.data.db.AppDatabase
import rs.timok.player.data.net.SecureDns
import rs.timok.player.data.remote.XtreamApi
import java.util.concurrent.TimeUnit
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    /** Identifiable User-Agent sent on API calls and streams, so the server can restrict access to this app. */
    @Provides
    @Named("userAgent")
    fun userAgent(): String = "TIMOK-Player/${BuildConfig.VERSION_NAME}"

    @Provides
    @Singleton
    fun json(): Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }

    @Provides
    @Singleton
    fun secureDns(): SecureDns = SecureDns()

    @Provides
    @Singleton
    fun okHttp(@Named("userAgent") userAgent: String, dns: SecureDns): OkHttpClient = OkHttpClient.Builder()
        .dns(dns)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .addInterceptor { chain ->
            val req = chain.request()
            val withUa = if (req.header("User-Agent") == null) {
                req.newBuilder().header("User-Agent", userAgent).build()
            } else req
            chain.proceed(withUa)
        }
        .build()

    @Provides
    @Singleton
    fun xtreamApi(client: OkHttpClient, json: Json): XtreamApi = Retrofit.Builder()
        // Placeholder; every call passes a full @Url.
        .baseUrl("http://localhost/")
        .client(client)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(XtreamApi::class.java)

    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "timok.db")
            // Pre-release: no migrations yet. Replace with real migrations before the first public build.
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()

    @Provides fun playlistDao(db: AppDatabase) = db.playlistDao()
    @Provides fun channelDao(db: AppDatabase) = db.channelDao()
    @Provides fun favoriteDao(db: AppDatabase) = db.favoriteDao()
    @Provides fun programDao(db: AppDatabase) = db.programDao()
    @Provides fun watchProgressDao(db: AppDatabase) = db.watchProgressDao()
    @Provides fun watchlistDao(db: AppDatabase) = db.watchlistDao()
}
