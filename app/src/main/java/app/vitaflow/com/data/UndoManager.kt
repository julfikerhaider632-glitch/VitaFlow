package app.vitaflow.com.data

import androidx.room.withTransaction
import app.vitaflow.com.data.db.ChangeRecordEntity
import app.vitaflow.com.data.db.MealEntity
import app.vitaflow.com.data.db.TaskEntity
import app.vitaflow.com.data.db.VitaDatabase
import app.vitaflow.com.data.db.WorkoutExerciseEntity
import app.vitaflow.com.reminders.ReminderScheduler
import org.json.JSONObject

/** Turns a recorded change back. It works from the before/after values saved in change_records. */
class UndoManager(private val db: VitaDatabase, private val reminders: ReminderScheduler?) {
    private fun JSONObject.str(k: String): String? = if (has(k) && !isNull(k)) optString(k) else null
    private fun JSONObject.lng(k: String): Long? = if (has(k) && !isNull(k)) optLong(k) else null
    private fun JSONObject.int(k: String): Int? = if (has(k) && !isNull(k)) optInt(k) else null
    private fun JSONObject.dbl(k: String): Double? = if (has(k) && !isNull(k)) optDouble(k) else null

    /** Returns a message for the user. */
    suspend fun undo(r: ChangeRecordEntity): String {
        if (r.undone) return "এটা আগেই ফিরিয়ে নেওয়া হয়েছে।"
        val before = r.beforeJson?.let { runCatching { JSONObject(it) }.getOrNull() }
        val message = db.withTransaction {
            val msg = when (r.entity to r.action) {
                "TASK" to "CREATE" -> {
                    db.taskDao().deleteById(r.entityId); reminders?.cancel(r.entityId); "টাস্কটা সরিয়ে দিয়েছি।"
                }
                "TASK" to "UPDATE" -> {
                    val t = db.taskDao().getById(r.entityId)
                    if (t == null || before == null) "টাস্কটা আর পাওয়া যায় না।" else {
                        val back = t.copy(
                            title = before.str("title") ?: t.title, notes = before.str("notes") ?: t.notes,
                            done = before.optBoolean("done", t.done), doneAt = before.lng("doneAt"),
                            dueAt = before.lng("dueAt"), repeatRule = before.str("repeatRule")
                        )
                        db.taskDao().update(back)
                        reminders?.cancel(back.id)
                        if (!back.done) back.dueAt?.let { reminders?.schedule(back.id, back.title, it) }
                        "টাস্ক আগের অবস্থায় ফিরিয়ে দিয়েছি।"
                    }
                }
                "TASK" to "DELETE" -> {
                    if (before == null) "ফেরানো গেল না." else {
                        val t = TaskEntity(
                            accountId = r.accountId, title = before.str("title") ?: "টাস্ক", notes = before.str("notes").orEmpty(),
                            done = before.optBoolean("done", false), doneAt = before.lng("doneAt"),
                            dueAt = before.lng("dueAt"), repeatRule = before.str("repeatRule")
                        )
                        val id = db.taskDao().insert(t)
                        if (!t.done) t.dueAt?.let { reminders?.schedule(id, t.title, it) }
                        "টাস্কটা আবার ফিরিয়ে এনেছি।"
                    }
                }
                "EXERCISE" to "CREATE" -> { db.workoutDao().deleteExerciseById(r.entityId); "Exercise সরিয়ে দিয়েছি।" }
                "EXERCISE" to "UPDATE" -> {
                    val e = db.workoutDao().getExercise(r.entityId)
                    if (e == null || before == null) "Exercise আর পাওয়া যায় না।" else {
                        db.workoutDao().updateExercise(
                            e.copy(
                                name = before.str("name") ?: e.name, sets = before.int("sets") ?: e.sets, reps = before.int("reps"),
                                durationSec = before.int("durationSec"), weightKg = before.dbl("weightKg"),
                                restSec = before.int("restSec") ?: e.restSec, howTo = before.str("howTo").orEmpty()
                            )
                        )
                        "Exercise আগের অবস্থায় ফিরিয়ে দিয়েছি।"
                    }
                }
                "EXERCISE" to "DELETE" -> {
                    val planId = before?.lng("planId")
                    if (before == null || planId == null || db.workoutDao().getPlan(planId) == null) "ফেরানো গেল না (ওয়ার্কআউটটা নেই)।" else {
                        db.workoutDao().insertExercise(
                            WorkoutExerciseEntity(
                                planId = planId, name = before.str("name") ?: "Exercise", sets = before.int("sets") ?: 3,
                                reps = before.int("reps"), durationSec = before.int("durationSec"), weightKg = before.dbl("weightKg"),
                                restSec = before.int("restSec") ?: 60, howTo = before.str("howTo").orEmpty(),
                                orderIndex = before.int("orderIndex") ?: 0
                            )
                        )
                        "Exercise আবার ফিরিয়ে এনেছি।"
                    }
                }
                "WORKOUT" to "CREATE" -> {
                    db.workoutDao().deleteExercisesOfPlan(r.entityId); db.workoutDao().deletePlan(r.entityId); "ওয়ার্কআউটটা সরিয়ে দিয়েছি।"
                }
                "MEAL" to "CREATE" -> { db.mealDao().deleteById(r.entityId); "Meal সরিয়ে দিয়েছি।" }
                "MEAL" to "DELETE" -> {
                    if (before == null) "ফেরানো গেল না।" else {
                        db.mealDao().insert(
                            MealEntity(
                                accountId = r.accountId, name = before.str("name") ?: "Meal", mealType = before.str("mealType") ?: "OTHER",
                                kcal = before.int("kcal"), proteinG = before.dbl("proteinG"), carbsG = before.dbl("carbsG"),
                                fatG = before.dbl("fatG"), planned = before.optBoolean("planned", false),
                                plannedFor = before.str("plannedFor"), loggedAt = before.lng("loggedAt") ?: System.currentTimeMillis()
                            )
                        )
                        "Meal আবার ফিরিয়ে এনেছি।"
                    }
                }
                "MEAL" to "REPLACE" -> "ডায়েট প্ল্যান বদল ফেরানো যায় না।"
                "WATER" to "CREATE" -> { db.waterDao().deleteById(r.entityId); "পানির লগ সরিয়ে দিয়েছি।" }
                "WEIGHT" to "CREATE" -> { db.weightDao().deleteById(r.entityId); "ওজনের লগ সরিয়ে দিয়েছি।" }
                else -> "এই ধরনের কাজ ফেরানো যায় না।"
            }
            if (msg.contains("ফিরিয়ে") || msg.contains("সরিয়ে")) db.changeDao().markUndone(r.id)
            msg
        }
        return message
    }
}
