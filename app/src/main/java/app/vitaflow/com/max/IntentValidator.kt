package app.vitaflow.com.max

import app.vitaflow.com.data.ExerciseDraft
import app.vitaflow.com.data.MealDraft
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * Checks everything the language model produced. Unknown intents, missing fields and out-of-range
 * numbers are rejected, so a wrong or made-up answer can never change the database.
 */
object IntentValidator {
    private val mealTypes = setOf("BREAKFAST", "LUNCH", "DINNER", "SNACK")
    private val showTargets = setOf("TASKS", "WATER", "MEALS", "WORKOUTS", "PROGRESS")

    fun extractJson(raw: String): JSONObject? {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching { JSONObject(raw.substring(start, end + 1)) }.getOrNull()
    }

    private fun JSONObject.text(key: String, max: Int): String? =
        if (has(key) && !isNull(key)) optString(key).trim().takeIf { it.isNotEmpty() }?.take(max) else null

    private fun JSONObject.num(key: String): Double? {
        if (!has(key) || isNull(key)) return null
        val d = optDouble(key, Double.NaN)
        return if (d.isNaN()) null else d
    }

    private fun JSONObject.int(key: String, range: IntRange): Int? =
        num(key)?.toInt()?.takeIf { it in range }

    private fun JSONObject.dbl(key: String, max: Double): Double? =
        num(key)?.takeIf { it >= 0 && it <= max }

    private fun dueAt(o: JSONObject, now: LocalDateTime): Long? {
        val date = o.text("date", 10)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        val time = o.text("time", 5)?.let { runCatching { LocalTime.parse(it) }.getOrNull() }
        if (date == null && time == null) return null
        val dt = (date ?: now.toLocalDate()).atTime(time ?: LocalTime.of(9, 0))
        if (dt.isBefore(now.minusDays(1)) || dt.isAfter(now.plusYears(2))) return null
        return dt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }

    /** The model may return one command, or {"actions":[...]} when the person said several things at once. */
    fun parseMany(raw: String, now: LocalDateTime): List<MaxIntent> {
        val o = extractJson(raw) ?: return listOf(MaxIntent.Unknown)
        val arr = o.optJSONArray("actions")
        if (o.optString("route").uppercase() == "COMMAND" && arr != null) {
            val list = (0 until minOf(arr.length(), 4)).mapNotNull { arr.optJSONObject(it) }
                .map { command(it, now) }.filter { it != MaxIntent.Unknown }
            return list.ifEmpty { listOf(MaxIntent.Unknown) }
        }
        return listOf(parse(raw, now))
    }

    fun parse(raw: String, now: LocalDateTime): MaxIntent {
        val o = extractJson(raw) ?: return MaxIntent.Unknown
        return when (o.optString("route").uppercase()) {
            "CHAT" -> MaxIntent.Chat(o.text("reply", 700))
            "BYE" -> MaxIntent.Bye
            "WEB" -> o.text("search", 200)?.let { MaxIntent.WebSearch(it) } ?: MaxIntent.Unknown
            "COMMAND" -> command(o, now)
            else -> MaxIntent.Unknown
        }
    }

    private fun command(o: JSONObject, now: LocalDateTime): MaxIntent {
        return when (o.optString("intent").uppercase()) {
            "CREATE_TASK" -> {
                val title = o.text("title", 120) ?: return MaxIntent.Unknown
                val repeat = if (o.optString("repeat").uppercase() == "DAILY") "DAILY" else null
                MaxIntent.CreateTask(title, dueAt(o, now) ?: if (repeat != null) dueAt(JSONObject().put("time", o.optString("time", "09:00")), now) else null, repeat)
            }
            "COMPLETE_TASK" -> (o.text("query", 120) ?: o.text("title", 120))
                ?.let { MaxIntent.CompleteTask(it) } ?: MaxIntent.Unknown
            "DELETE_TASK" -> (o.text("query", 120) ?: o.text("title", 120))
                ?.let { MaxIntent.DeleteTask(it) } ?: MaxIntent.Unknown
            "UPDATE_TASK" -> {
                val q = o.text("query", 120) ?: return MaxIntent.Unknown
                val newTitle = o.text("title", 120)
                val due = dueAt(o, now)
                if (newTitle == null && due == null) MaxIntent.Unknown else MaxIntent.UpdateTask(q, newTitle, due)
            }
            "LOG_WEIGHT" -> o.dbl("kg", 300.0)?.takeIf { it >= 20 }?.let { MaxIntent.LogWeight(it) } ?: MaxIntent.Unknown
            "START_WORKOUT" -> MaxIntent.StartWorkout(o.text("name", 60))
            "CREATE_PLAN" -> o.text("what", 10)?.uppercase()?.takeIf { it == "WORKOUT" || it == "DIET" }
                ?.let { MaxIntent.CreatePlan(it, o.text("request", 200).orEmpty()) } ?: MaxIntent.Unknown
            "LOG_WATER" -> o.int("ml", 50..3000)?.let { MaxIntent.LogWater(it) } ?: MaxIntent.Unknown
            "ADD_MEAL" -> {
                val name = o.text("name", 100) ?: return MaxIntent.Unknown
                val type = o.text("meal_type", 12)?.uppercase()?.takeIf { it in mealTypes } ?: "OTHER"
                MaxIntent.AddMeal(
                    MealDraft(
                        name = name, mealType = type,
                        kcal = o.int("kcal", 0..5000),
                        proteinG = o.dbl("protein", 500.0),
                        carbsG = o.dbl("carbs", 800.0),
                        fatG = o.dbl("fat", 500.0),
                        planned = o.optBoolean("planned", false)
                    )
                )
            }
            "CREATE_WORKOUT" -> {
                val arr = o.optJSONArray("exercises") ?: return MaxIntent.Unknown
                if (arr.length() !in 1..20) return MaxIntent.Unknown
                val list = ArrayList<ExerciseDraft>()
                for (i in 0 until arr.length()) {
                    val e = arr.optJSONObject(i) ?: return MaxIntent.Unknown
                    val name = e.text("name", 60) ?: return MaxIntent.Unknown
                    val reps = e.int("reps", 1..500)
                    val secs = e.int("seconds", 5..3600)
                    if (reps == null && secs == null) return MaxIntent.Unknown
                    list += ExerciseDraft(
                        name = name,
                        sets = e.int("sets", 1..20) ?: 3,
                        reps = reps,
                        durationSec = secs,
                        weightKg = e.dbl("weight_kg", 500.0),
                        restSec = e.int("rest", 0..600) ?: 60,
                        howTo = e.text("how_to", 500).orEmpty()
                    )
                }
                MaxIntent.CreateWorkout(o.text("name", 60) ?: "আমার workout", list)
            }
            "UPDATE_EXERCISE" -> {
                val name = o.text("name", 60) ?: return MaxIntent.Unknown
                val u = MaxIntent.UpdateExercise(
                    exerciseName = name,
                    sets = o.int("sets", 1..20),
                    reps = o.int("reps", 1..500),
                    durationSec = o.int("seconds", 5..3600),
                    weightKg = o.dbl("weight_kg", 500.0),
                    restSec = o.int("rest", 0..600),
                    howTo = o.text("how_to", 500)
                )
                val any = listOf(u.sets, u.reps, u.durationSec, u.weightKg, u.restSec, u.howTo).any { it != null }
                if (any) u else MaxIntent.Unknown
            }
            "SHOW" -> o.text("what", 10)?.uppercase()?.takeIf { it in showTargets }
                ?.let { MaxIntent.Show(it) } ?: MaxIntent.Unknown
            else -> MaxIntent.Unknown
        }
    }
}
