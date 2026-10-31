package app.vitaflow.com.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.vitaflow.com.VitaFlowApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** After a phone restart all alarms are gone, so schedule them again. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val container = (context.applicationContext as VitaFlowApp).container
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val now = System.currentTimeMillis()
                val day = 24L * 60 * 60 * 1000
                for (t in container.db.taskDao().allWithDue()) {
                    var due = t.dueAt ?: continue
                    if (t.repeatRule == "DAILY") {
                        while (due <= now) due += day
                        if (due != t.dueAt) container.db.taskDao().update(t.copy(dueAt = due, done = false, doneAt = null))
                        container.reminders.schedule(t.id, t.title, due)
                    } else if (!t.done && due > now) {
                        container.reminders.schedule(t.id, t.title, due)
                    }
                }
                container.reminders.scheduleBackupNudge()
            } finally {
                pending.finish()
            }
        }
    }
}
