package app.vitaflow.com.max

import app.vitaflow.com.data.MealRepository
import app.vitaflow.com.data.ProfileRepository
import app.vitaflow.com.data.ProgressRepository
import app.vitaflow.com.data.TaskRepository
import app.vitaflow.com.data.UndoManager
import app.vitaflow.com.data.WorkoutRepository
import app.vitaflow.com.data.db.VitaDatabase
import app.vitaflow.com.workout.RunResult
import app.vitaflow.com.workout.WorkoutRunner
import kotlinx.coroutines.flow.first
import app.vitaflow.com.data.db.TaskEntity
import app.vitaflow.com.data.db.WorkoutExerciseEntity
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The only code allowed to act on what Max understood. It calls the same repositories the screens use,
 * so a change made by Max and a change made by hand go through exactly the same checked path.
 */
class ActionEngine(
    private val db: VitaDatabase,
    private val tasks: TaskRepository,
    private val workouts: WorkoutRepository,
    private val meals: MealRepository,
    private val profiles: ProfileRepository,
    private val progress: ProgressRepository,
    private val runner: WorkoutRunner,
    private val undo: UndoManager,
    private val ai: AiPlanner
) {
    private fun RunResult.toReply() = MaxReply(text, listOfNotNull(changeId))

    private suspend fun ageOf(accountId: Long): Int? {
        val birth = db.accountDao().get(accountId)?.birthDate?.takeIf { it.isNotBlank() } ?: return null
        return runCatching { java.time.Period.between(LocalDate.parse(birth), LocalDate.now()).years }.getOrNull()
    }

    private val src = "MAX"
    private val dueFormat = DateTimeFormatter.ofPattern("d/M/yyyy, HH:mm")

    private fun startOfToday(): Long =
        LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun norm(s: String) = s.trim().lowercase()

    private fun matchTasks(all: List<TaskEntity>, query: String): List<TaskEntity> {
        val q = norm(query)
        all.filter { norm(it.title) == q }.let { if (it.isNotEmpty()) return it }
        return all.filter { norm(it.title).contains(q) || q.contains(norm(it.title)) }
    }

    private fun manyTasksReply(list: List<TaskEntity>) = MaxReply(
        "কোন টাস্কটা? " + list.take(5).joinToString("; ") { "\"${it.title}\"" } + " — নামটা আরেকটু স্পষ্ট করে বলুন।"
    )

    /** Delete always asks first. Returns a question, and the real delete happens in [confirmDelete]. */
    suspend fun prepareDelete(accountId: Long, intent: MaxIntent.DeleteTask): MaxReply {
        val found = matchTasks(tasks.listOnce(accountId), intent.query)
        return when {
            found.isEmpty() -> MaxReply("\"${intent.query}\" নামে কোনো টাস্ক পাইনি।")
            found.size > 1 -> manyTasksReply(found)
            else -> {
                val q = "আপনি কি \"${found[0].title}\" টাস্কটা মুছে ফেলতে চান? (হ্যাঁ / না)"
                MaxReply(q, pending = PendingAction(intent, taskId = found[0].id, question = q))
            }
        }
    }

    suspend fun confirmDelete(accountId: Long, pending: PendingAction): MaxReply {
        val task = tasks.listOnce(accountId).firstOrNull { it.id == pending.taskId }
            ?: return MaxReply("টাস্কটা আর পাওয়া যাচ্ছে না।")
        val c = tasks.delete(task, src)
        return MaxReply("\"${task.title}\" টাস্কটা মুছে ফেলেছি।", changeIds = listOf(c.changeId))
    }

    /** Called when the user answered "yes" to a question Max asked. */
    suspend fun confirm(accountId: Long, pending: PendingAction): MaxReply = when (val i = pending.intent) {
        is MaxIntent.DeleteTask -> confirmDelete(accountId, pending)
        is MaxIntent.CreatePlan -> createPlan(accountId, i.kind, confirmed = true, request = i.request)
        is MaxIntent.AddMeal -> execute(accountId, i)
        else -> MaxReply("ঠিক আছে।")
    }

    private suspend fun startWorkout(accountId: Long, i: MaxIntent.StartWorkout): MaxReply {
        val plans = workouts.plansOnce(accountId)
        val plan = i.planName?.let { n -> plans.firstOrNull { it.name.contains(n, ignoreCase = true) } } ?: plans.firstOrNull()
            ?: return MaxReply("আগে একটা ওয়ার্কআউট বানান। \"workout plan বানাও\" বললে আমি আপনার প্রোফাইল দেখে বানিয়ে দেব।")
        val ex = workouts.exercisesOnce(accountId).filter { it.planId == plan.id }
        return runner.start(accountId, plan, ex, src).toReply()
    }

    private suspend fun createPlan(accountId: Long, kind: String, confirmed: Boolean, request: String): MaxReply {
        val profile = profiles.get(accountId)
        if (kind == "WORKOUT") {
            val r = ai.workout(profile, request) ?: PlanTemplates.workout(profile)
            val c = workouts.createPlan(accountId, r.name, r.exercises, src)
            return MaxReply(
                "আপনার কাজ সম্পন্ন হয়েছে: \"${r.name}\" বানিয়েছি (${r.exercises.size}টি exercise)।\n${r.note}",
                listOf(c.changeId)
            )
        }
        val age = ageOf(accountId)
        DietPlanner.target(profile, age).error?.let { return MaxReply(it) }
        if (!confirmed && meals.plannedCount(accountId) > 0) {
            val q = "আগের ডায়েট প্ল্যান মুছে নতুনটা বসাব? (হ্যাঁ / না)"
            return MaxReply(q, pending = PendingAction(MaxIntent.CreatePlan("DIET", request), null, q))
        }
        val r = ai.diet(profile, age, request) ?: DietPlanner.build(profile, age)
        val c = meals.replaceDietPlan(accountId, r.drafts, src)
        return MaxReply(
            "আপনার কাজ সম্পন্ন হয়েছে: দৈনিক প্রায় ${r.targetKcal} kcal-এর ডায়েট প্ল্যান বানিয়েছি।\n${r.note}",
            listOf(c.changeId)
        )
    }

    private suspend fun undoLast(accountId: Long): MaxReply {
        val last = db.changeDao().lastActive(accountId) ?: return MaxReply("ফেরানোর মতো কিছু পাইনি।")
        return MaxReply(undo.undo(last))
    }

    suspend fun execute(accountId: Long, intent: MaxIntent): MaxReply = when (intent) {
        is MaxIntent.LogWeight -> {
            val c = profiles.logWeight(accountId, intent.kg, src)
            MaxReply("আপনার কাজ সম্পন্ন হয়েছে: ওজন ${"%.1f".format(intent.kg)} কেজি লগ করেছি।", listOf(c.changeId))
        }
        is MaxIntent.StartWorkout -> startWorkout(accountId, intent)
        is MaxIntent.NextExercise -> runner.next().toReply()
        is MaxIntent.SetDone -> runner.setDone(intent.reps).toReply()
        is MaxIntent.SkipRest -> runner.skipRest().toReply()
        is MaxIntent.FinishWorkout -> runner.finish().toReply()
        is MaxIntent.CreatePlan -> createPlan(accountId, intent.kind, confirmed = false, request = intent.request)
        is MaxIntent.Undo -> undoLast(accountId)
        is MaxIntent.CreateTask -> {
            val c = tasks.create(accountId, intent.title, dueAt = intent.dueAt, source = src, repeatRule = intent.repeatRule)
            val whenText = intent.dueAt?.let {
                " (${Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(dueFormat)})"
            }.orEmpty()
            val daily = if (intent.repeatRule == "DAILY") " (প্রতিদিন)" else ""
            MaxReply("আপনার কাজ সম্পন্ন হয়েছে: \"${intent.title}\" টাস্ক যোগ করেছি$whenText$daily।", listOf(c.changeId))
        }
        is MaxIntent.CompleteTask -> {
            val found = matchTasks(tasks.listOnce(accountId), intent.query)
            when {
                found.isEmpty() -> MaxReply("\"${intent.query}\" নামে কোনো টাস্ক পাইনি।")
                found.size > 1 -> manyTasksReply(found)
                found[0].done -> MaxReply("\"${found[0].title}\" আগেই সম্পন্ন করা আছে।")
                else -> {
                    val c = tasks.setDone(found[0], true, src)
                    MaxReply("আপনার কাজ সম্পন্ন হয়েছে: \"${found[0].title}\" টাস্ক সম্পন্ন করেছি।", listOf(c.changeId))
                }
            }
        }
        is MaxIntent.UpdateTask -> {
            val found = matchTasks(tasks.listOnce(accountId), intent.query)
            when {
                found.isEmpty() -> MaxReply("\"${intent.query}\" নামে কোনো টাস্ক পাইনি।")
                found.size > 1 -> manyTasksReply(found)
                else -> {
                    val c = tasks.update(found[0], intent.newTitle, intent.dueAt, src)
                    MaxReply("আপনার কাজ সম্পন্ন হয়েছে: টাস্কটা বদলে দিয়েছি।", listOf(c.changeId))
                }
            }
        }
        is MaxIntent.DeleteTask -> prepareDelete(accountId, intent)
        is MaxIntent.LogWater -> {
            val c = meals.addWater(accountId, intent.ml, src)
            val total = meals.waterOnce(accountId).filter { it.loggedAt >= startOfToday() }.sumOf { it.ml }
            MaxReply("আপনার কাজ সম্পন্ন হয়েছে: ${intent.ml} মিলি পানি লগ করেছি। আজ মোট $total মিলি।", listOf(c.changeId))
        }
        is MaxIntent.AddMeal -> {
            val c = meals.addMeal(accountId, intent.draft, src)
            val what = if (intent.draft.planned) "ডায়েট প্ল্যানে যোগ করেছি" else "লগ করেছি"
            MaxReply("আপনার কাজ সম্পন্ন হয়েছে: \"${intent.draft.name}\" $what।", listOf(c.changeId))
        }
        is MaxIntent.CreateWorkout -> {
            val c = workouts.createPlan(accountId, intent.name, intent.exercises, src)
            MaxReply(
                "আপনার কাজ সম্পন্ন হয়েছে: \"${intent.name}\" ওয়ার্কআউটে ${intent.exercises.size}টি exercise যোগ করেছি।",
                listOf(c.changeId)
            )
        }
        is MaxIntent.UpdateExercise -> updateExercise(accountId, intent)
        is MaxIntent.Show -> MaxReply(show(accountId, intent.what))
        else -> MaxReply("এই কাজটা আমি করতে পারি না।")
    }

    private suspend fun updateExercise(accountId: Long, i: MaxIntent.UpdateExercise): MaxReply {
        val q = norm(i.exerciseName)
        val all = workouts.exercisesOnce(accountId)
        val found = all.filter { norm(it.name) == q }.ifEmpty { all.filter { norm(it.name).contains(q) || q.contains(norm(it.name)) } }
        if (found.isEmpty()) return MaxReply("\"${i.exerciseName}\" নামে কোনো exercise পাইনি।")
        if (found.size > 1) {
            return MaxReply("কোনটা? " + found.take(5).joinToString("; ") { "\"${it.name}\"" })
        }
        val old = found[0]
        val new: WorkoutExerciseEntity = old.copy(
            sets = i.sets ?: old.sets,
            reps = i.reps ?: old.reps,
            durationSec = i.durationSec ?: old.durationSec,
            weightKg = i.weightKg ?: old.weightKg,
            restSec = i.restSec ?: old.restSec,
            howTo = i.howTo ?: old.howTo
        )
        val c = workouts.updateExercise(accountId, old, new, src)
        return MaxReply("আপনার কাজ সম্পন্ন হয়েছে: \"${old.name}\" বদলে দিয়েছি।", listOf(c.changeId))
    }

    private suspend fun show(accountId: Long, what: String): String = when (what) {
        "TASKS" -> {
            val open = tasks.listOnce(accountId).filter { !it.done }
            if (open.isEmpty()) "এখন কোনো বাকি টাস্ক নেই।" else "বাকি টাস্ক:\n" + open.joinToString("\n") { "• ${it.title}" }
        }
        "WATER" -> {
            val total = meals.waterOnce(accountId).filter { it.loggedAt >= startOfToday() }.sumOf { it.ml }
            "আজ এখন পর্যন্ত $total মিলি পানি খেয়েছেন।"
        }
        "MEALS" -> {
            val today = meals.mealsOnce(accountId).filter { !it.planned && it.loggedAt >= startOfToday() }
            if (today.isEmpty()) {
                "আজ এখনো কোনো meal লগ করা হয়নি।"
            } else {
                val kcal = today.sumOf { it.kcal ?: 0 }
                val p = today.sumOf { it.proteinG ?: 0.0 }
                "আজ ${today.size}টি meal: মোট $kcal kcal, প্রোটিন ${"%.0f".format(p)} গ্রাম।"
            }
        }
        "PROGRESS" -> progress.summaryText(progress.observe(accountId).first())
        "WORKOUTS" -> {
            val plans = workouts.plansOnce(accountId)
            val ex = workouts.exercisesOnce(accountId)
            if (plans.isEmpty()) "এখনো কোনো ওয়ার্কআউট নেই." else plans.joinToString("\n\n") { p ->
                p.name + "\n" + ex.filter { it.planId == p.id }.joinToString("\n") { "• ${it.name}: ${it.sets} × ${it.reps ?: "${it.durationSec} সে."}" }
            }
        }
        else -> "এটা দেখাতে পারছি না।"
    }
}
