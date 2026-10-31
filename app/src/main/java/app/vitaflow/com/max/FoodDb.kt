package app.vitaflow.com.max

import app.vitaflow.com.data.MealDraft
import java.time.LocalDateTime
import java.util.Locale
import kotlin.math.roundToInt

/** One serving of a common food. The numbers are rough averages, so every result is only an estimate. */
data class Food(
    val key: String,
    val label: String,
    val aliases: List<String>,
    val servingText: String,
    val kcal: Int,
    val protein: Double,
    val carbs: Double,
    val fat: Double,
    val plateFactor: Double = 1.0
)

object FoodDb {
    val foods: List<Food> = listOf(
        Food("rice", "ভাত", listOf("ভাত", "rice", "bhat"), "১ কাপ (প্রায় ১৬০ গ্রাম)", 205, 4.2, 45.0, 0.4, plateFactor = 2.0),
        Food("roti", "রুটি", listOf("রুটি", "roti", "chapati"), "১টি", 120, 3.5, 22.0, 2.5),
        Food("paratha", "পরোটা", listOf("পরোটা", "paratha", "parota"), "১টি", 260, 5.0, 30.0, 13.0),
        Food("dal", "ডাল", listOf("ডাল", "dal", "lentil"), "১ বাটি", 125, 7.5, 19.0, 2.0),
        Food("egg", "ডিম", listOf("ডিম", "egg"), "১টি সেদ্ধ", 78, 6.3, 0.6, 5.3),
        Food("omelette", "অমলেট", listOf("অমলেট", "omelette", "omelet"), "১টি", 130, 8.0, 1.0, 10.0),
        Food("chicken", "মুরগির মাংস", listOf("মুরগি", "মুরগীর", "chicken"), "১ টুকরা (প্রায় ১০০ গ্রাম)", 190, 24.0, 2.0, 9.0),
        Food("fish", "মাছ", listOf("মাছ", "fish"), "১ টুকরা", 150, 17.0, 2.0, 8.0),
        Food("beef", "গরুর মাংস", listOf("গরুর মাংস", "গরু", "beef"), "১ টুকরা", 230, 20.0, 3.0, 16.0),
        Food("veg", "সবজি", listOf("সবজি", "ভাজি", "vegetable", "veg"), "১ বাটি", 80, 2.0, 9.0, 4.0),
        Food("potato", "আলু", listOf("আলু", "potato"), "১টি মাঝারি", 130, 3.0, 30.0, 0.2),
        Food("khichuri", "খিচুড়ি", listOf("খিচুড়ি", "khichuri", "khichdi"), "১ প্লেট", 330, 11.0, 55.0, 7.0),
        Food("biryani", "বিরিয়ানি", listOf("বিরিয়ানি", "biryani", "বিরিয়ানী"), "১ প্লেট", 550, 22.0, 65.0, 20.0),
        Food("muri", "মুড়ি", listOf("মুড়ি", "muri"), "১ বাটি", 110, 2.0, 24.0, 0.3),
        Food("bread", "পাউরুটি", listOf("পাউরুটি", "bread", "ব্রেড"), "১ স্লাইস", 75, 2.5, 14.0, 1.0),
        Food("oats", "ওটস", listOf("ওটস", "oats", "oatmeal"), "১ বাটি", 150, 5.0, 27.0, 3.0),
        Food("chola", "ছোলা", listOf("ছোলা", "chickpea", "chola"), "১ বাটি সেদ্ধ", 164, 9.0, 27.0, 2.6),
        Food("banana", "কলা", listOf("কলা", "banana"), "১টি", 105, 1.3, 27.0, 0.4),
        Food("apple", "আপেল", listOf("আপেল", "apple"), "১টি", 95, 0.5, 25.0, 0.3),
        Food("milk", "দুধ", listOf("দুধ", "milk"), "১ গ্লাস", 150, 8.0, 12.0, 8.0),
        Food("yogurt", "দই", listOf("দই", "doi", "yogurt", "yoghurt"), "১ বাটি", 90, 4.0, 10.0, 4.0),
        Food("tea", "চা", listOf("চা", "tea"), "১ কাপ (দুধ-চিনি সহ)", 60, 1.5, 9.0, 2.0),
        Food("nuts", "বাদাম", listOf("বাদাম", "peanut", "nuts"), "১ মুঠো (৩০ গ্রাম)", 170, 7.5, 5.0, 14.0),
        Food("salad", "সালাদ", listOf("সালাদ", "salad"), "১ বাটি", 40, 1.5, 7.0, 0.5),
        Food("shake", "প্রোটিন শেক", listOf("প্রোটিন শেক", "protein shake", "shake"), "১ স্কুপ", 120, 24.0, 3.0, 1.5)
    )

