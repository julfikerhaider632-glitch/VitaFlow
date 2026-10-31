package app.vitaflow.com.workout

import app.vitaflow.com.data.db.ChangeRecordEntity
import app.vitaflow.com.data.db.SetLogEntity
import app.vitaflow.com.data.db.VitaDatabase
import app.vitaflow.com.data.db.WorkoutExerciseEntity
import app.vitaflow.com.data.db.WorkoutPlanEntity
import app.vitaflow.com.data.db.WorkoutSessionEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class RunPhase { IDLE, WORK, REST, FINISHED }

data class RunState(
    val phase: RunPhase = RunPhase.IDLE,
    val planName: String = "",
    val exercises: List<WorkoutExerciseEntity> = emptyList(),
    val exIndex: Int = 0,
    val setNo: Int = 1,
    val restLeft: Int = 0,
    val workLeft: Int = 0,
    val totalSets: Int = 0,
    val startedAt: Long = 0,
    val summary: String = ""
) {
    val current: WorkoutExerciseEntity? get() = exercises.getOrNull(exIndex)
}

data class RunResult(val text: String, val changeId: Long? = null)

/**
 * Runs a workout: current exercise, set counter, rest countdown. The same object is used by the screen
 * and by Max's voice commands ("set শেষ", "পরের exercise"), so both always agree.
 */
