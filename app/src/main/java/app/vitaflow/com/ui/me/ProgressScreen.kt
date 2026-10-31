package app.vitaflow.com.ui.me

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.vitaflow.com.AppContainer
import app.vitaflow.com.data.DayValue
import app.vitaflow.com.data.db.AccountEntity
import app.vitaflow.com.ui.components.toDoubleLoose
import app.vitaflow.com.ui.theme.GlassButton
import app.vitaflow.com.ui.theme.GlassSurface
import app.vitaflow.com.ui.theme.GlassTextField
import app.vitaflow.com.ui.theme.LocalGlass
import kotlinx.coroutines.launch

@Composable
fun ProgressScreen(account: AccountEntity, container: AppContainer) {
    val g = LocalGlass.current
    val progress by container.progressRepo.observe(account.id).collectAsStateWithLifecycle(initialValue = null)
    val scope = rememberCoroutineScope()
    var weightText by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val p = progress

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (p == null) {
            Text("লোড হচ্ছে...", color = g.textSecondary)
            return@Column
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatCard("${p.streak} দিন", "workout streak", Modifier.weight(1f))
            StatCard("${p.sessions7}টি", "এই সপ্তাহে workout", Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatCard("${p.tasksDone7}টি", "টাস্ক সম্পন্ন (৭ দিন)", Modifier.weight(1f))
            StatCard("${p.tasksOpen}টি", "টাস্ক বাকি", Modifier.weight(1f))
        }

        Text("ওজন", color = g.textPrimary, style = MaterialTheme.typography.titleLarge)
        GlassSurface(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val points = p.weights.takeLast(20).map { it.kg }
                if (points.size >= 2) {
                    LineChart(points, g.accent, Modifier.fillMaxWidth().height(120.dp))
                    Text(
                        "${"%.1f".format(points.first())} → ${"%.1f".format(points.last())} কেজি",
                        color = g.textSecondary, style = MaterialTheme.typography.bodyMedium
                    )
                } else {
                    Text("চার্ট দেখতে অন্তত দুটো ওজন লগ করুন।", color = g.textSecondary, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GlassTextField(weightText, { weightText = it }, "আজকের ওজন (কেজি)", Modifier.weight(1f), keyboardType = KeyboardType.Decimal)
            GlassButton("লগ", {
                val kg = weightText.toDoubleLoose()
                if (kg == null || kg !in 20.0..300.0) {
                    error = "ওজন ২০ থেকে ৩০০ কেজির মধ্যে লিখুন"
                } else {
                    error = null
                    weightText = ""
                    scope.launch { container.profileRepo.logWeight(account.id, kg) }
                }
            })
        }
        error?.let { Text(it, color = g.danger, style = MaterialTheme.typography.bodyMedium) }

        Text("পানি (মিলি, ৭ দিন)", color = g.textPrimary, style = MaterialTheme.typography.titleLarge)
        BarChart(p.waterByDay, g.accent)
        Text("ক্যালরি (kcal, ৭ দিন)", color = g.textPrimary, style = MaterialTheme.typography.titleLarge)
        BarChart(p.kcalByDay, g.success)
    }
}

@Composable
private fun StatCard(value: String, label: String, modifier: Modifier) {
    val g = LocalGlass.current
    GlassSurface(modifier) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(value, color = g.textPrimary, style = MaterialTheme.typography.titleLarge)
            Text(label, color = g.textSecondary, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun LineChart(values: List<Double>, color: Color, modifier: Modifier) {
    Canvas(modifier) {
        if (values.size < 2) return@Canvas
        val min = values.min()
        val range = (values.max() - min).coerceAtLeast(0.5)
        val stepX = size.width / (values.size - 1)
        val path = Path()
        values.forEachIndexed { i, v ->
            val x = i * stepX
            val y = size.height - (((v - min) / range) * size.height * 0.8 + size.height * 0.1).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            drawCircle(color, 5f, Offset(x, y))
        }
        drawPath(path, color, style = Stroke(width = 4f))
    }
}

@Composable
private fun BarChart(values: List<DayValue>, color: Color) {
    val g = LocalGlass.current
    val maxV = (values.maxOfOrNull { it.value } ?: 0.0).coerceAtLeast(1.0)
    GlassSurface(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().height(140.dp).padding(14.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.Bottom
        ) {
            values.forEach { d ->
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom, modifier = Modifier.fillMaxHeight()) {
                    Box(
                        Modifier.width(18.dp).height((90 * (d.value / maxV)).dp.coerceAtLeast(2.dp))
                            .background(color, RoundedCornerShape(6.dp))
                    )
                    Text("${d.date.dayOfMonth}", color = g.textSecondary, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}
