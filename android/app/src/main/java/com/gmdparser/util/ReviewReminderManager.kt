package com.gmdparser.util

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.gmdparser.receiver.ReminderReceiver
import java.util.Calendar

enum class ReminderCadence(val label: String) {
    OFF("Off"),
    DAILY("Daily"),
    WEEKLY("Weekly"),
    MONTHLY("Monthly")
}

object ReviewReminderManager {
    private const val PREFS_NAME = "gmdparser_reminder_prefs"
    private const val KEY_CADENCE = "review_reminder_cadence"
    private const val REQUEST_CODE = 8800

    fun getCadence(context: Context): ReminderCadence {
        val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val name = sp.getString(KEY_CADENCE, ReminderCadence.DAILY.name) ?: ReminderCadence.DAILY.name
        return try {
            ReminderCadence.valueOf(name)
        } catch (_: Exception) {
            ReminderCadence.DAILY
        }
    }

    fun setCadence(context: Context, cadence: ReminderCadence) {
        val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        sp.edit().putString(KEY_CADENCE, cadence.name).apply()
        schedule(context, cadence)
    }

    fun rescheduleFromBoot(context: Context) {
        val cadence = getCadence(context)
        if (cadence != ReminderCadence.OFF) {
            schedule(context, cadence)
        }
    }

    private fun schedule(context: Context, cadence: ReminderCadence) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val intent = Intent(context, ReminderReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        alarmManager.cancel(pendingIntent)

        if (cadence == ReminderCadence.OFF) return

        val intervalMillis = when (cadence) {
            ReminderCadence.DAILY -> AlarmManager.INTERVAL_DAY
            ReminderCadence.WEEKLY -> AlarmManager.INTERVAL_DAY * 7L
            ReminderCadence.MONTHLY -> AlarmManager.INTERVAL_DAY * 30L
            ReminderCadence.OFF -> return
        }

        val calendar = Calendar.getInstance().apply {
            timeInMillis = System.currentTimeMillis()
            set(Calendar.HOUR_OF_DAY, 20)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) {
                add(Calendar.DAY_OF_YEAR, 1)
            }
        }

        alarmManager.setInexactRepeating(
            AlarmManager.RTC_WAKEUP,
            calendar.timeInMillis,
            intervalMillis,
            pendingIntent
        )
    }
}
