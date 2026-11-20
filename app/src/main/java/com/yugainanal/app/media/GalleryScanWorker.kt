package com.yugainanal.app.media

import android.content.Context
import android.os.SystemClock
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.await
import androidx.work.workDataOf
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

/**
 * Periodic workers enqueue a bounded one-time scan. Each successful batch writes
 * decisions before scheduling the next batch, so later attempts leave them alone.
 */
class GalleryScanWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val childId = inputData.getLong(CHILD_ID, 0L)
        if (childId <= 0L) return@withContext Result.failure()

        val index = GalleryIndex(applicationContext)
        val profiles = ChildFaceProfileStore(applicationContext)
        val automaticOnly = inputData.getBoolean(AUTOMATIC_ONLY, false)
        if (automaticOnly && !index.automaticEnabled(childId)) return@withContext Result.success()
        val profile = profiles.load(childId) ?: return@withContext Result.success()
        if (GalleryAccess.current(applicationContext) == GalleryAccess.NONE) {
            index.updateStatus(childId, profile.revision, GalleryScanStatus.NO_PERMISSION)
            return@withContext Result.success()
        }

        if (inputData.getBoolean(PERIODIC, false)) {
            return@withContext try {
                requestBatch(applicationContext, childId, true, ExistingWorkPolicy.KEEP)
                Result.success()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                Result.retry()
            }
        }

        if (!scannerLock.tryLock()) return@withContext Result.retry()
        index.setRunning(childId, true)
        try {
            runBatch(index, profiles, childId, profile, automaticOnly)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: SecurityException) {
            index.updateStatus(childId, profile.revision, GalleryScanStatus.NO_PERMISSION)
            Result.success()
        } catch (_: Exception) {
            index.updateStatus(childId, profile.revision, GalleryScanStatus.ERROR)
            if (runAttemptCount >= 2) Result.failure() else Result.retry()
        } finally {
            index.setRunning(childId, false)
            scannerLock.unlock()
        }
    }

    private suspend fun runBatch(
        index: GalleryIndex,
        profiles: ChildFaceProfileStore,
        childId: Long,
        profile: ChildFaceProfile,
        automaticOnly: Boolean,
    ): Result {
        val context = applicationContext
        val revision = profile.revision
        index.updateStatus(childId, revision, GalleryScanStatus.PENDING)
        val batch = index.nextBatch(childId, revision, ITEMS_PER_BATCH)
        if (batch.items.isEmpty()) {
            index.updateStatus(childId, revision, GalleryScanStatus.COMPLETE, analyzed = 0)
            return Result.success()
        }

        var analyzed = 0
        var remaining = batch.more
        var denied = false
        val started = SystemClock.elapsedRealtime()
        ChildFaceMatcher(context).use { matcher ->
            for (item in batch.items) {
                currentCoroutineContext().ensureActive()
                if (profiles.load(childId)?.revision != revision) return Result.success()
                if (automaticOnly && !index.automaticEnabled(childId)) {
                    index.updateStatus(childId, revision, GalleryScanStatus.IDLE, analyzed)
                    return Result.success()
                }
                if (SystemClock.elapsedRealtime() - started >= BATCH_BUDGET_MILLIS) {
                    remaining = true
                    break
                }
                val media = item.media
                if (GalleryAccess.mediaAccess(context, media.kind) == GalleryAccess.NONE) {
                    denied = true
                    continue
                }
                val decision = try {
                    analyzed++
                    when (matcher.classify(media.uri, media.kind == GalleryMediaKind.VIDEO, profile)) {
                        GalleryMatch.MATCH -> IndexedDecision.MATCH
                        GalleryMatch.NO_MATCH -> IndexedDecision.NO_MATCH
                        GalleryMatch.REVIEW -> IndexedDecision.REVIEW
                    }
                } catch (_: SecurityException) {
                    denied = true
                    continue
                } catch (_: IOException) {
                    IndexedDecision.UNREADABLE
                }
                val embeddedDate = if (media.dateEstimated && decision in setOf(IndexedDecision.MATCH, IndexedDecision.REVIEW)) {
                    embeddedGalleryCaptureDate(context, media)
                } else null
                currentCoroutineContext().ensureActive()
                val datedItem = if (embeddedDate == null) item
                else item.copy(media = media.copy(date = embeddedDate, dateEstimated = false))
                index.remember(datedItem, revision, decision)
            }
        }
        currentCoroutineContext().ensureActive()
        if (profiles.load(childId)?.revision != revision) return Result.success()
        if (automaticOnly && !index.automaticEnabled(childId)) {
            index.updateStatus(childId, revision, GalleryScanStatus.IDLE, analyzed)
            return Result.success()
        }
        val status = when {
            GalleryAccess.current(context) == GalleryAccess.NONE -> GalleryScanStatus.NO_PERMISSION
            denied -> GalleryScanStatus.ERROR
            remaining -> GalleryScanStatus.PENDING
            else -> GalleryScanStatus.COMPLETE
        }
        if (status == GalleryScanStatus.PENDING) {
            requestBatch(context, childId, automaticOnly, ExistingWorkPolicy.APPEND_OR_REPLACE)
        }
        index.updateStatus(childId, revision, status, analyzed)
        return Result.success()
    }

    companion object {
        private const val CHILD_ID = "child_id"
        private const val PERIODIC = "periodic"
        private const val AUTOMATIC_ONLY = "automatic_only"
        private const val ITEMS_PER_BATCH = 60
        private const val BATCH_BUDGET_MILLIS = 90_000L
        private val scannerLock = Mutex()
        private val backgroundConstraints = Constraints.Builder()
            .setRequiresBatteryNotLow(true)
            .build()

        suspend fun enqueueNow(context: Context, childId: Long) {
            requestBatch(context, childId, false, ExistingWorkPolicy.APPEND_OR_REPLACE)
        }

        suspend fun schedule(context: Context, childId: Long) {
            if (!GalleryIndex(context).automaticEnabled(childId)) return
            val request = PeriodicWorkRequestBuilder<GalleryScanWorker>(24, TimeUnit.HOURS)
                .setInitialDelay(24, TimeUnit.HOURS)
                .setConstraints(backgroundConstraints)
                .setInputData(workDataOf(CHILD_ID to childId, PERIODIC to true, AUTOMATIC_ONLY to true))
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                periodicName(childId), ExistingPeriodicWorkPolicy.KEEP, request,
            ).await()
        }

        suspend fun setAutomatic(context: Context, childId: Long, enabled: Boolean) {
            GalleryIndex(context).setAutomatic(childId, enabled)
            if (enabled) {
                schedule(context, childId)
                requestBatch(context, childId, true, ExistingWorkPolicy.KEEP)
            } else {
                WorkManager.getInstance(context).cancelUniqueWork(periodicName(childId)).await()
            }
        }

        suspend fun cancel(context: Context, childId: Long) {
            val work = WorkManager.getInstance(context)
            work.cancelUniqueWork(periodicName(childId)).await()
            work.cancelUniqueWork(batchName(childId)).await()
        }

        private suspend fun requestBatch(
            context: Context,
            childId: Long,
            automaticOnly: Boolean,
            policy: ExistingWorkPolicy,
        ) {
            require(childId > 0L)
            val request = OneTimeWorkRequestBuilder<GalleryScanWorker>()
                .setConstraints(if (automaticOnly) backgroundConstraints else Constraints.NONE)
                .setBackoffCriteria(BackoffPolicy.LINEAR, 15, TimeUnit.SECONDS)
                .setInputData(workDataOf(CHILD_ID to childId, AUTOMATIC_ONLY to automaticOnly))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(batchName(childId), policy, request).await()
        }

        private fun periodicName(childId: Long) = "gallery-periodic-$childId"
        private fun batchName(childId: Long) = "gallery-batch-$childId"
    }
}