    fun byKey(key: String): Food? = foods.firstOrNull { it.key == key }

    private val eatVerb = Regex("খেয়েছি|খেলাম|খাচ্ছি|খেয়ে ফেলেছি|খেয়ে নিয়েছি|খেয়েছিলাম|\\bate\\b|\\bhad\\b|eaten|eating")

    private fun containsAlias(low: String, alias: String): Int {
        if (alias.length > 2) return low.indexOf(alias)
        return Regex("(?<!\\p{L})${Regex.escape(alias)}(?!\\p{L})").find(low)?.range?.first ?: -1
    }

    private fun quantityBefore(low: String, idx: Int, food: Food): Double {
        val before = low.substring(maxOf(0, idx - 16), idx)
        if (Regex("(আধা|আধ|half)\\s*(?:কাপ|বাটি|গ্লাস|প্লেট|টুকরা)?\\s*$").containsMatchIn(before)) return 0.5
        val m = Regex("(\\d+(?:\\.\\d+)?)\\s*(টা|টি|টো|কাপ|বাটি|গ্লাস|প্লেট|পিস|টুকরা|টুকরো|cups?|bowls?|glass(?:es)?|plates?|pieces?|slices?)?\\s*$").find(before)
            ?: return 1.0
        var q = m.groupValues[1].toDoubleOrNull() ?: 1.0
        if (m.groupValues[2] in listOf("প্লেট", "plate", "plates")) q *= food.plateFactor
        return q.coerceIn(0.25, 10.0)
    }

    private fun mealType(low: String, now: LocalDateTime): String = when {
        Regex("সকালে|সকালের|নাস্তা|নাশতা|breakfast").containsMatchIn(low) -> "BREAKFAST"
        Regex("দুপুরে|দুপুরের|lunch").containsMatchIn(low) -> "LUNCH"
        Regex("রাতে|রাতের|dinner").containsMatchIn(low) -> "DINNER"
        Regex("বিকেলে|বিকালে|snack|স্ন্যাক").containsMatchIn(low) -> "SNACK"
        else -> when (now.hour) {
            in 5..10 -> "BREAKFAST"
            in 11..15 -> "LUNCH"
            in 16..18 -> "SNACK"
            else -> "DINNER"
        }
    }

    private fun fmt(q: Double): String = if (q == q.toInt().toDouble()) q.toInt().toString() else String.format(Locale.US, "%.1f", q)

    /** A meal from food keys and servings, e.g. what the AI saw on a plate. Totals come from the table above. */
    fun draftFor(items: List<Pair<String, Double>>, now: LocalDateTime): MealDraft? {
        val found = items.mapNotNull { (k, s) -> byKey(k)?.let { it to s.coerceIn(0.25, 6.0) } }.distinctBy { it.first.key }
        if (found.isEmpty()) return null
        return MealDraft(
            name = found.joinToString(", ") { (f, q) -> if (q == 1.0) f.label else "${f.label} ×${fmt(q)}" },
            mealType = mealType("", now),
            kcal = found.sumOf { (f, q) -> f.kcal * q }.roundToInt(),
            proteinG = found.sumOf { (f, q) -> f.protein * q }.let { (it * 10).roundToInt() / 10.0 },
            carbsG = found.sumOf { (f, q) -> f.carbs * q }.let { (it * 10).roundToInt() / 10.0 },
            fatG = found.sumOf { (f, q) -> f.fat * q }.let { (it * 10).roundToInt() / 10.0 }
        )
    }

    /** "২ কাপ ভাত আর ১ বাটি ডাল খেয়েছি" -> a meal with calories and protein worked out from the table above. */
    fun parseMeal(text: String, now: LocalDateTime): MealDraft? {
        val low = TimeParser.normalize(text).lowercase()
        if (!eatVerb.containsMatchIn(low)) return null
        val found = ArrayList<Pair<Food, Double>>()
        for (f in foods) {
            for (a in f.aliases) {
                val idx = containsAlias(low, a)
                if (idx >= 0) {
                    found += f to quantityBefore(low, idx, f)
                    break
                }
            }
        }
        if (found.isEmpty()) return null
        return MealDraft(
            name = found.joinToString(", ") { (f, q) -> if (q == 1.0) f.label else "${f.label} ×${fmt(q)}" },
            mealType = mealType(low, now),
            kcal = found.sumOf { (f, q) -> f.kcal * q }.roundToInt(),
            proteinG = found.sumOf { (f, q) -> f.protein * q }.let { (it * 10).roundToInt() / 10.0 },
            carbsG = found.sumOf { (f, q) -> f.carbs * q }.let { (it * 10).roundToInt() / 10.0 },
            fatG = found.sumOf { (f, q) -> f.fat * q }.let { (it * 10).roundToInt() / 10.0 }
        )
    }
}
