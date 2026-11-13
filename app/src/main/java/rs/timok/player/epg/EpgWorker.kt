package rs.timok.player.epg

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

@HiltWorker
class EpgWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val epg: EpgRepository,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val playlistId = inputData.getLong(KEY_PLAYLIST, -1)
        if (playlistId <= 0) return Result.failure()
        return try {
            val count = epg.refresh(playlistId)
            Log.i(TAG, "EPG imported: $count programmes")
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "EPG import failed", e)
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    companion object {
        private const val TAG = "EpgWorker"
        private const val KEY_PLAYLIST = "playlist"

        fun enqueue(context: Context, playlistId: Long, force: Boolean) {
            val request = OneTimeWorkRequestBuilder<EpgWorker>()
                .setInputData(workDataOf(KEY_PLAYLIST to playlistId))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "epg-$playlistId",
                if (force) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
                request,
            )
        }
    }
}
