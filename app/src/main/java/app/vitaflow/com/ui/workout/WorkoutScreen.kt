package app.vitaflow.com.ui.workout

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
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.vitaflow.com.AppContainer
import app.vitaflow.com.data.ExerciseDraft
import app.vitaflow.com.data.db.AccountEntity
import app.vitaflow.com.data.db.WorkoutExerciseEntity
import app.vitaflow.com.data.db.WorkoutPlanEntity
import app.vitaflow.com.max.PlanTemplates
import app.vitaflow.com.ui.components.ConfirmDialog
import app.vitaflow.com.ui.components.GlassDialog
import app.vitaflow.com.ui.components.toDoubleLoose
import app.vitaflow.com.ui.components.toIntLoose
import app.vitaflow.com.ui.theme.GlassButton
import app.vitaflow.com.ui.theme.GlassChip
import app.vitaflow.com.ui.theme.GlassSurface
import app.vitaflow.com.ui.theme.GlassTextField
import app.vitaflow.com.ui.theme.LocalGlass
import app.vitaflow.com.workout.RunPhase
import app.vitaflow.com.workout.RunState
import kotlinx.coroutines.launch

private fun WorkoutExerciseEntity.summary(): String {
    val amount = reps?.let { "$sets × $it" } ?: "$sets × ${durationSec ?: 0} সে."
    val w = weightKg?.let { " · ${"%.1f".format(it)} কেজি" }.orEmpty()
    return "$amount$w · বিশ্রাম $restSec সে."
}

@Composable
fun WorkoutScreen(account: AccountEntity, container: AppContainer) {
    val repo = container.workoutRepo
    val runner = container.runner
    val g = LocalGlass.current
    val plans by repo.observePlans(account.id).collectAsStateWithLifecycle(initialValue = emptyList())
    val exercises by repo.observeExercises(account.id).collectAsStateWithLifecycle(initialValue = emptyList())
    val run by runner.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var showAdd by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<WorkoutExerciseEntity?>(null) }
    var deleteEx by remember { mutableStateOf<WorkoutExerciseEntity?>(null) }
    var deletePlan by remember { mutableStateOf<WorkoutPlanEntity?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var planRequest by remember { mutableStateOf("") }
    var busyPlan by remember { mutableStateOf(false) }

    if (run.phase != RunPhase.IDLE) {
        RunPanel(run, container)
        return
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("ওয়ার্কআউট", style = MaterialTheme.typography.headlineMedium, color = g.textPrimary)
        message?.let { Text(it, color = g.accent, style = MaterialTheme.typography.bodyMedium) }
        GlassButton("নতুন exercise যোগ করুন", { showAdd = true }, Modifier.fillMaxWidth())
        GlassTextField(planRequest, { planRequest = it }, "বিশেষ চাওয়া (ইচ্ছা হলে, যেমন: ঘরে করার মতো, সময় কম)")
        GlassButton(if (busyPlan) "AI বানাচ্ছে... (কিছুক্ষণ লাগবে)" else "প্রোফাইল দেখে ওয়ার্কআউট বানান", {
            scope.launch {
                busyPlan = true
                val profile = container.profileRepo.get(account.id)
                val ai = container.aiPlanner.workout(profile, planRequest)
                val r = ai ?: PlanTemplates.workout(profile)
                repo.createPlan(account.id, r.name, r.exercises)
                message = "\"${r.name}\" বানানো হয়েছে। " + (if (ai == null) "(AI পাওয়া যায়নি বা উত্তর ঠিক ছিল না, তাই তৈরি ছাঁচ ব্যবহার হয়েছে) " else "") + r.note
                busyPlan = false
            }
        }, Modifier.fillMaxWidth(), enabled = !busyPlan)
        if (plans.isEmpty()) {
            Text(
                "এখনো কোনো ওয়ার্কআউট নেই। উপরের বোতামে চেপে যোগ করুন, অথবা Max-কে বলুন।",
                color = g.textSecondary, style = MaterialTheme.typography.bodyLarge
            )
        }
        plans.forEach { plan ->
            GlassSurface(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(plan.name, color = g.textPrimary, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                        IconButton(onClick = { deletePlan = plan }) {
                            Icon(Icons.Rounded.Delete, contentDescription = "ওয়ার্কআউট মুছুন", tint = g.danger)
                        }
                    }
                    val list = exercises.filter { it.planId == plan.id }
                    list.forEach { ex ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(ex.name, color = g.textPrimary, style = MaterialTheme.typography.titleMedium)
                                Text(ex.summary(), color = g.textSecondary, style = MaterialTheme.typography.bodyMedium)
                                if (ex.howTo.isNotBlank()) Text(ex.howTo, color = g.textSecondary, style = MaterialTheme.typography.bodyMedium)
                            }
                            IconButton(onClick = { editing = ex }) { Icon(Icons.Rounded.Edit, contentDescription = "বদলান", tint = g.accent) }
                            IconButton(onClick = { deleteEx = ex }) { Icon(Icons.Rounded.Delete, contentDescription = "মুছুন", tint = g.danger) }
                        }
                    }
                    GlassButton("শুরু করুন", {
                        scope.launch { message = runner.start(account.id, plan, list).text }
                    }, Modifier.fillMaxWidth(), enabled = list.isNotEmpty())
                }
            }
        }
    }

    if (showAdd) {
        ExerciseDialog(
            title = "নতুন exercise", initial = null, askPlanName = true,
            defaultPlan = plans.firstOrNull()?.name ?: "আমার workout",
            onDismiss = { showAdd = false },
            onSave = { planName, draft -> scope.launch { repo.addExercise(account.id, planName, draft) }; showAdd = false }
        )
    }
    editing?.let { ex ->
        ExerciseDialog(
            title = "Exercise বদলান", initial = ex, askPlanName = false, defaultPlan = "",
            onDismiss = { editing = null },
            onSave = { _, d ->
                scope.launch {
                    repo.updateExercise(
                        account.id, ex,
                        ex.copy(name = d.name, sets = d.sets, reps = d.reps, durationSec = d.durationSec,
                            weightKg = d.weightKg, restSec = d.restSec, howTo = d.howTo)
                    )
                }
                editing = null
            }
        )
    }
    deleteEx?.let { ex ->
        ConfirmDialog("\"${ex.name}\" মুছে ফেলবেন?", onConfirm = { scope.launch { repo.deleteExercise(account.id, ex) } }, onDismiss = { deleteEx = null })
    }
    deletePlan?.let { p ->
        ConfirmDialog("\"${p.name}\" ওয়ার্কআউট এবং এর সব exercise মুছে ফেলবেন?", onConfirm = { scope.launch { repo.deletePlan(account.id, p) } }, onDismiss = { deletePlan = null })
    }
}

