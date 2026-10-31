package app.vitaflow.com.reminders

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.vitaflow.com.MainActivity
import app.vitaflow.com.VitaFlowApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Shows the task notification, and moves a daily task to the next day. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as VitaFlowApp
        val container = app.container
        when (intent.action) {
            ReminderScheduler.ACTION_TASK -> {
                val id = intent.getLongExtra(ReminderScheduler.EXTRA_TASK_ID, -1)
                val title = intent.getStringExtra(ReminderScheduler.EXTRA_TITLE).orEmpty()
                show(context, id.toInt(), "টাস্কের সময় হয়েছে", title)
                val pending = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val task = container.db.taskDao().getById(id)
                        if (task != null && task.repeatRule == "DAILY" && task.dueAt != null) {
                            var next = task.dueAt
                            val day = 24L * 60 * 60 * 1000
                            while (next <= System.currentTimeMillis()) next += day
                            container.db.taskDao().update(task.copy(dueAt = next, done = false, doneAt = null))
                            container.reminders.schedule(task.id, task.title, next)
                        }
                    } finally {
                        pending.finish()
                    }
                }
            }
            ReminderScheduler.ACTION_BACKUP -> {
                val pending = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val last = container.settingsStore.lastExportAt.first()
                        val week = 7L * 24 * 60 * 60 * 1000
                        if (System.currentTimeMillis() - last > week) {
                            show(context, 900_001, "Data backup নেওয়ার সময়", "Settings > Export চেপে আপনার data ফোনে সংরক্ষণ করে রাখুন।")
                        }
                    } finally {
                        pending.finish()
                    }
                }
            }
        }
    }

    private fun show(context: Context, id: Int, title: String, text: String) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        ReminderScheduler(context).ensureChannel()
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(context, ReminderScheduler.CHANNEL)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        NotificationManagerCompat.from(context).notify(id, n)
    }
}
