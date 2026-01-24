package com.krishna.netspeedlite

import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.net.ConnectivityManager
import java.util.Calendar

object NetworkUsageHelper {

    // 🛡️ Sanity Limit: If a single bucket claims > 100 TB, it's a glitch.
    private const val SANITY_THRESHOLD = 100L * 1024 * 1024 * 1024 * 1024

    fun getUsageForDate(context: Context, timestamp: Long): Pair<Long, Long> {
        val statsManager = context.getSystemService(Context.NETWORK_STATS_SERVICE) as? NetworkStatsManager
            ?: return Pair(0L, 0L)

        // 1. Calculate Start and End of the given day
        val calendar = Calendar.getInstance()
        calendar.timeInMillis = timestamp
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        val startTime = calendar.timeInMillis

        calendar.set(Calendar.HOUR_OF_DAY, 23)
        calendar.set(Calendar.MINUTE, 59)
        calendar.set(Calendar.SECOND, 59)
        calendar.set(Calendar.MILLISECOND, 999)
        val endTime = calendar.timeInMillis

        // 3. Get Data
        // 3. Get Data (Suppress deprecation for TYPE_MOBILE/WIFI as NetworkStatsManager still uses them)
        @Suppress("DEPRECATION")
        val mobile = getSafeUsage(statsManager, ConnectivityManager.TYPE_MOBILE, startTime, endTime)
        @Suppress("DEPRECATION")
        val wifi = getSafeUsage(statsManager, ConnectivityManager.TYPE_WIFI, startTime, endTime)

        return Pair(mobile, wifi)
    }

    /**
     * Generic method to get usage for a specific time range.
     * Useful for custom ranges (e.g., in MainActivity).
     */
    fun getUsageInRange(context: Context, startTime: Long, endTime: Long): Pair<Long, Long> {
        val statsManager = context.getSystemService(Context.NETWORK_STATS_SERVICE) as? NetworkStatsManager
            ?: return Pair(0L, 0L)

        @Suppress("DEPRECATION")
        val mobile = getSafeUsage(statsManager, ConnectivityManager.TYPE_MOBILE, startTime, endTime)
        @Suppress("DEPRECATION")
        val wifi = getSafeUsage(statsManager, ConnectivityManager.TYPE_WIFI, startTime, endTime)

        return Pair(mobile, wifi)
    }

    private fun getSafeUsage(manager: NetworkStatsManager, networkType: Int, start: Long, end: Long): Long {
        var totalBytes = 0L
        var networkStats: NetworkStats? = null
        try {
            // Use querySummary to iterate over buckets
            networkStats = manager.querySummary(networkType, null, start, end)

            if (networkStats == null) {
                android.util.Log.w("NetworkUsageHelper", "querySummary returned null for networkType: $networkType")
                return 0L
            }

            val bucket = NetworkStats.Bucket()

            while (networkStats.hasNextBucket()) {
                networkStats.getNextBucket(bucket)

                // 🛡️ CRITICAL FIX: Strictly filter buckets by time.
                // NetworkStatsManager might return buckets that *overlap* the start time.
                // If a bucket started BEFORE our 'resetTimestamp' (which is passed as 'start'),
                // we must IGNORE it, otherwise the user sees old data immediately after reset.
                val bucketStart = bucket.startTimeStamp
                val bucketEnd = bucket.endTimeStamp
                val totalBytesInBucket = bucket.rxBytes + bucket.txBytes

                // Filter out irrelevant buckets (shouldn't happen with querySummary but safe to keep)
                if (bucketEnd <= start || bucketStart >= end) continue

                // Calculate overlap
                val overlapStart = if (bucketStart < start) start else bucketStart
                val overlapEnd = if (bucketEnd > end) end else bucketEnd

                if (overlapEnd <= overlapStart) continue

                // Interpolate bytes based on overlap duration
                // This handles cases where a bucket spans across midnight (e.g. 23:00 - 01:00)
                val bucketDuration = bucketEnd - bucketStart
                val overlapDuration = overlapEnd - overlapStart

                val bytesToAdd = if (bucketDuration > 0) {
                    val ratio = overlapDuration.toDouble() / bucketDuration.toDouble()
                    (totalBytesInBucket * ratio).toLong()
                } else {
                    totalBytesInBucket
                }

                // Filter out negative values or impossible spikes
                if (bytesToAdd < 0) continue
                if (bytesToAdd > SANITY_THRESHOLD) continue

                totalBytes += bytesToAdd
            }
        } catch (e: Exception) {
            android.util.Log.e("NetworkUsageHelper", "Error getting safe usage", e)
        } finally {
            networkStats?.close()
        }
        return totalBytes
    }
}