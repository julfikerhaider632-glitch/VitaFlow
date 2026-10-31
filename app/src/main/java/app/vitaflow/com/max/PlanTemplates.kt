package app.vitaflow.com.max

import app.vitaflow.com.data.ExerciseDraft
import app.vitaflow.com.data.MealDraft
import app.vitaflow.com.data.db.ProfileEntity
import kotlin.math.roundToInt

data class PlanResult(val name: String, val exercises: List<ExerciseDraft>, val note: String)

data class DietResult(val drafts: List<MealDraft>, val targetKcal: Int, val note: String, val error: String? = null)

/**
 * Builds plans from ready-made templates and the saved profile, with no AI involved, so it is instant
 * and predictable. Injuries remove the exercises that could make them worse.
 */
object PlanTemplates {
    private enum class Kind { BODY, DUMBBELL, GYM }

    private data class Ex(val key: String, val name: String, val kind: Kind, val timed: Boolean, val howTo: String, val avoid: Set<String>)

    private val library = listOf(
        Ex("pushup", "Push-ups", Kind.BODY, false, "হাত কাঁধের চেয়ে একটু চওড়া রাখুন। শরীর সোজা রেখে বুক মাটির কাছে নামান, তারপর ঠেলে উঠুন।", setOf("shoulder", "wrist")),
        Ex("squat", "Squats", Kind.BODY, false, "পা কাঁধ-চওড়া, পিঠ সোজা। চেয়ারে বসার মতো নিচে নামুন, গোড়ালিতে চাপ দিয়ে উঠুন।", setOf("knee")),
        Ex("lunge", "Lunges", Kind.BODY, false, "এক পা সামনে বাড়িয়ে দুই হাঁটু প্রায় ৯০° বাঁকান, তারপর ফিরে আসুন। পা বদলান।", setOf("knee")),
        Ex("bridge", "Glute bridge", Kind.BODY, false, "চিত হয়ে হাঁটু ভাঁজ করে শুয়ে কোমর উপরে তুলুন। উপরে ১ সেকেন্ড ধরে রাখুন।", emptySet()),
        Ex("plank", "Plank", Kind.BODY, true, "কনুইয়ের ওপর ভর দিয়ে শরীর সোজা রাখুন। কোমর ঝুলে পড়তে দেবেন না।", setOf("shoulder", "wrist", "back")),
        Ex("crunch", "Crunches", Kind.BODY, false, "চিত হয়ে হাঁটু ভাঁজ করুন। ঘাড় না টেনে পেটের পেশি দিয়ে কাঁধ সামান্য তুলুন।", setOf("back")),
        Ex("birddog", "Bird-dog", Kind.BODY, false, "হাত-হাঁটুর ওপর ভর দিয়ে উল্টো হাত ও পা সোজা করে ১ সেকেন্ড ধরে রাখুন।", emptySet()),
        Ex("jacks", "Jumping jacks", Kind.BODY, true, "লাফ দিয়ে পা ও হাত একসাথে ছড়ান ও গুটান। ছন্দ ধরে রাখুন।", setOf("knee")),
        Ex("climber", "Mountain climbers", Kind.BODY, true, "পুশ-আপ অবস্থানে এক এক করে হাঁটু বুকের দিকে টানুন।", setOf("shoulder", "wrist", "knee")),
        Ex("superman", "Superman", Kind.BODY, false, "উপুড় হয়ে হাত-পা একসাথে সামান্য তুলে ১-২ সেকেন্ড ধরে রাখুন।", setOf("back")),
        Ex("goblet", "Goblet squat", Kind.DUMBBELL, false, "ডাম্বেল বুকের কাছে ধরে স্কোয়াট করুন।", setOf("knee")),
        Ex("floorpress", "Dumbbell floor press", Kind.DUMBBELL, false, "মেঝেতে চিত হয়ে ডাম্বেল বুকের ওপর ঠেলে তুলুন।", setOf("shoulder")),
        Ex("row", "Dumbbell row", Kind.DUMBBELL, false, "এক হাত ও হাঁটু বেঞ্চে রেখে অন্য হাতে ডাম্বেল কোমরের দিকে টানুন।", setOf("back")),
        Ex("press", "Dumbbell shoulder press", Kind.DUMBBELL, false, "ডাম্বেল কাঁধের পাশ থেকে মাথার ওপর ঠেলে তুলুন।", setOf("shoulder")),
        Ex("rdl", "Romanian deadlift", Kind.DUMBBELL, false, "হাঁটু সামান্য ভাঁজ করে পিঠ সোজা রেখে কোমর থেকে সামনে ঝুঁকুন।", setOf("back")),
        Ex("curl", "Bicep curls", Kind.DUMBBELL, false, "কনুই শরীরের পাশে রেখে ডাম্বেল কাঁধের দিকে তুলুন।", emptySet()),
        Ex("legpress", "Leg press", Kind.GYM, false, "পিঠ সিটে ঠেকিয়ে ধীরে প্ল্যাটফর্ম ঠেলুন। হাঁটু পুরো সোজা করবেন না।", setOf("knee")),
        Ex("chestpress", "Chest press machine", Kind.GYM, false, "হাতল ধীরে সামনে ঠেলুন ও ফিরিয়ে আনুন।", setOf("shoulder")),
        Ex("pulldown", "Lat pulldown", Kind.GYM, false, "বার বুকের দিকে টানুন। কাঁধ নিচে রাখুন।", setOf("shoulder")),
        Ex("seatedrow", "Seated cable row", Kind.GYM, false, "পিঠ সোজা রেখে হাতল পেটের দিকে টানুন।", setOf("back"))
    )

