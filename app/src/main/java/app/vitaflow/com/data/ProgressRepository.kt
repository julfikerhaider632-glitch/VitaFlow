package app.vitaflow.com.data

import app.vitaflow.com.data.db.VitaDatabase
import app.vitaflow.com.data.db.WeightLogEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class DayValue(val date: LocalDate, val value: Double)

data class Progress(
    val weights: List<WeightLogEntity>,
    val waterByDay: List<DayValue>,
    val kcalByDay: List<DayValue>,
    val sessions7: Int,
    val sets7: Int,
    val streak: Int,
    val tasksDone7: Int,
    val tasksOpen: Int
)

/** Works out the numbers for the progress screen and for "how was my week?" from what is already saved. */
class ProgressRepository(private val db: VitaDatabase) {
    private val zone = ZoneId.systemDefault()
    private fun day(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()

    fun observe(accountId: Long): Flow<Progress> = combine(
        db.taskDao().observe(accountId),
        db.mealDao().observe(accountId),
        db.waterDao().observe(accountId),
        db.weightDao().observe(accountId),
        db.sessionDao().observeFinished(accountId)
    ) { tasks, meals, water, weights, sessions ->
        val today = LocalDate.now()
        val last7 = (6 downTo 0).map { today.minusDays(it.toLong()) }
        val waterByDay = last7.map { d -> DayValue(d, water.filter { day(it.loggedAt) == d }.sumOf { it.ml }.toDouble()) }
        val kcalByDay = last7.map { d ->
            DayValue(d, meals.filter { !it.planned && day(it.loggedAt) == d }.sumOf { it.kcal ?: 0 }.toDouble())
        }
        val weekAgo = today.minusDays(6)
        val recent = sessions.filter { day(it.startedAt) >= weekAgo }
        val days = sessions.map { day(it.startedAt) }.toSet()
        var streak = 0
        var d = if (today in days) today else today.minusDays(1)
        while (d in days) {
            streak++
            d = d.minusDays(1)
        }
        Progress(
            weights = weights,
            waterByDay = waterByDay,
            kcalByDay = kcalByDay,
            sessions7 = recent.size,
            sets7 = recent.sumOf { it.totalSets },
            streak = streak,
            tasksDone7 = tasks.count { it.done && (it.doneAt ?: 0L) > 0 && day(it.doneAt ?: 0L) >= weekAgo },
            tasksOpen = tasks.count { !it.done }
        )
    }

    fun summaryText(p: Progress): String {
        val water = p.waterByDay.filter { it.value > 0 }
        val kcal = p.kcalByDay.filter { it.value > 0 }
        val lines = mutableListOf<String>()
        lines += "এই সপ্তাহে ${p.sessions7}টি workout (${p.sets7} set), টানা ${p.streak} দিনের streak।"
        lines += "টাস্ক: ${p.tasksDone7}টি সম্পন্ন, ${p.tasksOpen}টি বাকি।"
        if (water.isNotEmpty()) lines += "পানি: গড়ে দিনে ${"%.0f".format(water.map { it.value }.average())} মিলি।"
        if (kcal.isNotEmpty()) lines += "ক্যালরি: গড়ে দিনে ${"%.0f".format(kcal.map { it.value }.average())} kcal।"
        if (p.weights.size >= 2) {
            val change = p.weights.last().kg - p.weights.first().kg
            lines += "ওজন: ${"%.1f".format(p.weights.first().kg)} থেকে ${"%.1f".format(p.weights.last().kg)} কেজি (${"%+.1f".format(change)})।"
        } else if (p.weights.size == 1) {
            lines += "ওজন: ${"%.1f".format(p.weights.last().kg)} কেজি।"
        }
        return lines.joinToString("\n")
    }
}
