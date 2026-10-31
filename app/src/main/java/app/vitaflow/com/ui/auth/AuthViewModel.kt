package app.vitaflow.com.ui.auth

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.vitaflow.com.data.auth.AuthRepository
import app.vitaflow.com.data.auth.AuthResult
import app.vitaflow.com.data.auth.GoogleIdentity
import app.vitaflow.com.data.auth.RegisterForm
import app.vitaflow.com.data.auth.SessionState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class AuthViewModel(private val repo: AuthRepository) : ViewModel() {
    val session: StateFlow<SessionState> =
        repo.sessionFlow().stateIn(viewModelScope, SharingStarted.Eagerly, SessionState.Loading)

    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var info by mutableStateOf<String?>(null)
        private set
    var recoveryQuestion by mutableStateOf<String?>(null)
        private set

    fun clearMessages() {
        error = null
        info = null
        recoveryQuestion = null
    }

    private fun launchAuth(onOk: () -> Unit = {}, block: suspend () -> AuthResult) {
        viewModelScope.launch {
            busy = true
            error = null
            info = null
            when (val r = block()) {
                is AuthResult.Ok -> onOk()
                is AuthResult.Err -> error = r.message
            }
            busy = false
        }
    }

    fun login(identifier: String, password: String) = launchAuth { repo.login(identifier, password) }

    fun register(form: RegisterForm) = launchAuth { repo.register(form) }

    fun loginWithGoogle(identity: GoogleIdentity) = launchAuth { repo.loginWithGoogle(identity) }

    fun showError(message: String) {
        error = message
    }

    fun startRecovery(identifier: String) {
        viewModelScope.launch {
            busy = true
            error = null
            val q = repo.recoveryQuestion(identifier)
            if (q == null) error = "এই email বা ফোন নম্বরে account নেই, অথবা account-টা Google দিয়ে খোলা (নিরাপত্তা প্রশ্ন নেই)" else recoveryQuestion = q
            busy = false
        }
    }

    fun resetPassword(identifier: String, answer: String, newPassword: String, confirm: String, onDone: () -> Unit) =
        launchAuth(onOk = {
            recoveryQuestion = null
            info = "পাসওয়ার্ড বদলানো হয়েছে। এবার প্রবেশ করুন।"
            onDone()
        }) { repo.resetPassword(identifier, answer, newPassword, confirm) }

    fun logout() {
        viewModelScope.launch { repo.logout() }
    }
}