    private val orders = mapOf(
        "NONE" to listOf("squat", "pushup", "bridge", "birddog", "plank", "lunge", "crunch", "superman"),
        "DUMBBELL" to listOf("goblet", "floorpress", "row", "press", "rdl", "curl", "bridge", "plank"),
        "GYM" to listOf("legpress", "chestpress", "pulldown", "seatedrow", "press", "plank", "bridge", "curl")
    )

    fun injuryTags(injuries: String): Set<String> {
        val inj = injuries.lowercase()
        return buildSet {
            if (Regex("knee|হাঁটু").containsMatchIn(inj)) add("knee")
            if (Regex("shoulder|কাঁধ").containsMatchIn(inj)) add("shoulder")
            if (Regex("back|কোমর|পিঠ").containsMatchIn(inj)) add("back")
            if (Regex("wrist|কব্জি").containsMatchIn(inj)) add("wrist")
        }
    }

    /** Words in an exercise name that should not appear for a given injury. Used to check what the AI proposes. */
    fun bannedWords(tags: Set<String>): Regex? {
        val words = mutableListOf<String>()
        if ("knee" in tags) words += listOf("squat", "lunge", "jump", "burpee", "climber", "wall sit", "leg press", "step")
        if ("shoulder" in tags) words += listOf("push", "press", "plank", "burpee", "climber", "pulldown", "pull-up", "dip")
        if ("back" in tags) words += listOf("deadlift", "sit-up", "situp", "crunch", "superman", "row", "plank", "burpee")
        if ("wrist" in tags) words += listOf("push", "plank", "climber", "burpee")
        return if (words.isEmpty()) null else Regex(words.joinToString("|"), RegexOption.IGNORE_CASE)
    }

