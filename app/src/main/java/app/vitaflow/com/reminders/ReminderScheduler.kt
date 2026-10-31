package app.vitaflow.com.reminders

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/** Schedules the notifications for tasks that have a time, and a weekly "take a backup" nudge. */
class ReminderScheduler(private val context: Context) {
    companion object {
        const val CHANNEL = "vitaflow_reminders"
        const val ACTION_TASK = "app.vitaflow.com.TASK_REMINDER"
        const val ACTION_BACKUP = "app.vitaflow.com.BACKUP_NUDGE"
        const val EXTRA_TASK_ID = "taskId"
        const val EXTRA_TITLE = "title"
        private const val BACKUP_CODE = 2_000_000_001.toInt()
    }

    private val alarms: AlarmManager = context.getSystemService(AlarmManager::class.java)

    fun ensureChannel() {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Vita Flow reminders", NotificationManager.IMPORTANCE_HIGH)
            )
        }
    }

    private fun taskIntent(taskId: Long, title: String): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java)
            .setAction(ACTION_TASK)
            .putExtra(EXTRA_TASK_ID, taskId)
            .putExtra(EXTRA_TITLE, title)
        return PendingIntent.getBroadcast(
            context, taskId.toInt(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun schedule(taskId: Long, title: String, atMillis: Long) {
        if (atMillis <= System.currentTimeMillis()) return
        val pi = taskIntent(taskId, title)
        if (alarms.canScheduleExactAlarms()) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi)
        } else {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi)
        }
    }

    fun cancel(taskId: Long) {
        alarms.cancel(taskIntent(taskId, ""))
    }

    /** Once a week: "take a backup". The receiver only shows it if the last export is more than a week old. */
    fun scheduleBackupNudge() {
        val intent = Intent(context, ReminderReceiver::class.java).setAction(ACTION_BACKUP)
        val pi = PendingIntent.getBroadcast(
            context, BACKUP_CODE, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val already = PendingIntent.getBroadcast(
            context, BACKUP_CODE, intent, PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        if (already != null) return
        val week = 7L * 24 * 60 * 60 * 1000
        alarms.setInexactRepeating(AlarmManager.RTC, System.currentTimeMillis() + week, week, pi)
    }
}
