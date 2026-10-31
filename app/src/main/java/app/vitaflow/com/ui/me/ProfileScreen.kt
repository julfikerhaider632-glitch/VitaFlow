package app.vitaflow.com.ui.me

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.vitaflow.com.AppContainer
import app.vitaflow.com.data.db.AccountEntity
import app.vitaflow.com.data.db.ProfileEntity
import app.vitaflow.com.ui.components.toDoubleLoose
import app.vitaflow.com.ui.components.toIntLoose
import app.vitaflow.com.ui.theme.GlassButton
import app.vitaflow.com.ui.theme.GlassChip
import app.vitaflow.com.ui.theme.GlassTextField
import app.vitaflow.com.ui.theme.LocalGlass
import kotlinx.coroutines.launch

@Composable
fun ProfileScreen(account: AccountEntity, container: AppContainer) {
    val g = LocalGlass.current
    val scope = rememberCoroutineScope()
    var gender by remember { mutableStateOf("") }
    var height by remember { mutableStateOf("") }
    var weight by remember { mutableStateOf("") }
    var goal by remember { mutableStateOf("MAINTAIN") }
    var level by remember { mutableStateOf("BEGINNER") }
    var equipment by remember { mutableStateOf("NONE") }
    var diet by remember { mutableStateOf("") }
    var allergies by remember { mutableStateOf("") }
    var injuries by remember { mutableStateOf("") }
    var water by remember { mutableStateOf("2500") }
    var message by remember { mutableStateOf<String?>(null) }
    var oldWeight by remember { mutableStateOf<Double?>(null) }

    LaunchedEffect(account.id) {
        container.profileRepo.get(account.id)?.let { p ->
            gender = p.gender
            height = p.heightCm?.toString().orEmpty()
            weight = p.weightKg?.let { "%.1f".format(it) }.orEmpty()
            oldWeight = p.weightKg
            goal = p.goal; level = p.level; equipment = p.equipment
            diet = p.dietPref; allergies = p.allergies; injuries = p.injuries
            water = p.waterTargetMl.toString()
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "এই তথ্য শুধু আপনার ফোনে থাকে। Max আর plan বানানোর কাজে এটা ব্যবহার হয়।",
            color = g.textSecondary, style = MaterialTheme.typography.bodyMedium
        )
        Text("লিঙ্গ", color = g.textPrimary, style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GlassChip("পুরুষ", gender == "MALE", { gender = "MALE" })
            GlassChip("মহিলা", gender == "FEMALE", { gender = "FEMALE" })
            GlassChip("বলতে চাই না", gender == "", { gender = "" })
        }
        GlassTextField(height, { height = it }, "উচ্চতা (সেমি)", keyboardType = KeyboardType.Number)
        GlassTextField(weight, { weight = it }, "ওজন (কেজি)", keyboardType = KeyboardType.Decimal)

        Text("লক্ষ্য", color = g.textPrimary, style = MaterialTheme.typography.titleMedium)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GlassChip("ওজন কমানো", goal == "LOSE_FAT", { goal = "LOSE_FAT" })
            GlassChip("ফিট থাকা", goal == "MAINTAIN", { goal = "MAINTAIN" })
            GlassChip("পেশি বানানো", goal == "BUILD_MUSCLE", { goal = "BUILD_MUSCLE" })
        }
        Text("অভিজ্ঞতা", color = g.textPrimary, style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GlassChip("শুরুর", level == "BEGINNER", { level = "BEGINNER" })
            GlassChip("মধ্যম", level == "INTERMEDIATE", { level = "INTERMEDIATE" })
            GlassChip("উন্নত", level == "ADVANCED", { level = "ADVANCED" })
        }
        Text("যন্ত্রপাতি", color = g.textPrimary, style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GlassChip("কিছু নেই", equipment == "NONE", { equipment = "NONE" })
            GlassChip("ডাম্বেল", equipment == "DUMBBELL", { equipment = "DUMBBELL" })
            GlassChip("জিম", equipment == "GYM", { equipment = "GYM" })
        }
        GlassTextField(diet, { diet = it }, "খাবারের ধরন (যেমন নিরামিষ, না লিখলে সব)")
        GlassTextField(allergies, { allergies = it }, "অ্যালার্জি (যেমন ডিম, বাদাম, দুধ)")
        GlassTextField(injuries, { injuries = it }, "আঘাত বা সমস্যা (যেমন হাঁটু, কাঁধ, কোমর)")
        GlassTextField(water, { water = it }, "দৈনিক পানির লক্ষ্য (মিলি)", keyboardType = KeyboardType.Number)
        message?.let { Text(it, color = g.accent, style = MaterialTheme.typography.bodyMedium) }
        GlassButton("সংরক্ষণ করুন", {
            val h = height.toIntLoose()
            val w = weight.toDoubleLoose()
            when {
                height.isNotBlank() && (h == null || h !in 80..250) -> message = "উচ্চতা ৮০ থেকে ২৫০ সেমির মধ্যে লিখুন"
                weight.isNotBlank() && (w == null || w !in 20.0..300.0) -> message = "ওজন ২০ থেকে ৩০০ কেজির মধ্যে লিখুন"
                else -> scope.launch {
                    container.profileRepo.save(
                        ProfileEntity(
                            accountId = account.id, gender = gender, heightCm = h, weightKg = w, goal = goal, level = level,
                            equipment = equipment, dietPref = diet.trim(), allergies = allergies.trim(), injuries = injuries.trim(),
                            waterTargetMl = (water.toIntLoose() ?: 2500).coerceIn(500, 8000)
                        )
                    )
                    if (w != null && w != oldWeight) {
                        container.profileRepo.logWeight(account.id, w)
                        oldWeight = w
                    }
                    message = "সংরক্ষিত হয়েছে ✓"
                }
            }
        }, Modifier.fillMaxWidth())
    }
}
