package niel.kro.penik.data.network

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import niel.kro.penik.data.network.api.ApiService
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages client-server clock offset calibration.
 * Allows outgoing messages and presence to use synchronized server timestamps
 * even if device system clock is skewed.
 */
@Singleton
class TimeSyncManager @Inject constructor(
    private val apiService: ApiService
) {
    companion object {
        private val globalOffsetMs = AtomicLong(0L)
        @Volatile private var globalSynced = false

        /**
         * Returns estimated current server time in milliseconds.
         */
        fun currentTimeMs(): Long {
            return System.currentTimeMillis() + globalOffsetMs.get()
        }

        /**
         * Returns estimated current server time in seconds.
         */
        fun currentTimeSec(): Long {
            return currentTimeMs() / 1000
        }

        fun getOffsetMs(): Long = globalOffsetMs.get()
        fun isSynced(): Boolean = globalSynced
    }

    /**
     * Queries /api/v1/time and updates the clock offset taking half-RTT network latency into account.
     */
    suspend fun syncTime(): Boolean = withContext(Dispatchers.IO) {
        try {
            val t0 = System.currentTimeMillis()
            val resp = apiService.getServerTime()
            val t1 = System.currentTimeMillis()
            if (resp.isSuccessful) {
                val body = resp.body()
                if (body != null) {
                    val latency = (t1 - t0) / 2
                    val serverEstimatedMs = body.serverTimeMs + latency
                    val offset = serverEstimatedMs - t1
                    globalOffsetMs.set(offset)
                    globalSynced = true
                    Log.d("TimeSync", "Server time synced: offset=${offset}ms, latency=${latency}ms")
                    return@withContext true
                }
            }
        } catch (e: Exception) {
            Log.w("TimeSync", "Failed to sync server time: ${e.message}")
        }
        false
    }
}
