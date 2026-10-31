package app.vitaflow.com

import android.app.Application
import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import app.vitaflow.com.ai.AttachmentManager
import app.vitaflow.com.ai.LiveListener
import app.vitaflow.com.ai.LlmEngine
import app.vitaflow.com.ai.LocalAsr
import app.vitaflow.com.ai.LocalTts
import app.vitaflow.com.ai.ModelManager
import app.vitaflow.com.ai.SecretStore
import app.vitaflow.com.ai.TavilyClient
import app.vitaflow.com.ai.VoiceRecorder
import app.vitaflow.com.data.ChatRepository
import app.vitaflow.com.data.MealRepository
import app.vitaflow.com.data.ProfileRepository
import app.vitaflow.com.data.ProgressRepository
import app.vitaflow.com.data.UndoManager
import app.vitaflow.com.data.SessionStore
import app.vitaflow.com.data.SettingsStore
import app.vitaflow.com.data.TaskRepository
import app.vitaflow.com.data.WorkoutRepository
import app.vitaflow.com.data.auth.AuthRepository
import app.vitaflow.com.data.backup.BackupManager
import app.vitaflow.com.data.db.VitaDatabase
import app.vitaflow.com.max.ActionEngine
import app.vitaflow.com.max.AiPlanner
import app.vitaflow.com.max.LlmRouter
import app.vitaflow.com.max.MaxEngine
import app.vitaflow.com.max.MemoryManager
import app.vitaflow.com.reminders.ReminderScheduler
import app.vitaflow.com.workout.WorkoutRunner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Simple manual dependency container. Can be swapped for Hilt later without touching the screens. */
class AppContainer(context: Context) {
    val db: VitaDatabase = VitaDatabase.build(context)
    val sessionStore = SessionStore(context)
    val settingsStore = SettingsStore(context)
    val secrets = SecretStore(context)

    val authRepo = AuthRepository(db.accountDao(), sessionStore)
    val reminders = ReminderScheduler(context)
    val taskRepo = TaskRepository(db, reminders)
    val workoutRepo = WorkoutRepository(db)
    val mealRepo = MealRepository(db)
    val chatRepo = ChatRepository(db)
    val profileRepo = ProfileRepository(db)
    val progressRepo = ProgressRepository(db)
    val undo = UndoManager(db, reminders)
    val runner = WorkoutRunner(db)
    val backup = BackupManager(db, sessionStore)

    val models = ModelManager(context)
    val asr = LocalAsr(models)
    val tts = LocalTts(models)
    val llm = LlmEngine(context, models)
    val recorder = VoiceRecorder()
    val listener = LiveListener()
    val web = TavilyClient(secrets, settingsStore)

    val aiPlanner = AiPlanner(llm)
    val attachments = AttachmentManager(context)
    val memory = MemoryManager(db, llm, profileRepo, settingsStore)
    private val actions = ActionEngine(db, taskRepo, workoutRepo, mealRepo, profileRepo, progressRepo, runner, undo, aiPlanner)
    val max = MaxEngine(llm, LlmRouter(llm), actions, web, profileRepo, settingsStore, memory)

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val tone by lazy { ToneGenerator(AudioManager.STREAM_MUSIC, 80) }

    init {
        reminders.ensureChannel()
        reminders.scheduleBackupNudge()
        // Workout coach: spoken cues (if "read answers aloud" is on) and a beep when a rest ends.
        CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
            runner.announcements.collect { text ->
                if (settingsStore.speakReplies.first()) runCatching { tts.speak(text) }
            }
        }
        CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
            runner.beeps.collect { runCatching { tone.startTone(ToneGenerator.TONE_PROP_BEEP, 250) } }
        }
    }
}

class VitaFlowApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
