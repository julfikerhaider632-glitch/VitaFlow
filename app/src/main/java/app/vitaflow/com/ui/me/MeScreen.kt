package app.vitaflow.com.ui.me

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.vitaflow.com.AppContainer
import app.vitaflow.com.data.db.AccountEntity
import app.vitaflow.com.ui.settings.SettingsScreen
import app.vitaflow.com.ui.theme.GlassChip
import app.vitaflow.com.ui.theme.LocalGlass

/** "আমি" tab: progress, profile and settings under one roof. */
@Composable
fun MeScreen(account: AccountEntity, container: AppContainer, onLogout: () -> Unit, onClearChat: () -> Unit) {
    val g = LocalGlass.current
    var part by rememberSaveable { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("আমি", style = MaterialTheme.typography.headlineMedium, color = g.textPrimary)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GlassChip("প্রগতি", part == 0, { part = 0 })
            GlassChip("প্রোফাইল", part == 1, { part = 1 })
            GlassChip("স্মৃতি", part == 2, { part = 2 })
            GlassChip("সেটিংস", part == 3, { part = 3 })
        }
        when (part) {
            0 -> ProgressScreen(account, container)
            1 -> ProfileScreen(account, container)
            2 -> MemoryScreen(account, container)
            else -> SettingsScreen(account, container, onLogout, onClearChat)
        }
    }
}
