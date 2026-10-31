package app.vitaflow.com.data

import androidx.room.withTransaction
import app.vitaflow.com.data.db.ChangeRecordEntity
import app.vitaflow.com.data.db.VitaDatabase
import app.vitaflow.com.data.db.WorkoutExerciseEntity
import app.vitaflow.com.data.db.WorkoutPlanEntity
import kotlinx.coroutines.flow.Flow

/** One exercise as typed by the user or produced by Max. */
data class ExerciseDraft(
    val name: String,
    val sets: Int = 3,
    val reps: Int? = null,
    val durationSec: Int? = null,
    val weightKg: Double? = null,
    val restSec: Int = 60,
    val howTo: String = ""
)

class WorkoutRepository(private val db: VitaDatabase) {
    private val dao = db.workoutDao()
    private val changes = db.changeDao()

    fun observePlans(accountId: Long): Flow<List<WorkoutPlanEntity>> = dao.observePlans(accountId)
    fun observeExercises(accountId: Long): Flow<List<WorkoutExerciseEntity>> = dao.observeAllExercises(accountId)
    suspend fun plansOnce(accountId: Long) = dao.plansOnce(accountId)
    suspend fun exercisesOnce(accountId: Long) = dao.allExercisesOnce(accountId)

    private fun ExerciseDraft.toEntity(planId: Long, order: Int) = WorkoutExerciseEntity(
        planId = planId, name = name.trim(), sets = sets, reps = reps, durationSec = durationSec,
        weightKg = weightKg, restSec = restSec, howTo = howTo.trim(), orderIndex = order
    )

    suspend fun createPlan(accountId: Long, name: String, exercises: List<ExerciseDraft>, source: String = "USER"): Change =
        db.withTransaction {
            val planId = dao.insertPlan(WorkoutPlanEntity(accountId = accountId, name = name.trim(), source = source))
            exercises.forEachIndexed { i, e -> dao.insertExercise(e.toEntity(planId, i)) }
            val list = exercises.joinToString("\n") { "• ${it.name}: ${it.sets} × ${it.reps ?: "${it.durationSec ?: 0} সে."}" }
            val cid = changes.insert(
                ChangeRecordEntity(
                    accountId = accountId, entity = "WORKOUT", entityId = planId, action = "CREATE",
                    summary = "ওয়ার্কআউট তৈরি: ${name.trim()} (${exercises.size}টি exercise)\n$list", source = source
                )
            )
            Change(planId, cid)
        }

    /** Adds one exercise to an existing plan, or to a new plan called [planName] if none exists. */
    suspend fun addExercise(accountId: Long, planName: String, draft: ExerciseDraft, source: String = "USER"): Change =
        db.withTransaction {
            val plans = dao.plansOnce(accountId)
            val plan = plans.firstOrNull { it.name.equals(planName.trim(), ignoreCase = true) }
                ?: WorkoutPlanEntity(accountId = accountId, name = planName.trim(), source = source)
                    .let { it.copy(id = dao.insertPlan(it)) }
            val entity = draft.toEntity(plan.id, dao.maxOrder(plan.id) + 1)
            val id = dao.insertExercise(entity)
            val cid = changes.insert(
                ChangeRecordEntity(
                    accountId = accountId, entity = "EXERCISE", entityId = id, action = "CREATE",
                    summary = "Exercise যোগ হয়েছে: ${entity.name} (${plan.name})",
                    afterJson = entity.copy(id = id).toJson(), source = source
                )
            )
            Change(id, cid)
        }

    suspend fun updateExercise(
        accountId: Long,
        old: WorkoutExerciseEntity,
        new: WorkoutExerciseEntity,
        source: String = "USER"
    ): Change = db.withTransaction {
        dao.updateExercise(new)
        val cid = changes.insert(
            ChangeRecordEntity(
                accountId = accountId, entity = "EXERCISE", entityId = old.id, action = "UPDATE",
                summary = "Exercise বদলানো হয়েছে: ${old.name}",
                beforeJson = old.toJson(), afterJson = new.toJson(), source = source
            )
        )
        Change(old.id, cid)
    }

    suspend fun deleteExercise(accountId: Long, exercise: WorkoutExerciseEntity, source: String = "USER"): Change =
        db.withTransaction {
            dao.deleteExercise(exercise)
            val cid = changes.insert(
                ChangeRecordEntity(
                    accountId = accountId, entity = "EXERCISE", entityId = exercise.id, action = "DELETE",
                    summary = "Exercise মুছে ফেলা হয়েছে: ${exercise.name}",
                    beforeJson = exercise.toJson(), source = source
                )
            )
            Change(exercise.id, cid)
        }

    suspend fun deletePlan(accountId: Long, plan: WorkoutPlanEntity, source: String = "USER"): Change =
        db.withTransaction {
            dao.deleteExercisesOfPlan(plan.id)
            dao.deletePlan(plan.id)
            val cid = changes.insert(
                ChangeRecordEntity(
                    accountId = accountId, entity = "WORKOUT", entityId = plan.id, action = "DELETE",
                    summary = "ওয়ার্কআউট মুছে ফেলা হয়েছে: ${plan.name}", source = source
                )
            )
            Change(plan.id, cid)
        }
}