    fun workout(p: ProfileEntity?): PlanResult {
        val level = p?.level ?: "BEGINNER"
        val goal = p?.goal ?: "MAINTAIN"
        val equipment = p?.equipment ?: "NONE"
        val inj = (p?.injuries ?: "").lowercase()
        val tags = buildSet {
            if (Regex("knee|হাঁটু").containsMatchIn(inj)) add("knee")
            if (Regex("shoulder|কাঁধ").containsMatchIn(inj)) add("shoulder")
            if (Regex("back|কোমর|পিঠ").containsMatchIn(inj)) add("back")
            if (Regex("wrist|কব্জি").containsMatchIn(inj)) add("wrist")
        }
        val count = when (level) { "ADVANCED" -> 7; "INTERMEDIATE" -> 6; else -> 5 }
        val keys = (orders[equipment] ?: orders.getValue("NONE")).toMutableList()
        if (goal == "LOSE_FAT") { keys += "jacks"; keys += "climber" }
        val chosen = keys.mapNotNull { k -> library.firstOrNull { it.key == k } }
            .filter { e -> e.avoid.none { it in tags } }
            .distinctBy { it.key }
            .let { list ->
                if (goal == "LOSE_FAT") {
                    val cardio = list.filter { it.key == "jacks" || it.key == "climber" }
                    (list.filterNot { it in cardio }.take(count - cardio.size) + cardio).take(count)
                } else list.take(count)
            }
        val sets = when (level) { "ADVANCED" -> 4; "INTERMEDIATE" -> 3; else -> 2 }
        var reps = when (level) { "ADVANCED" -> 15; "INTERMEDIATE" -> 12; else -> 10 }
        if (goal == "BUILD_MUSCLE") reps = if (level == "ADVANCED") 8 else 10
        if (goal == "LOSE_FAT") reps += 2
        val rest = when (goal) { "BUILD_MUSCLE" -> 90; "LOSE_FAT" -> 40; else -> 60 }
        val seconds = when (level) { "ADVANCED" -> 45; "INTERMEDIATE" -> 30; else -> 20 }
        val drafts = chosen.map { e ->
            ExerciseDraft(
                name = e.name, sets = sets, reps = if (e.timed) null else reps,
                durationSec = if (e.timed) seconds else null, restSec = rest, howTo = e.howTo
            )
        }
        val levelBn = when (level) { "ADVANCED" -> "উন্নত"; "INTERMEDIATE" -> "মধ্যম"; else -> "শুরুর" }
        val goalBn = when (goal) { "LOSE_FAT" -> "ওজন কমানো"; "BUILD_MUSCLE" -> "পেশি বানানো"; else -> "ফিট থাকা" }
        var note = "আপনার স্তর, লক্ষ্য ও আঘাত দেখে বানানো। ব্যথা হলে সঙ্গে সঙ্গে থামুন। এটা ডাক্তারের পরামর্শের বিকল্প নয়।"
        if (Regex("heart|হার্ট|প্রেশার|pressure|হাঁপানি|asthma").containsMatchIn(inj)) {
            note += " আপনার শারীরিক সমস্যার কারণে শুরুর আগে অবশ্যই ডাক্তারের সাথে কথা বলুন।"
        }
        return PlanResult("আমার ওয়ার্কআউট ($levelBn, $goalBn)", drafts, note)
    }
}

data class DietTarget(val kcal: Int, val note: String, val error: String? = null)

object DietPlanner {
    private data class Slot(val type: String, val share: Double)

    private val slots = listOf(Slot("BREAKFAST", 0.25), Slot("LUNCH", 0.35), Slot("SNACK", 0.10), Slot("DINNER", 0.30))
    val slotTypes: List<String> = slots.map { it.type }
    fun slotShare(type: String): Double = slots.firstOrNull { it.type == type }?.share ?: 0.25

    private val allergenWords = mapOf(
        "egg" to "egg|ডিম", "omelette" to "egg|ডিম", "yogurt" to "milk|dairy|দুধ|দই", "milk" to "milk|dairy|দুধ",
        "nuts" to "nut|বাদাম", "fish" to "fish|মাছ", "chicken" to "chicken|মুরগি", "beef" to "beef|গরু",
        "roti" to "gluten|wheat|আটা|গম", "bread" to "gluten|wheat|আটা|গম", "paratha" to "gluten|wheat|আটা|গম",
        "oats" to "gluten|wheat|oat|ওটস"
    )

    /** Daily calorie goal from a standard formula, with a safe floor. The numbers are always computed here, never guessed. */
    fun target(p: ProfileEntity?, age: Int?): DietTarget {
        val weight = p?.weightKg
        val height = p?.heightCm
        if (p == null || weight == null || height == null) {
            return DietTarget(0, "", "ডায়েট প্ল্যানের জন্য আগে প্রোফাইলে উচ্চতা ও ওজন দিন (আমি > প্রোফাইল)।")
        }
        if (age != null && age < 18) {
            return DietTarget(0, "", "১৮ বছরের কম বয়সীদের জন্য আমি ক্যালরির ডায়েট প্ল্যান বানাই না। একজন পুষ্টিবিদের সাথে কথা বলুন।")
        }
        val years = age ?: 25
        val bmr = 10 * weight + 6.25 * height - 5 * years + when (p.gender) { "MALE" -> 5.0; "FEMALE" -> -161.0; else -> -78.0 }
        val factor = when (p.level) { "ADVANCED" -> 1.6; "INTERMEDIATE" -> 1.45; else -> 1.3 }
        var target = bmr * factor * when (p.goal) { "LOSE_FAT" -> 0.85; "BUILD_MUSCLE" -> 1.10; else -> 1.0 }
        val floor = when (p.gender) { "MALE" -> 1500.0; "FEMALE" -> 1200.0; else -> 1350.0 }
        var note = "লক্ষ্য প্রায় ${target.roundToInt()} kcal/দিন (আনুমানিক হিসাব)। এটা ডাক্তার বা পুষ্টিবিদের পরামর্শের বিকল্প নয়।"
        if (target < floor) {
            target = floor
            note = "নিরাপত্তার জন্য ক্যালরি কমপক্ষে ${floor.roundToInt()} kcal রাখা হয়েছে। এর নিচে নামা ঠিক নয়। এটা আনুমানিক হিসাব, ডাক্তারের পরামর্শের বিকল্প নয়।"
        }
        return DietTarget(target.coerceAtMost(4000.0).roundToInt(), note)
    }

