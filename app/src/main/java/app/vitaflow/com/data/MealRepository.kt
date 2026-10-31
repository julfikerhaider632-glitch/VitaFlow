package app.vitaflow.com.data

import androidx.room.withTransaction
import app.vitaflow.com.data.db.ChangeRecordEntity
import app.vitaflow.com.data.db.MealEntity
import app.vitaflow.com.data.db.VitaDatabase
import app.vitaflow.com.data.db.WaterLogEntity
import kotlinx.coroutines.flow.Flow

data class MealDraft(
    val name: String,
    val mealType: String = "OTHER",
    val kcal: Int? = null,
    val proteinG: Double? = null,
    val carbsG: Double? = null,
    val fatG: Double? = null,
    val planned: Boolean = false,
    val plannedFor: String? = null
)

class MealRepository(private val db: VitaDatabase) {
    private val meals = db.mealDao()
    private val water = db.waterDao()
    private val changes = db.changeDao()

    fun observeMeals(accountId: Long): Flow<List<MealEntity>> = meals.observe(accountId)
    fun observeWater(accountId: Long): Flow<List<WaterLogEntity>> = water.observe(accountId)
    suspend fun mealsOnce(accountId: Long) = meals.allOnce(accountId)
    suspend fun waterOnce(accountId: Long) = water.allOnce(accountId)

    suspend fun addMeal(accountId: Long, d: MealDraft, source: String = "USER"): Change = db.withTransaction {
        val meal = MealEntity(
            accountId = accountId, name = d.name.trim(), mealType = d.mealType, kcal = d.kcal,
            proteinG = d.proteinG, carbsG = d.carbsG, fatG = d.fatG, planned = d.planned,
            plannedFor = d.plannedFor, source = source
        )
        val id = meals.insert(meal)
        val cid = changes.insert(
            ChangeRecordEntity(
                accountId = accountId, entity = "MEAL", entityId = id, action = "CREATE",
                summary = (if (d.planned) "ডায়েট প্ল্যানে যোগ: " else "Meal লগ: ") + meal.name,
                afterJson = meal.copy(id = id).toJson(), source = source
            )
        )
        Change(id, cid)
    }

    suspend fun plannedCount(accountId: Long): Int = meals.plannedCount(accountId)

    /** Replaces the whole diet plan (all planned meals) with a new one, in one transaction. */
    suspend fun replaceDietPlan(accountId: Long, drafts: List<MealDraft>, source: String = "USER"): Change =
        db.withTransaction {
            meals.deletePlanned(accountId)
            drafts.forEach { d ->
                meals.insert(
                    MealEntity(
                        accountId = accountId, name = d.name, mealType = d.mealType, kcal = d.kcal,
                        proteinG = d.proteinG, carbsG = d.carbsG, fatG = d.fatG, planned = true,
                        plannedFor = d.plannedFor, source = source
                    )
                )
            }
            val cid = changes.insert(
                ChangeRecordEntity(
                    accountId = accountId, entity = "MEAL", entityId = 0, action = "REPLACE",
                    summary = "ডায়েট প্ল্যান বসানো হয়েছে (${drafts.size}টি meal, প্রায় ${drafts.sumOf { it.kcal ?: 0 }} kcal)\n" +
                        drafts.joinToString("\n") { "• ${it.name}" },
                    source = source
                )
            )
            Change(0, cid)
        }

    suspend fun deleteMeal(meal: MealEntity, source: String = "USER"): Change = db.withTransaction {
        meals.delete(meal)
        val cid = changes.insert(
            ChangeRecordEntity(
                accountId = meal.accountId, entity = "MEAL", entityId = meal.id, action = "DELETE",
                summary = "Meal মুছে ফেলা হয়েছে: ${meal.name}", beforeJson = meal.toJson(), source = source
            )
        )
        Change(meal.id, cid)
    }

    suspend fun addWater(accountId: Long, ml: Int, source: String = "USER"): Change = db.withTransaction {
        val log = WaterLogEntity(accountId = accountId, ml = ml, source = source)
        val id = water.insert(log)
        val cid = changes.insert(
            ChangeRecordEntity(
                accountId = accountId, entity = "WATER", entityId = id, action = "CREATE",
                summary = "পানি লগ: $ml মিলি", afterJson = log.copy(id = id).toJson(), source = source
            )
        )
        Change(id, cid)
    }
}
