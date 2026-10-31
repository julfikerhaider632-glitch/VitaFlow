package app.vitaflow.com.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChatBubble
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.FitnessCenter
import androidx.compose.material.icons.rounded.Restaurant
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import app.vitaflow.com.AppContainer
import app.vitaflow.com.data.db.AccountEntity
import app.vitaflow.com.ui.auth.AuthViewModel
import app.vitaflow.com.ui.chat.ChatScreen
import app.vitaflow.com.ui.diet.DietScreen
import app.vitaflow.com.ui.me.MeScreen
import app.vitaflow.com.ui.tasks.TasksScreen
import app.vitaflow.com.ui.theme.GlassSurface
import app.vitaflow.com.ui.theme.LocalGlass
import app.vitaflow.com.ui.workout.WorkoutScreen
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope

enum class HomeTab(val label: String, val icon: ImageVector) {
    CHAT("চ্যাট", Icons.Rounded.ChatBubble),
    TASKS("টাস্ক", Icons.Rounded.Checklist),
    WORKOUT("ওয়ার্কআউট", Icons.Rounded.FitnessCenter),
    DIET("ডায়েট", Icons.Rounded.Restaurant),
    ME("আমি", Icons.Rounded.Person)
}

/** Which screen shows the thing a change record is about. */
fun tabForEntity(entity: String): HomeTab = when (entity) {
    "TASK" -> HomeTab.TASKS
    "WORKOUT", "EXERCISE" -> HomeTab.WORKOUT
    "MEAL", "WATER" -> HomeTab.DIET
    "SESSION" -> HomeTab.WORKOUT
    "WEIGHT" -> HomeTab.ME
    else -> HomeTab.CHAT
}

@Composable
fun HomeScreen(account: AccountEntity, container: AppContainer, authVm: AuthViewModel) {
    var tab by rememberSaveable { mutableStateOf(HomeTab.CHAT) }
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) {
            when (tab) {
                HomeTab.CHAT -> ChatScreen(account, container, onNavigate = { tab = it })
                HomeTab.TASKS -> TasksScreen(account, container.taskRepo)
                HomeTab.WORKOUT -> WorkoutScreen(account, container)
                HomeTab.DIET -> DietScreen(account, container)
                HomeTab.ME -> MeScreen(
                    account, container,
                    onLogout = authVm::logout,
                    onClearChat = { scope.launch { container.chatRepo.clearAll(account.id) } }
                )
            }
        }
        BottomBar(selected = tab, onSelect = { tab = it })
    }
}

@Composable
private fun BottomBar(selected: HomeTab, onSelect: (HomeTab) -> Unit) {
    val g = LocalGlass.current
    GlassSurface(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
        shape = RoundedCornerShape(28.dp)
    ) {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            HomeTab.entries.forEach { t ->
                val color = if (t == selected) g.accent else g.textSecondary
                Column(
                    Modifier.clickable { onSelect(t) }.padding(horizontal = 6.dp, vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(t.icon, contentDescription = t.label, tint = color, modifier = Modifier.size(24.dp))
                    Text(t.label, color = color, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}