    fun isAllowed(p: ProfileEntity?, key: String): Boolean {
        val pref = (p?.dietPref ?: "").lowercase()
        val vegan = Regex("vegan|ভেগান").containsMatchIn(pref)
        val veg = vegan || Regex("veg|নিরামিষ").containsMatchIn(pref)
        if (veg && key in setOf("chicken", "fish", "beef")) return false
        if (vegan && key in setOf("egg", "omelette", "milk", "yogurt")) return false
        val words = allergenWords[key] ?: return true
        return !Regex(words).containsMatchIn((p?.allergies ?: "").lowercase())
    }

    private fun fmt(q: Double) = if (q == q.toInt().toDouble()) q.toInt().toString() else "%.1f".format(q)

    /**
     * Takes the foods chosen for one meal (by the template or by the AI) and fixes the amounts so the meal
     * adds up to its share of the daily calories. The arithmetic is always done here.
     */
    fun scaleSlot(type: String, items: List<Pair<String, Double>>, slotKcal: Double): MealDraft? {
        val foods = items.mapNotNull { (k, s) -> FoodDb.byKey(k)?.let { it to s.coerceIn(0.5, 4.0) } }.distinctBy { it.first.key }
        if (foods.isEmpty()) return null
        val base = foods.sumOf { (f, s) -> f.kcal * s }
        val factor = ((slotKcal / base) * 2).roundToInt() / 2.0
        val scale = factor.coerceIn(0.5, 3.0)
        val scaled = foods.map { (f, s) -> f to (s * scale * 2).roundToInt().coerceAtLeast(1) / 2.0 }
        return MealDraft(
            name = scaled.joinToString(", ") { (f, q) -> "${f.label} ×${fmt(q)}" },
            mealType = type,
            kcal = scaled.sumOf { (f, q) -> f.kcal * q }.roundToInt(),
            proteinG = (scaled.sumOf { (f, q) -> f.protein * q } * 10).roundToInt() / 10.0,
            carbsG = (scaled.sumOf { (f, q) -> f.carbs * q } * 10).roundToInt() / 10.0,
            fatG = (scaled.sumOf { (f, q) -> f.fat * q } * 10).roundToInt() / 10.0,
            planned = true, plannedFor = "DAILY"
        )
    }

    /** Ready-made plan, used when the AI is not available or its answer was not usable. */
    fun build(p: ProfileEntity?, age: Int?): DietResult {
        val t = target(p, age)
        if (t.error != null) return DietResult(emptyList(), 0, "", t.error)
        fun pick(vararg options: String): String? = options.firstOrNull { isAllowed(p, it) }
        val starch = pick("roti", "rice") ?: "rice"
        val protein = pick("egg", "chola", "dal")
        val lunchProtein = pick("fish", "chicken", "chola", "dal")
        val dinnerProtein = pick("chicken", "egg", "chola", "dal")
        val combos = mapOf(
            "BREAKFAST" to listOfNotNull(starch to 2.0, protein?.let { it to 1.5 }, "veg" to 1.0),
            "LUNCH" to listOfNotNull("rice" to 1.5, "dal".takeIf { isAllowed(p, it) }?.let { it to 1.0 }, lunchProtein?.let { it to 1.0 }, "veg" to 1.0, "salad" to 1.0),
            "SNACK" to listOfNotNull("banana" to 1.0, pick("nuts", "yogurt")?.let { it to 1.0 }),
            "DINNER" to listOfNotNull(starch to 2.0, dinnerProtein?.let { it to 1.0 }, "veg" to 1.0, "salad" to 1.0)
        )
        val drafts = slots.mapNotNull { slot -> scaleSlot(slot.type, combos.getValue(slot.type), t.kcal * slot.share) }
        return DietResult(drafts, t.kcal, t.note)
    }
}
