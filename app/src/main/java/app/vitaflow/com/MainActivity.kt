package app.vitaflow.com

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.vitaflow.com.data.ThemeMode
import app.vitaflow.com.data.auth.SessionState
import app.vitaflow.com.ui.auth.AuthScreen
import app.vitaflow.com.ui.auth.AuthViewModel
import app.vitaflow.com.ui.home.HomeScreen
import app.vitaflow.com.ui.theme.GlassBackground
import app.vitaflow.com.ui.theme.LocalGlass
import app.vitaflow.com.ui.theme.VitaFlowTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as VitaFlowApp).container
        setContent {
            val mode by container.settingsStore.themeMode.collectAsStateWithLifecycle(initialValue = ThemeMode.SYSTEM)
            val dark = when (mode) {
                ThemeMode.DARK -> true
                ThemeMode.LIGHT -> false
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
            }
            val reduce by container.settingsStore.reduceEffects.collectAsStateWithLifecycle(initialValue = false)
            VitaFlowTheme(dark = dark, reduceEffects = reduce) { AppRoot(container) }
        }
    }
}

@Composable
private fun AppRoot(container: AppContainer) {
    val vm: AuthViewModel = viewModel(factory = viewModelFactory {
        initializer { AuthViewModel(container.authRepo) }
    })
    val session by vm.session.collectAsStateWithLifecycle()

    // Task reminders need the notification permission (Android 13 and newer). Ask once after login.
    val context = LocalContext.current
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val loggedIn = session is SessionState.LoggedIn
    LaunchedEffect(loggedIn) {
        if (loggedIn && Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    GlassBackground {
        Box(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
            when (val s = session) {
                SessionState.Loading -> CircularProgressIndicator(
                    Modifier.align(Alignment.Center),
                    color = LocalGlass.current.accent
                )
                SessionState.LoggedOut -> AuthScreen(vm)
                is SessionState.LoggedIn -> HomeScreen(s.account, container, vm)
            }
        }
    }
}