class WorkoutRunner(private val db: VitaDatabase) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val lock = Mutex()
    private val _state = MutableStateFlow(RunState())
    val state: StateFlow<RunState> = _state

    /** Short sentences for the voice ("rest over, set 2"). */
    val announcements = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val beeps = MutableSharedFlow<Unit>(extraBufferCapacity = 4)

    private var sessionId = 0L
    private var accountId = 0L
    private var planId = 0L
    private var tick: Job? = null

    val running: Boolean get() = _state.value.phase == RunPhase.WORK || _state.value.phase == RunPhase.REST

    fun label(ex: WorkoutExerciseEntity?): String {
        if (ex == null) return ""
        val amount = ex.reps?.let { "$it বার" } ?: "${ex.durationSec ?: 0} সেকেন্ড"
        return "${ex.name}, ${ex.sets} set, প্রতি set-এ $amount"
    }

    private fun announce(text: String) {
        announcements.tryEmit(text)
    }

    suspend fun start(accountId: Long, plan: WorkoutPlanEntity, exercises: List<WorkoutExerciseEntity>, source: String = "USER"): RunResult =
        lock.withLock {
            if (running) return@withLock RunResult("আগে চলমান ওয়ার্কআউটটা শেষ করুন।")
            if (exercises.isEmpty()) return@withLock RunResult("এই ওয়ার্কআউটে কোনো exercise নেই।")
            this.accountId = accountId
            this.planId = plan.id
            val now = System.currentTimeMillis()
            sessionId = db.sessionDao().insertSession(
                WorkoutSessionEntity(accountId = accountId, planId = plan.id, planName = plan.name, startedAt = now)
            )
            val first = exercises[0]
            _state.value = RunState(
                phase = RunPhase.WORK, planName = plan.name, exercises = exercises, exIndex = 0, setNo = 1,
                workLeft = first.durationSec ?: 0, startedAt = now
            )
            startTick()
            val text = "ওয়ার্কআউট শুরু হয়েছে: ${plan.name}। প্রথম exercise: ${label(first)}।"
            val cid = db.changeDao().insert(
                ChangeRecordEntity(
                    accountId = accountId, entity = "SESSION", entityId = sessionId, action = "START",
                    summary = "ওয়ার্কআউট শুরু: ${plan.name}", source = source
                )
            )
            announce(text)
            RunResult(text, cid)
        }

    private fun startTick() {
        tick?.cancel()
        tick = scope.launch {
            while (isActive) {
                delay(1000)
                lock.withLock { onTick() }
            }
        }
    }

    private suspend fun onTick() {
        val s = _state.value
        when (s.phase) {
            RunPhase.REST -> {
                val left = s.restLeft - 1
                if (left <= 0) advanceAfterRest() else _state.value = s.copy(restLeft = left)
            }
            RunPhase.WORK -> if (s.workLeft > 0) {
                val left = s.workLeft - 1
                if (left <= 0) completeSet(null) else _state.value = s.copy(workLeft = left)
            }
            else -> Unit
        }
    }

    private fun advanceAfterRest() {
        val s = _state.value
        val ex = s.current ?: return
        val newExercise = s.setNo >= ex.sets
        val ns = if (newExercise) s.copy(exIndex = s.exIndex + 1, setNo = 1) else s.copy(setNo = s.setNo + 1)
        val next = ns.current ?: return
        _state.value = ns.copy(phase = RunPhase.WORK, restLeft = 0, workLeft = next.durationSec ?: 0)
        beeps.tryEmit(Unit)
        announce(if (newExercise) "পরের exercise: ${label(next)}।" else "বিশ্রাম শেষ। set ${ns.setNo} শুরু করুন।")
    }

    suspend fun setDone(reps: Int?): RunResult = lock.withLock { completeSet(reps) }

    private suspend fun completeSet(reps: Int?): RunResult {
        val s = _state.value
        if (s.phase != RunPhase.WORK) return RunResult("এখন কোনো set চলছে না।")
        val ex = s.current ?: return RunResult("এখন কোনো set চলছে না।")
        db.sessionDao().insertSet(
            SetLogEntity(
                sessionId = sessionId, exerciseName = ex.name, setNo = s.setNo,
                reps = reps ?: ex.reps, weightKg = ex.weightKg, durationSec = ex.durationSec
            )
        )
        val total = s.totalSets + 1
        val lastSet = s.setNo >= ex.sets
        val lastExercise = s.exIndex >= s.exercises.size - 1
        if (lastSet && lastExercise) {
            _state.value = s.copy(totalSets = total)
            return finishLocked()
        }
        _state.value = s.copy(phase = RunPhase.REST, restLeft = ex.restSec, workLeft = 0, totalSets = total)
        val text = if (lastSet) "${ex.name} শেষ। ${ex.restSec} সেকেন্ড বিশ্রাম নিন।" else "set ${s.setNo} শেষ। ${ex.restSec} সেকেন্ড বিশ্রাম নিন।"
        announce(text)
        if (ex.restSec <= 0) advanceAfterRest()
        return RunResult(text)
    }

    suspend fun skipRest(): RunResult = lock.withLock {
        if (_state.value.phase != RunPhase.REST) return@withLock RunResult("এখন বিশ্রাম চলছে না।")
        advanceAfterRest()
        RunResult("বিশ্রাম বাদ দিলাম। ${_state.value.current?.name.orEmpty()} চালিয়ে যান।")
    }

    suspend fun next(): RunResult = lock.withLock {
        val s = _state.value
        if (!running) return@withLock RunResult("কোনো ওয়ার্কআউট চলছে না।")
        if (s.exIndex >= s.exercises.size - 1) return@withLock RunResult("এটাই শেষ exercise। শেষ করতে \"workout শেষ করো\" বলুন।")
        val ns = s.copy(exIndex = s.exIndex + 1, setNo = 1, phase = RunPhase.WORK, restLeft = 0)
        val next = ns.current
        _state.value = ns.copy(workLeft = next?.durationSec ?: 0)
        val text = "পরের exercise: ${label(next)}।"
        announce(text)
        RunResult(text)
    }

    suspend fun finish(): RunResult = lock.withLock { finishLocked() }

    private suspend fun finishLocked(): RunResult {
        val s = _state.value
        if (!running) return RunResult("কোনো ওয়ার্কআউট চলছে না।")
        tick?.cancel()
        val now = System.currentTimeMillis()
        val minutes = maxOf(1, ((now - s.startedAt) / 60000).toInt())
        db.sessionDao().updateSession(
            WorkoutSessionEntity(
                id = sessionId, accountId = accountId, planId = planId, planName = s.planName,
                startedAt = s.startedAt, finishedAt = now, totalSets = s.totalSets
            )
        )
        val summary = "ওয়ার্কআউট শেষ: ${s.planName} · ${s.totalSets}টি set · $minutes মিনিট"
        val cid = db.changeDao().insert(
            ChangeRecordEntity(
                accountId = accountId, entity = "SESSION", entityId = sessionId, action = "FINISH", summary = summary
            )
        )
        _state.value = s.copy(phase = RunPhase.FINISHED, summary = summary)
        announce("দারুণ! $summary।")
        return RunResult(summary, cid)
    }

    fun dismiss() {
        if (_state.value.phase == RunPhase.FINISHED) _state.value = RunState()
    }
}
