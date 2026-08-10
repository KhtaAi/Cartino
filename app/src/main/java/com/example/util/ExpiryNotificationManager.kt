package com.example.util

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity

object ExpiryNotificationManager {

    private const val CHANNEL_ID = "cartino_expiry_channel"
    private const val CHANNEL_NAME = "هشدارهای انقضا"
    private const val NOTIFICATION_ID = 2001
    private const val PREFS_NAME = "cartino_expiry_prefs"
    private const val KEY_LAST_NOTIF_DATE = "last_notification_date"

    fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "اعلان‌های یادآوری انقضای کارت‌ها و مدارک"
            }
            val notificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            notificationManager?.createNotificationChannel(channel)
        }
    }

    fun sendExpirySummaryNotification(
        context: Context,
        dueItems: List<ExpiringItem>,
        force: Boolean = false
    ): Boolean {
        if (dueItems.isEmpty()) return false

        val todayStr = JalaliCalendarHelper.getCurrentJalaliDate().toString()
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastNotifDate = prefs.getString(KEY_LAST_NOTIF_DATE, null)

        if (!force && lastNotifDate == todayStr) {
            return false
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                return false
            }
        }
        val managerCompat = NotificationManagerCompat.from(context)
        if (!managerCompat.areNotificationsEnabled()) {
            return false
        }

        try {
            createNotificationChannel(context)

            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra("from_expiry_notification", true)
                putExtra("notification_item_ids", dueItems.map { it.id }.toTypedArray())
            }

            val pendingIntent = PendingIntent.getActivity(
                context,
                1001,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val title = "هشدار انقضای کارتینو"
            val summaryText = "${dueItems.size} مورد نیاز به تمدید یا توجه دارند"

            val inboxStyle = NotificationCompat.InboxStyle()
                .setBigContentTitle(title)
                .setSummaryText(summaryText)

            dueItems.take(5).forEach { item ->
                inboxStyle.addLine("${item.title} — ${item.detail}")
            }

            val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(context.applicationInfo.icon)
                .setContentTitle(title)
                .setContentText(summaryText)
                .setStyle(inboxStyle)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)

            managerCompat.notify(NOTIFICATION_ID, builder.build())

            prefs.edit().putString(KEY_LAST_NOTIF_DATE, todayStr).apply()
            return true
        } catch (e: Throwable) {
            SyncLogger.log("EXPIRY_NOTIF", "خطا در ارسال نوتیفیکیشن: ${e.message}")
            return false
        }
    }
}