/** The screen shown while a workout is running: what to do now, the rest countdown, and the buttons. */
@Composable
private fun RunPanel(run: RunState, container: AppContainer) {
    val g = LocalGlass.current
    val runner = container.runner
    val scope = rememberCoroutineScope()
    var repsText by remember(run.exIndex, run.setNo) { mutableStateOf("") }
    var confirmFinish by remember { mutableStateOf(false) }
    val ex = run.current

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(run.planName, style = MaterialTheme.typography.titleMedium, color = g.textSecondary)

        if (run.phase == RunPhase.FINISHED) {
            GlassSurface(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("দারুণ! শেষ হয়েছে 🎉", color = g.textPrimary, style = MaterialTheme.typography.headlineMedium)
                    Text(run.summary, color = g.textSecondary, style = MaterialTheme.typography.bodyLarge)
                    GlassButton("ঠিক আছে", { runner.dismiss() }, Modifier.fillMaxWidth())
                }
            }
            return@Column
        }

        Text(
            "Exercise ${run.exIndex + 1}/${run.exercises.size}" + (ex?.let { " · set ${run.setNo}/${it.sets}" } ?: ""),
            color = g.textSecondary, style = MaterialTheme.typography.bodyLarge
        )
        GlassSurface(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                if (run.phase == RunPhase.REST) {
                    Text("বিশ্রাম", color = g.accent, style = MaterialTheme.typography.titleLarge)
                    Text("${run.restLeft}", color = g.textPrimary, fontSize = 72.sp)
                    Text("সেকেন্ড", color = g.textSecondary)
                    GlassButton("বিশ্রাম বাদ দিন", { scope.launch { runner.skipRest() } }, Modifier.fillMaxWidth())
                } else if (ex != null) {
                    Text(ex.name, color = g.textPrimary, style = MaterialTheme.typography.headlineMedium)
                    if (ex.durationSec != null) {
                        Text("${run.workLeft}", color = g.textPrimary, fontSize = 72.sp)
                        Text("সেকেন্ড বাকি", color = g.textSecondary)
                    } else {
                        Text("${ex.reps ?: 0} বার", color = g.accent, fontSize = 40.sp)
                    }
                    if (ex.howTo.isNotBlank()) Text(ex.howTo, color = g.textSecondary, style = MaterialTheme.typography.bodyMedium)
                    if (ex.durationSec == null) {
                        GlassTextField(repsText, { repsText = it }, "আসলে কত বার করলেন (ফাঁকা = ${ex.reps ?: 0})", keyboardType = KeyboardType.Number)
                    }
                    GlassButton("set শেষ", { scope.launch { runner.setDone(repsText.toIntLoose()) } }, Modifier.fillMaxWidth())
                }
            }
        }
        run.exercises.getOrNull(run.exIndex + 1)?.let {
            Text("এরপর: ${it.name}", color = g.textSecondary, style = MaterialTheme.typography.bodyMedium)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GlassButton("পরের exercise", { scope.launch { runner.next() } }, Modifier.weight(1f))
            GlassButton("শেষ করুন", { confirmFinish = true }, Modifier.weight(1f), danger = true)
        }
    }
    if (confirmFinish) {
        ConfirmDialog("ওয়ার্কআউট এখনই শেষ করবেন?", "শেষ করুন", onConfirm = { scope.launch { runner.finish() } }, onDismiss = { confirmFinish = false })
    }
}

