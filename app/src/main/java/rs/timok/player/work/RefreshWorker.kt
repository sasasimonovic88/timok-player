package rs.timok.player.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import rs.timok.player.data.repo.PlaylistRepository
import java.util.concurrent.TimeUnit

/** Keeps the channel list and the guide fresh while the app stays open for days on a TV box. */
@HiltWorker
class RefreshWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val playlists: PlaylistRepository,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        playlists.refreshIfStale()
        return Result.success()
    }

    companion object {
        private const val NAME = "auto-refresh"

        /** Safe to call on every start: an already scheduled run keeps its timing. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<RefreshWorker>(12, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
