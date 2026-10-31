package app.vitaflow.com.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** source is "USER" when the user made the change by hand, "MAX" when the assistant did. */

@Entity(
    tableName = "accounts",
    indices = [Index("email", unique = true), Index("phone", unique = true), Index("googleSub")]
)
data class AccountEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val phone: String?,
    val email: String?,
    val birthDate: String,
    val passwordHash: String,
    val passwordSalt: String,
    val securityQuestionId: Int,
    val securityAnswerHash: String,
    val securityAnswerSalt: String,
    val failedAttempts: Int = 0,
    val lockedUntil: Long = 0,
    val answerFailed: Int = 0,
    val answerLockedUntil: Long = 0,
    val googleSub: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "tasks", indices = [Index("accountId")])
data class TaskEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val accountId: Long,
    val title: String,
    val notes: String = "",
    val dueAt: Long? = null,
    val repeatRule: String? = null,
    val done: Boolean = false,
    val doneAt: Long? = null,
    val source: String = "USER",
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "workout_plans", indices = [Index("accountId")])
data class WorkoutPlanEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val accountId: Long,
    val name: String,
    val source: String = "USER",
    val createdAt: Long = System.currentTimeMillis()
)

/** One exercise by name (Push-ups, Sit-ups) with its own details. No "leg day" style grouping. */
@Entity(tableName = "workout_exercises", indices = [Index("planId")])
data class WorkoutExerciseEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val planId: Long,
    val name: String,
    val sets: Int = 3,
    val reps: Int? = null,
    val durationSec: Int? = null,
    val weightKg: Double? = null,
    val restSec: Int = 60,
    val howTo: String = "",
    val orderIndex: Int = 0
)

@Entity(tableName = "meals", indices = [Index("accountId")])
data class MealEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val accountId: Long,
    val name: String,
    val mealType: String = "OTHER",
    val kcal: Int? = null,
    val proteinG: Double? = null,
    val carbsG: Double? = null,
    val fatG: Double? = null,
    /** true = part of a diet plan (planned), false = something actually eaten. */
    val planned: Boolean = false,
    val plannedFor: String? = null,
    val source: String = "USER",
    val loggedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "water_logs", indices = [Index("accountId")])
data class WaterLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val accountId: Long,
    val ml: Int,
    val source: String = "USER",
    val loggedAt: Long = System.currentTimeMillis()
)

/** Every change, by the user or by Max, is recorded here. The chat "Open" button reads this table. */
@Entity(tableName = "change_records", indices = [Index("accountId")])
data class ChangeRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val accountId: Long,
    val entity: String,
    val entityId: Long,
    val action: String,
    val summary: String,
    val beforeJson: String? = null,
    val afterJson: String? = null,
    val source: String = "USER",
    /** true after the user pressed Undo on this change. */
    val undone: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "chat_messages", indices = [Index("accountId"), Index("conversationId")])
data class ChatMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val accountId: Long,
    val conversationId: Long = 0,
    /** "USER" or "MAX" */
    val role: String,
    val text: String,
    /** Comma separated change_records ids, used by the "Open" button. */
    val changeIds: String = "",
    /** JSON array of {title,url} for web answers. */
    val sourcesJson: String? = null,
    /** JSON array of {path,kind,name} for photos and files sent with this message. */
    val attachments: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

/** One row per person: the facts Max and the plan makers use. */
@Entity(tableName = "profiles")
data class ProfileEntity(
    @PrimaryKey val accountId: Long,
    /** "MALE", "FEMALE" or "" (not told) */
    val gender: String = "",
    val heightCm: Int? = null,
    val weightKg: Double? = null,
    /** LOSE_FAT, MAINTAIN or BUILD_MUSCLE */
    val goal: String = "MAINTAIN",
    /** BEGINNER, INTERMEDIATE or ADVANCED */
    val level: String = "BEGINNER",
    /** NONE (body weight), DUMBBELL or GYM */
    val equipment: String = "NONE",
    val dietPref: String = "",
    val allergies: String = "",
    val injuries: String = "",
    val waterTargetMl: Int = 2500
)

@Entity(tableName = "weight_logs", indices = [Index("accountId")])
data class WeightLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val accountId: Long,
    val kg: Double,
    val source: String = "USER",
    val loggedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "workout_sessions", indices = [Index("accountId")])
data class WorkoutSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val accountId: Long,
    val planId: Long,
    val planName: String,
    val startedAt: Long = System.currentTimeMillis(),
    val finishedAt: Long? = null,
    val totalSets: Int = 0
)

@Entity(tableName = "set_logs", indices = [Index("sessionId")])
data class SetLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val exerciseName: String,
    val setNo: Int,
    val reps: Int? = null,
    val weightKg: Double? = null,
    val durationSec: Int? = null,
    val doneAt: Long = System.currentTimeMillis()
)

/** One chat. The summary and digest are Max's memory of it when you are not inside it. */
@Entity(tableName = "conversations", indices = [Index("accountId")])
data class ConversationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val accountId: Long,
    val title: String = "",
    /** Rolling summary of everything in this chat that is older than the latest messages. */
    val summary: String = "",
    /** Id of the last message already folded into [summary]. */
    val summarizedUpTo: Long = 0,
    /** One line about what this chat was about. */
    val digest: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

/** A lasting fact Max learned about the person ("খুব ভোরে workout করতে পছন্দ করে"). The user can read and delete these. */
@Entity(tableName = "memory_facts", indices = [Index("accountId")])
data class MemoryFactEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val accountId: Long,
    val text: String,
    val conversationId: Long = 0,
    val createdAt: Long = System.currentTimeMillis()
)
