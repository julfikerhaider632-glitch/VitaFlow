package app.vitaflow.com.max

import app.vitaflow.com.ai.LlmEngine
import app.vitaflow.com.data.ExerciseDraft
import app.vitaflow.com.data.MealDraft
import app.vitaflow.com.data.db.ProfileEntity

/**
 * Lets the AI personalise plans: it picks the exercises and foods from the person's profile and their own words
 * ("রাতে ভাত খাই না", "হাঁটুতে ব্যথা"). Everything the AI writes is then checked here: limits, injuries,
 * allergies, and all calorie arithmetic is redone in code. Returns null when the AI is unavailable or its answer
 * is not safe to use, and the caller falls back to the ready-made template.
 */
class AiPlanner(private val llm: LlmEngine) {
    fun available(): Boolean = llm.isReady()

    private fun levelText(p: ProfileEntity?) = when (p?.level) { "ADVANCED" -> "advanced"; "INTERMEDIATE" -> "intermediate"; else -> "beginner" }
    private fun goalText(p: ProfileEntity?) = when (p?.goal) { "LOSE_FAT" -> "lose fat"; "BUILD_MUSCLE" -> "build muscle"; else -> "stay fit" }

    // ---------------------------------------------------------------- workout

    suspend fun workout(p: ProfileEntity?, request: String): PlanResult? {
        if (!available()) return null
        val system = """
You are a careful fitness coach. Create ONE workout plan. Reply with JSON only:
{"name":"...","exercises":[{"name":"Push-ups","sets":3,"reps":10,"seconds":null,"rest":60,"how_to":"..."}]}
Rules: 4 to 7 exercises. Use only exercises that fit the equipment. Skip anything that could hurt the injuries. Each exercise has reps OR seconds. Beginners get fewer sets and easy moves. Exercise names in English, how_to is one short Bengali sentence.
""".trimIndent()
        val equipment = when (p?.equipment) { "DUMBBELL" -> "dumbbells"; "GYM" -> "full gym"; else -> "no equipment (body weight)" }
        val user = "Level: ${levelText(p)}. Goal: ${goalText(p)}. Equipment: $equipment. Injuries: ${p?.injuries?.ifBlank { "none" } ?: "none"}. " +
            "Weight: ${p?.weightKg ?: "unknown"} kg, height: ${p?.heightCm ?: "unknown"} cm. Extra request: ${request.take(300).ifBlank { "none" }}"
        val raw = runCatching { llm.complete(system, user, temperature = 0.3) }.getOrNull() ?: return null
        val o = IntentValidator.extractJson(raw) ?: return null
        val arr = o.optJSONArray("exercises") ?: return null

        val banned = PlanTemplates.bannedWords(PlanTemplates.injuryTags(p?.injuries.orEmpty()))
        val maxSets = if (p?.level == "ADVANCED") 5 else if (p?.level == "INTERMEDIATE") 4 else 3
        val maxReps = if (p?.level == "ADVANCED") 25 else if (p?.level == "INTERMEDIATE") 20 else 15
        val list = ArrayList<ExerciseDraft>()
        for (i in 0 until minOf(arr.length(), 8)) {
            val e = arr.optJSONObject(i) ?: continue
            val name = e.optString("name").trim().take(40)
            if (name.isEmpty() || (banned != null && banned.containsMatchIn(name))) continue
            val reps = e.optInt("reps", 0).takeIf { !e.isNull("reps") && it in 4..30 }?.coerceAtMost(maxReps)
            val secs = e.optInt("seconds", 0).takeIf { !e.isNull("seconds") && it in 10..120 }
            if (reps == null && secs == null) continue
            list += ExerciseDraft(
                name = name,
                sets = e.optInt("sets", 3).coerceIn(1, maxSets),
                reps = reps,
                durationSec = if (reps == null) secs else null,
                restSec = e.optInt("rest", 60).coerceIn(15, 180),
                howTo = e.optString("how_to").trim().take(220)
            )
        }
        if (list.size < 3) return null
        val name = o.optString("name").trim().take(50).ifBlank { "আমার ওয়ার্কআউট (AI)" }
        val note = "AI আপনার প্রোফাইল ও চাওয়া দেখে বানিয়েছে; সীমা ও আঘাত app যাচাই করেছে। ব্যথা হলে থামুন। এটা ডাক্তারের পরামর্শের বিকল্প নয়।"
        return PlanResult(name, list, note)
    }

    // ---------------------------------------------------------------- diet

    suspend fun diet(p: ProfileEntity?, age: Int?, request: String): DietResult? {
        if (!available()) return null
        val t = DietPlanner.target(p, age)
        if (t.error != null) return null
        val menu = FoodDb.foods.filter { DietPlanner.isAllowed(p, it.key) }
        if (menu.size < 6) return null
        val system = """
You plan one day of meals for a person in Bangladesh. Use ONLY these foods (key = kcal per serving):
${menu.joinToString(", ") { "${it.key}=${it.kcal} (${it.servingText})" }}
Reply with JSON only: {"meals":[{"type":"BREAKFAST","items":[{"food":"roti","servings":2}]},{"type":"LUNCH","items":[...]},{"type":"SNACK","items":[...]},{"type":"DINNER","items":[...]}]}
Rules: 2 to 4 foods per meal. Follow the diet type and the extra request. Choose foods the person will actually enjoy. Do not worry about exact calories, the app corrects the amounts.
""".trimIndent()
        val user = "Goal: ${goalText(p)}. Diet type: ${p?.dietPref?.ifBlank { "no restriction" } ?: "no restriction"}. " +
            "Allergies: ${p?.allergies?.ifBlank { "none" } ?: "none"}. Extra request: ${request.take(300).ifBlank { "none" }}"
        val raw = runCatching { llm.complete(system, user, temperature = 0.3) }.getOrNull() ?: return null
        val o = IntentValidator.extractJson(raw) ?: return null
        val meals = o.optJSONArray("meals") ?: return null

        val drafts = ArrayList<MealDraft>()
        for (type in DietPlanner.slotTypes) {
            var items: List<Pair<String, Double>> = emptyList()
            for (i in 0 until meals.length()) {
                val m = meals.optJSONObject(i) ?: continue
                if (m.optString("type").uppercase() != type) continue
                val arr = m.optJSONArray("items") ?: continue
                items = (0 until minOf(arr.length(), 5)).mapNotNull { arr.optJSONObject(it) }.mapNotNull { obj ->
                    val key = obj.optString("food").trim().lowercase()
                    if (FoodDb.byKey(key) == null || !DietPlanner.isAllowed(p, key)) null else key to obj.optDouble("servings", 1.0).let { s -> if (s.isNaN()) 1.0 else s }
                }
            }
            val draft = DietPlanner.scaleSlot(type, items, t.kcal * DietPlanner.slotShare(type)) ?: return null
            drafts += draft
        }
        val note = "খাবার বেছেছে AI, আপনার চাওয়া অনুযায়ী; পরিমাণ ও ক্যালরি app হিসাব করে মিলিয়েছে। " + t.note
        return DietResult(drafts, t.kcal, note)
    }
}
