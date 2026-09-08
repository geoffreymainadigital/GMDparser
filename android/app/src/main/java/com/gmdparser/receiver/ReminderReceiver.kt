package com.gmdparser.receiver

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.gmdparser.data.repository.TransactionRepository
import com.gmdparser.ui.MainActivity
import com.gmdparser.util.ReviewReminderManager

class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null) return

        if (intent?.action == Intent.ACTION_BOOT_COMPLETED) {
            ReviewReminderManager.rescheduleFromBoot(context)
            return
        }

        // Initialize repository from persistent storage if not already loaded
        TransactionRepository.init(context)

        val pendingCount = TransactionRepository.pendingTransactions.value.size
        if (pendingCount > 0) {
            showReminderNotification(context, pendingCount)
        }
    }

    private fun showReminderNotification(context: Context, count: Int) {
        val channelId = "gmdparser_reminders"
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Review Reminders",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Periodic reminders to review and confirm pending M-PESA transactions"
            }
            notificationManager.createNotificationChannel(channel)
        }

        val tapIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            9001,
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Pending M-PESA Reviews")
            .setContentText("$count transaction${if (count > 1) "s" else ""} waiting for your confirmation.")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        notificationManager.notify(9001, notification)
    }
}
