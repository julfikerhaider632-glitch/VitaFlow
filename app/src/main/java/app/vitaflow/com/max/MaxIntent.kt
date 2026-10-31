package app.vitaflow.com.max

import app.vitaflow.com.data.ExerciseDraft
import app.vitaflow.com.data.MealDraft

/** What Max understood. The language model only produces one of these; it never touches the database. */
sealed interface MaxIntent {
    data class CreateTask(val title: String, val dueAt: Long? = null, val repeatRule: String? = null) : MaxIntent
    data class CompleteTask(val query: String) : MaxIntent
    data class DeleteTask(val query: String) : MaxIntent
    data class UpdateTask(val query: String, val newTitle: String?, val dueAt: Long?) : MaxIntent
    data class LogWater(val ml: Int) : MaxIntent
    data class LogWeight(val kg: Double) : MaxIntent
    data class AddMeal(val draft: MealDraft) : MaxIntent
    data class CreateWorkout(val name: String, val exercises: List<ExerciseDraft>) : MaxIntent
    data class UpdateExercise(
        val exerciseName: String,
        val sets: Int?,
        val reps: Int?,
        val durationSec: Int?,
        val weightKg: Double?,
        val restSec: Int?,
        val howTo: String?
    ) : MaxIntent
    /** Builds a workout or diet plan from the profile. [kind] is "WORKOUT" or "DIET". */
    data class CreatePlan(val kind: String, val request: String = "") : MaxIntent
    data class StartWorkout(val planName: String?) : MaxIntent
    data object NextExercise : MaxIntent
    data class SetDone(val reps: Int?) : MaxIntent
    data object SkipRest : MaxIntent
    data object FinishWorkout : MaxIntent
    data object Undo : MaxIntent
    data class Show(val what: String) : MaxIntent
    data class WebSearch(val query: String) : MaxIntent
    data object Bye : MaxIntent
    /** [reply] is filled when the language model already wrote the answer in the same step. */
    data class Chat(val reply: String? = null) : MaxIntent
    data object Unknown : MaxIntent
}

/** An action waiting for the user's "yes" or "no". */
data class PendingAction(val intent: MaxIntent, val taskId: Long? = null, val question: String)

data class MaxSource(val title: String, val url: String)

data class MaxReply(
    val text: String,
    val changeIds: List<Long> = emptyList(),
    val sources: List<MaxSource> = emptyList(),
    val pending: PendingAction? = null,
    /** true when the user said goodbye: Live mode should end. */
    val endLive: Boolean = false
)
