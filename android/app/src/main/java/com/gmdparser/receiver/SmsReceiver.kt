package com.gmdparser.receiver

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Telephony
import androidx.core.app.NotificationCompat
import com.gmdparser.data.repository.TransactionRepository
import com.gmdparser.parser.MpesaParser
import com.gmdparser.ui.MainActivity

class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        try {
            val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
            val fullBody = buildString {
                for (sms in messages) {
                    if (sms != null && sms.messageBody != null) {
                        append(sms.messageBody)
                    }
                }
            }

            if (fullBody.isBlank()) return

            // Parse using deterministic M-PESA parser
            val transaction = MpesaParser.parse(fullBody) ?: return
            if (transaction.transactionCode.isBlank()) return

            // Add to pending review repository
            TransactionRepository.addPendingTransaction(transaction)

            // Notify user for mandatory review and explicit confirmation
            showConfirmationNotification(context, transaction.transactionCode, transaction.amount, transaction.description)

        } catch (e: Exception) {
            // Fail safely without crashing receiver
            e.printStackTrace()
        }
    }

    private fun showConfirmationNotification(context: Context, code: String, amount: Double, desc: String) {
        val channelId = "gmdparser_confirmations"
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Transaction Reviews",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Alerts for user confirmation of captured M-PESA transactions"
            }
            notificationManager.createNotificationChannel(channel)
        }

        val tapIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("OPEN_REVIEW_CODE", code)
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            code.hashCode(),
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("M-PESA: Review Ksh ${String.format("%.2f", amount)}")
            .setContentText("$desc - Tap to verify and confirm recording.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        notificationManager.notify(code.hashCode(), notification)
    }
}
