package app.vitaflow.com.ui.diet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.vitaflow.com.AppContainer
import app.vitaflow.com.data.MealDraft
import app.vitaflow.com.data.MealRepository
import app.vitaflow.com.data.db.AccountEntity
import app.vitaflow.com.data.db.MealEntity
import app.vitaflow.com.max.DietPlanner
import app.vitaflow.com.ui.components.ConfirmDialog
import app.vitaflow.com.ui.components.toDoubleLoose
import app.vitaflow.com.ui.components.toIntLoose
import app.vitaflow.com.ui.theme.GlassButton
import app.vitaflow.com.ui.theme.GlassChip
import app.vitaflow.com.ui.theme.GlassSurface
import app.vitaflow.com.ui.theme.GlassTextField
import app.vitaflow.com.ui.theme.LocalGlass
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

private val mealTypes = listOf("BREAKFAST" to "ব্রেকফাস্ট", "LUNCH" to "লাঞ্চ", "DINNER" to "ডিনার", "SNACK" to "স্ন্যাক")

private fun mealTypeLabel(t: String) = mealTypes.firstOrNull { it.first == t }?.second ?: "অন্যান্য"

@Composable
fun DietScreen(account: AccountEntity, container: AppContainer) {
    val repo = container.mealRepo
    val g = LocalGlass.current
    val meals by repo.observeMeals(account.id).collectAsStateWithLifecycle(initialValue = emptyList())
    val water by repo.observeWater(account.id).collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()

    val startOfDay = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    val todayMeals = meals.filter { !it.planned && it.loggedAt >= startOfDay }
    val planned = meals.filter { it.planned }
    val waterToday = water.filter { it.loggedAt >= startOfDay }.sumOf { it.ml }

    var customWater by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var type by remember { mutableStateOf("BREAKFAST") }
    var kcal by remember { mutableStateOf("") }
    var protein by remember { mutableStateOf("") }
    var carbs by remember { mutableStateOf("") }
    var fat by remember { mutableStateOf("") }
    var isPlan by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var deleteMeal by remember { mutableStateOf<MealEntity?>(null) }
    var planMessage by remember { mutableStateOf<String?>(null) }
    var confirmReplace by remember { mutableStateOf(false) }
    var planRequest by remember { mutableStateOf("") }
    var busyPlan by remember { mutableStateOf(false) }

    suspend fun buildPlan() {
        val birth = container.db.accountDao().get(account.id)?.birthDate?.takeIf { it.isNotBlank() }
        val age = birth?.let { runCatching { java.time.Period.between(LocalDate.parse(it), LocalDate.now()).years }.getOrNull() }
        val profile = container.profileRepo.get(account.id)
        val t = DietPlanner.target(profile, age)
        if (t.error != null) {
            planMessage = t.error
        } else {
            busyPlan = true
            val ai = container.aiPlanner.diet(profile, age, planRequest)
            val r = ai ?: DietPlanner.build(profile, age)
            repo.replaceDietPlan(account.id, r.drafts)
            planMessage = "দৈনিক প্রায় ${r.targetKcal} kcal-এর প্ল্যান বানানো হয়েছে। " +
                (if (ai == null) "(AI পাওয়া যায়নি বা উত্তর ঠিক ছিল না, তাই তৈরি ছাঁচ ব্যবহার হয়েছে) " else "") + r.note
            busyPlan = false
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("ডায়েট", style = MaterialTheme.typography.headlineMedium, color = g.textPrimary)

        GlassSurface(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("আজকের হিসাব", color = g.textPrimary, style = MaterialTheme.typography.titleMedium)
                Text(
                    "${todayMeals.sumOf { it.kcal ?: 0 }} kcal · প্রোটিন ${"%.0f".format(todayMeals.sumOf { it.proteinG ?: 0.0 })} গ্রাম · " +
                        "কার্ব ${"%.0f".format(todayMeals.sumOf { it.carbsG ?: 0.0 })} গ্রাম · ফ্যাট ${"%.0f".format(todayMeals.sumOf { it.fatG ?: 0.0 })} গ্রাম",
                    color = g.textSecondary, style = MaterialTheme.typography.bodyLarge
                )
                Text("পানি: $waterToday মিলি", color = g.accent, style = MaterialTheme.typography.titleMedium)
            }
        }

        planMessage?.let { Text(it, color = g.accent, style = MaterialTheme.typography.bodyMedium) }
        GlassTextField(planRequest, { planRequest = it }, "বিশেষ চাওয়া (ইচ্ছা হলে, যেমন: রাতে ভাত না, মাছ বেশি)")
        GlassButton(if (busyPlan) "AI বানাচ্ছে... (কিছুক্ষণ লাগবে)" else "প্রোফাইল দেখে ডায়েট প্ল্যান বানান", {
            if (planned.isNotEmpty()) confirmReplace = true else scope.launch { buildPlan() }
        }, Modifier.fillMaxWidth(), enabled = !busyPlan)

        Text("পানি লগ করুন", color = g.textPrimary, style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GlassChip("+২৫০ মিলি", false, { scope.launch { repo.addWater(account.id, 250) } })
            GlassChip("+৫০০ মিলি", false, { scope.launch { repo.addWater(account.id, 500) } })
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GlassTextField(customWater, { customWater = it }, "অন্য পরিমাণ (মিলি)", Modifier.weight(1f), keyboardType = KeyboardType.Number)
            GlassButton("যোগ", {
                val ml = customWater.toIntLoose()
                if (ml != null && ml in 50..3000) {
                    scope.launch { repo.addWater(account.id, ml) }
                    customWater = ""
                }
            }, enabled = (customWater.toIntLoose() ?: 0) in 50..3000)
        }

        Text("Meal যোগ করুন", color = g.textPrimary, style = MaterialTheme.typography.titleMedium)
        GlassTextField(name, { name = it }, "খাবারের নাম")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            mealTypes.forEach { (key, label) -> GlassChip(label, type == key, { type = key }) }
        }
        GlassTextField(kcal, { kcal = it }, "ক্যালরি (kcal)", keyboardType = KeyboardType.Number)
        GlassTextField(protein, { protein = it }, "প্রোটিন (গ্রাম)", keyboardType = KeyboardType.Decimal)
        GlassTextField(carbs, { carbs = it }, "কার্বোহাইড্রেট (গ্রাম)", keyboardType = KeyboardType.Decimal)
        GlassTextField(fat, { fat = it }, "ফ্যাট (গ্রাম)", keyboardType = KeyboardType.Decimal)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Switch(
                checked = isPlan, onCheckedChange = { isPlan = it },
                colors = SwitchDefaults.colors(checkedTrackColor = g.accent)
            )
            Text("এটা ডায়েট প্ল্যানের অংশ (এখনো খাইনি)", color = g.textSecondary, style = MaterialTheme.typography.bodyMedium)
        }
        error?.let { Text(it, color = g.danger, style = MaterialTheme.typography.bodyMedium) }
        GlassButton("সংরক্ষণ করুন", {
            val k = kcal.toIntLoose()
            when {
                name.isBlank() -> error = "খাবারের নাম লিখুন"
                kcal.isNotBlank() && (k == null || k !in 0..5000) -> error = "ক্যালরি ০ থেকে ৫০০০-এর মধ্যে হতে হবে"
                else -> {
                    error = null
                    val draft = MealDraft(
                        name = name.trim(), mealType = type, kcal = k,
                        proteinG = protein.toDoubleLoose()?.takeIf { it in 0.0..500.0 },
                        carbsG = carbs.toDoubleLoose()?.takeIf { it in 0.0..800.0 },
                        fatG = fat.toDoubleLoose()?.takeIf { it in 0.0..500.0 },
                        planned = isPlan
                    )
                    scope.launch { repo.addMeal(account.id, draft) }
                    name = ""; kcal = ""; protein = ""; carbs = ""; fat = ""
                }
            }
        }, Modifier.fillMaxWidth())

        MealList("আজকের meal", todayMeals, onDelete = { deleteMeal = it })
        MealList("ডায়েট প্ল্যান", planned, onDelete = { deleteMeal = it })
    }

    if (confirmReplace) {
        ConfirmDialog("আগের ডায়েট প্ল্যান মুছে নতুনটা বসাবেন?", "বসান", onConfirm = { scope.launch { buildPlan() } }, onDismiss = { confirmReplace = false })
    }
    deleteMeal?.let { m ->
        ConfirmDialog("\"${m.name}\" মুছে ফেলবেন?", onConfirm = { scope.launch { repo.deleteMeal(m) } }, onDismiss = { deleteMeal = null })
    }
}

@Composable
private fun MealList(title: String, list: List<MealEntity>, onDelete: (MealEntity) -> Unit) {
    val g = LocalGlass.current
    if (list.isEmpty()) return
    Text(title, color = g.textPrimary, style = MaterialTheme.typography.titleMedium)
    list.forEach { m ->
        GlassSurface(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(m.name, color = g.textPrimary, style = MaterialTheme.typography.titleMedium)
                    Text(
                        mealTypeLabel(m.mealType) + (m.kcal?.let { " · $it kcal" } ?: "") +
                            (m.proteinG?.let { " · প্রোটিন ${"%.0f".format(it)} গ্রাম" } ?: ""),
                        color = g.textSecondary, style = MaterialTheme.typography.bodyMedium
                    )
                }
                IconButton(onClick = { onDelete(m) }) {
                    Icon(Icons.Rounded.Delete, contentDescription = "মুছুন", tint = g.danger)
                }
            }
        }
    }
}
