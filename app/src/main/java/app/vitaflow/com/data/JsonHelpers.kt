package app.vitaflow.com.data

import app.vitaflow.com.data.db.MealEntity
import app.vitaflow.com.data.db.TaskEntity
import app.vitaflow.com.data.db.WaterLogEntity
import app.vitaflow.com.data.db.WorkoutExerciseEntity
import org.json.JSONObject

private fun JSONObject.putN(key: String, value: Any?): JSONObject = put(key, value ?: JSONObject.NULL)

fun TaskEntity.toJson(): String = JSONObject()
    .put("title", title).put("notes", notes).put("done", done)
    .putN("dueAt", dueAt).putN("repeatRule", repeatRule).putN("doneAt", doneAt).toString()

fun WorkoutExerciseEntity.toJson(): String = JSONObject()
    .put("planId", planId).put("orderIndex", orderIndex)
    .put("name", name).put("sets", sets).putN("reps", reps).putN("durationSec", durationSec)
    .putN("weightKg", weightKg).put("restSec", restSec).put("howTo", howTo).toString()

fun MealEntity.toJson(): String = JSONObject()
    .put("name", name).put("mealType", mealType).putN("kcal", kcal).putN("proteinG", proteinG)
    .putN("carbsG", carbsG).putN("fatG", fatG).put("planned", planned).putN("plannedFor", plannedFor)
    .put("loggedAt", loggedAt).toString()

fun WaterLogEntity.toJson(): String = JSONObject().put("ml", ml).toString()

/** Human readable "field: old -> new" lines for the change dialog. */
fun diffJson(before: String?, after: String?): List<String> {
    val b = runCatching { JSONObject(before ?: "{}") }.getOrDefault(JSONObject())
    val a = runCatching { JSONObject(after ?: "{}") }.getOrDefault(JSONObject())
    val keys = (b.keys().asSequence() + a.keys().asSequence()).toSortedSet()
    return keys.mapNotNull { k ->
        val old = if (b.isNull(k)) "—" else b.get(k).toString()
        val new = if (a.isNull(k)) "—" else a.get(k).toString()
        if (old == new) null else "$k: $old → $new"
    }
}