@Composable
private fun ExerciseDialog(
    title: String,
    initial: WorkoutExerciseEntity?,
    askPlanName: Boolean,
    defaultPlan: String,
    onDismiss: () -> Unit,
    onSave: (planName: String, draft: ExerciseDraft) -> Unit
) {
    val g = LocalGlass.current
    var plan by remember { mutableStateOf(defaultPlan) }
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var sets by remember { mutableStateOf((initial?.sets ?: 3).toString()) }
    var reps by remember { mutableStateOf(initial?.reps?.toString().orEmpty()) }
    var secs by remember { mutableStateOf(initial?.durationSec?.toString().orEmpty()) }
    var weight by remember { mutableStateOf(initial?.weightKg?.toString().orEmpty()) }
    var rest by remember { mutableStateOf((initial?.restSec ?: 60).toString()) }
    var howTo by remember { mutableStateOf(initial?.howTo.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }

    GlassDialog(title, onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (askPlanName) GlassTextField(plan, { plan = it }, "ওয়ার্কআউটের নাম")
            GlassTextField(name, { name = it }, "Exercise-এর নাম (যেমন Push-ups)")
            GlassTextField(sets, { sets = it }, "set", keyboardType = KeyboardType.Number)
            GlassTextField(reps, { reps = it }, "প্রতি set-এ rep", keyboardType = KeyboardType.Number)
            GlassTextField(secs, { secs = it }, "অথবা সময় (সেকেন্ড)", keyboardType = KeyboardType.Number)
            GlassTextField(weight, { weight = it }, "ওজন (কেজি, ইচ্ছা হলে)", keyboardType = KeyboardType.Decimal)
            GlassTextField(rest, { rest = it }, "বিশ্রাম (সেকেন্ড)", keyboardType = KeyboardType.Number)
            GlassTextField(howTo, { howTo = it }, "কীভাবে করবেন (ইচ্ছা হলে)")
            error?.let { Text(it, color = g.danger, style = MaterialTheme.typography.bodyMedium) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GlassButton("বাতিল", onDismiss, Modifier.weight(1f))
            GlassButton("সংরক্ষণ", {
                val s = sets.toIntLoose()
                val r = reps.toIntLoose()
                val d = secs.toIntLoose()
                val rs = rest.toIntLoose()
                when {
                    name.isBlank() -> error = "Exercise-এর নাম লিখুন"
                    s == null || s !in 1..20 -> error = "set ১ থেকে ২০-এর মধ্যে হতে হবে"
                    r == null && d == null -> error = "rep অথবা সময় — একটা দিন"
                    r != null && r !in 1..500 -> error = "rep ১ থেকে ৫০০-এর মধ্যে হতে হবে"
                    d != null && d !in 5..3600 -> error = "সময় ৫ থেকে ৩৬০০ সেকেন্ডের মধ্যে হতে হবে"
                    rs == null || rs !in 0..600 -> error = "বিশ্রাম ০ থেকে ৬০০ সেকেন্ডের মধ্যে হতে হবে"
                    askPlanName && plan.isBlank() -> error = "ওয়ার্কআউটের নাম লিখুন"
                    else -> onSave(
                        plan.trim(),
                        ExerciseDraft(
                            name = name.trim(), sets = s, reps = r, durationSec = d,
                            weightKg = weight.toDoubleLoose()?.takeIf { it in 0.0..500.0 },
                            restSec = rs, howTo = howTo
                        )
                    )
                }
            }, Modifier.weight(1f))
        }
    }
}
