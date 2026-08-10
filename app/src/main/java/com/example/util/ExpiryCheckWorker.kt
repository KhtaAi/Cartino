package com.example.util

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.data.local.CartinoDatabase
import kotlinx.coroutines.flow.first

class ExpiryCheckWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            val db = CartinoDatabase.getDatabase(applicationContext)
            val cards = db.bankCardDao().getAllCards().first().map { it.decrypted() }
            val docs = db.identityDocumentDao().getAllDocuments().first().map { it.decrypted() }

            val dueItems = ExpiryReminderManager.getDueExpiringItems(cards, docs, applicationContext)
            if (dueItems.isNotEmpty()) {
                ExpiryNotificationManager.sendExpirySummaryNotification(applicationContext, dueItems)
            }
            Result.success()
        } catch (e: Throwable) {
            SyncLogger.log("EXPIRY_WORKER", "خطا در کارگر پس‌زمینه بررسی انقضا: ${e.message}")
            Result.retry()
        }
    }
}
