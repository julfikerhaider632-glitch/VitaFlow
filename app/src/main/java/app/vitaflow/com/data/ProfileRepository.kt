package app.vitaflow.com.data

import androidx.room.withTransaction
import app.vitaflow.com.data.db.ChangeRecordEntity
import app.vitaflow.com.data.db.ProfileEntity
import app.vitaflow.com.data.db.VitaDatabase
import app.vitaflow.com.data.db.WeightLogEntity
import kotlinx.coroutines.flow.Flow
import org.json.JSONObject

class ProfileRepository(private val db: VitaDatabase) {
    private val profiles = db.profileDao()
    private val weights = db.weightDao()
    private val changes = db.changeDao()

    fun observe(accountId: Long): Flow<ProfileEntity?> = profiles.observe(accountId)
    fun observeWeights(accountId: Long): Flow<List<WeightLogEntity>> = weights.observe(accountId)
    suspend fun get(accountId: Long): ProfileEntity? = profiles.get(accountId)

    suspend fun save(profile: ProfileEntity) = profiles.upsert(profile)

    /** Logs a weight, and keeps the profile's current weight in step. */
    suspend fun logWeight(accountId: Long, kg: Double, source: String = "USER"): Change = db.withTransaction {
        val id = weights.insert(WeightLogEntity(accountId = accountId, kg = kg, source = source))
        val p = profiles.get(accountId) ?: ProfileEntity(accountId = accountId)
        profiles.upsert(p.copy(weightKg = kg))
        val cid = changes.insert(
            ChangeRecordEntity(
                accountId = accountId, entity = "WEIGHT", entityId = id, action = "CREATE",
                summary = "ওজন লগ: ${"%.1f".format(kg)} কেজি",
                beforeJson = JSONObject().put("kg", p.weightKg ?: JSONObject.NULL).toString(),
                afterJson = JSONObject().put("kg", kg).toString(), source = source
            )
        )
        Change(id, cid)
    }

    /** One line about the person for Max's prompt. Empty if nothing is filled in. */
    suspend fun summaryLine(accountId: Long): String {
        val p = profiles.get(accountId) ?: return ""
        val parts = mutableListOf<String>()
        parts += "goal=" + when (p.goal) { "LOSE_FAT" -> "lose fat"; "BUILD_MUSCLE" -> "build muscle"; else -> "stay fit" }
        p.weightKg?.let { parts += "weight=${"%.0f".format(it)}kg" }
        p.heightCm?.let { parts += "height=${it}cm" }
        parts += "level=${p.level.lowercase()}"
        if (p.allergies.isNotBlank()) parts += "allergies=${p.allergies.take(60)}"
        if (p.injuries.isNotBlank()) parts += "injuries=${p.injuries.take(60)}"
        if (p.dietPref.isNotBlank()) parts += "diet=${p.dietPref.take(40)}"
        return parts.joinToString(", ")
    }
}
