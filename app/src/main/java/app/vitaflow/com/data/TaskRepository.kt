package app.vitaflow.com.data

import androidx.room.withTransaction
import app.vitaflow.com.data.db.ChangeRecordEntity
import app.vitaflow.com.data.db.TaskEntity
import app.vitaflow.com.data.db.VitaDatabase
import app.vitaflow.com.reminders.ReminderScheduler
import kotlinx.coroutines.flow.Flow

/** Result of a write: the row id and the id of the change record that describes it. */
data class Change(val entityId: Long, val changeId: Long)

/**
 * The one place that changes tasks. The user's screens and Max's Action Engine both call this,
 * so every change is written to change_records in the same database transaction.
 */
class TaskRepository(private val db: VitaDatabase, private val reminders: ReminderScheduler? = null) {
    private val tasks = db.taskDao()
    private val changes = db.changeDao()

    fun observe(accountId: Long): Flow<List<TaskEntity>> = tasks.observe(accountId)

    suspend fun listOnce(accountId: Long): List<TaskEntity> = tasks.allOnce(accountId)

    suspend fun create(
        accountId: Long,
        title: String,
        notes: String = "",
        dueAt: Long? = null,
        source: String = "USER",
        repeatRule: String? = null
    ): Change = db.withTransaction {
        val task = TaskEntity(
            accountId = accountId, title = title.trim(), notes = notes.trim(), dueAt = dueAt,
            repeatRule = repeatRule, source = source
        )
        val id = tasks.insert(task)
        val cid = changes.insert(
            ChangeRecordEntity(
                accountId = accountId, entity = "TASK", entityId = id, action = "CREATE",
                summary = "টাস্ক যোগ হয়েছে: ${task.title}",
                afterJson = task.copy(id = id).toJson(), source = source
            )
        )
        if (dueAt != null) reminders?.schedule(id, task.title, dueAt)
        Change(id, cid)
    }

    suspend fun update(task: TaskEntity, newTitle: String?, newDueAt: Long?, source: String = "USER"): Change =
        db.withTransaction {
            val updated = task.copy(title = newTitle?.trim() ?: task.title, dueAt = newDueAt ?: task.dueAt)
            tasks.update(updated)
            val cid = changes.insert(
                ChangeRecordEntity(
                    accountId = task.accountId, entity = "TASK", entityId = task.id, action = "UPDATE",
                    summary = "টাস্ক বদলানো হয়েছে: ${task.title}",
                    beforeJson = task.toJson(), afterJson = updated.toJson(), source = source
                )
            )
            reminders?.cancel(task.id)
            updated.dueAt?.let { reminders?.schedule(task.id, updated.title, it) }
            Change(task.id, cid)
        }

    suspend fun setDone(task: TaskEntity, done: Boolean, source: String = "USER"): Change = db.withTransaction {
        val updated = task.copy(done = done, doneAt = if (done) System.currentTimeMillis() else null)
        tasks.update(updated)
        val cid = changes.insert(
            ChangeRecordEntity(
                accountId = task.accountId, entity = "TASK", entityId = task.id, action = "UPDATE",
                summary = if (done) "টাস্ক সম্পন্ন: ${task.title}" else "টাস্ক আবার চালু: ${task.title}",
                beforeJson = task.toJson(), afterJson = updated.toJson(), source = source
            )
        )
        if (done && updated.repeatRule != "DAILY") reminders?.cancel(task.id)
        if (!done) updated.dueAt?.let { reminders?.schedule(task.id, updated.title, it) }
        Change(task.id, cid)
    }

    suspend fun delete(task: TaskEntity, source: String = "USER"): Change = db.withTransaction {
        tasks.delete(task)
        val cid = changes.insert(
            ChangeRecordEntity(
                accountId = task.accountId, entity = "TASK", entityId = task.id, action = "DELETE",
                summary = "টাস্ক মুছে ফেলা হয়েছে: ${task.title}",
                beforeJson = task.toJson(), source = source
            )
        )
        reminders?.cancel(task.id)
        Change(task.id, cid)
    }
}
