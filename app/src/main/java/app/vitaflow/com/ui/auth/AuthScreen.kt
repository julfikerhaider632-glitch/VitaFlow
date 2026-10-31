package app.vitaflow.com.ui.auth

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import app.vitaflow.com.R
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.vitaflow.com.data.auth.GoogleResult
import app.vitaflow.com.data.auth.RegisterForm
import app.vitaflow.com.data.auth.requestGoogleIdentity
import app.vitaflow.com.data.auth.SecurityQuestions
import app.vitaflow.com.ui.theme.GlassButton
import app.vitaflow.com.ui.theme.GlassSurface
import app.vitaflow.com.ui.theme.GlassTextField
import app.vitaflow.com.ui.theme.LocalGlass

private enum class AuthMode { LOGIN, REGISTER, RECOVER }

@Composable
fun AuthScreen(vm: AuthViewModel) {
    var mode by rememberSaveable { mutableStateOf(AuthMode.LOGIN) }
    val g = LocalGlass.current

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Image(
            painter = painterResource(R.drawable.vita_logo),
            contentDescription = "Vita Flow",
            modifier = Modifier.size(104.dp).clip(RoundedCornerShape(26.dp))
        )
        Text("Vita Flow", style = MaterialTheme.typography.headlineMedium, color = g.textPrimary)
        Text(
            when (mode) {
                AuthMode.LOGIN -> "আপনার account-এ প্রবেশ করুন"
                AuthMode.REGISTER -> "নতুন account খুলুন"
                AuthMode.RECOVER -> "নিরাপত্তা প্রশ্নের উত্তর দিয়ে পাসওয়ার্ড বদলান"
            },
            color = g.textSecondary,
            style = MaterialTheme.typography.bodyLarge
        )
        vm.error?.let { Text(it, color = g.danger, style = MaterialTheme.typography.bodyMedium) }
        vm.info?.let { Text(it, color = g.success, style = MaterialTheme.typography.bodyMedium) }

        when (mode) {
            AuthMode.LOGIN -> LoginForm(
                vm,
                onRegister = { vm.clearMessages(); mode = AuthMode.REGISTER },
                onForgot = { vm.clearMessages(); mode = AuthMode.RECOVER }
            )
            AuthMode.REGISTER -> RegisterContent(vm, onBack = { vm.clearMessages(); mode = AuthMode.LOGIN })
            AuthMode.RECOVER -> RecoverForm(vm, onBack = { vm.clearMessages(); mode = AuthMode.LOGIN })
        }
    }
}

@Composable
private fun LinkText(text: String, onClick: () -> Unit) {
    val g = LocalGlass.current
    Text(
        text,
        color = g.accent,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp)
    )
}

@Composable
private fun LoginForm(vm: AuthViewModel, onRegister: () -> Unit, onForgot: () -> Unit) {
    var identifier by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    GlassTextField(identifier, { identifier = it }, "email বা ফোন নম্বর", keyboardType = KeyboardType.Email)
    GlassTextField(password, { password = it }, "পাসওয়ার্ড", password = true)
    GlassButton(
        text = if (vm.busy) "অপেক্ষা করুন..." else "প্রবেশ করুন",
        onClick = { vm.login(identifier, password) },
        enabled = !vm.busy && identifier.isNotBlank() && password.isNotEmpty(),
        modifier = Modifier.fillMaxWidth()
    )
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    GlassButton(
        text = "Google দিয়ে প্রবেশ",
        onClick = {
            scope.launch {
                when (val r = requestGoogleIdentity(context)) {
                    is GoogleResult.Ok -> vm.loginWithGoogle(r.identity)
                    is GoogleResult.Err -> vm.showError(r.message)
                }
            }
        },
        enabled = !vm.busy,
        modifier = Modifier.fillMaxWidth()
    )
    LinkText("পাসওয়ার্ড ভুলে গেছেন?", onForgot)
    LinkText("নতুন account খুলুন", onRegister)
}

@Composable
private fun RegisterContent(vm: AuthViewModel, onBack: () -> Unit) {
    val g = LocalGlass.current
    var name by rememberSaveable { mutableStateOf("") }
    var phone by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    var birth by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var questionId by rememberSaveable { mutableIntStateOf(0) }
    var answer by rememberSaveable { mutableStateOf("") }

    GlassTextField(name, { name = it }, "নাম")
    GlassTextField(phone, { phone = it }, "ফোন নম্বর", keyboardType = KeyboardType.Phone)
    GlassTextField(email, { email = it }, "email", keyboardType = KeyboardType.Email)
    GlassTextField(birth, { birth = it }, "জন্মতারিখ (দিন/মাস/বছর)", keyboardType = KeyboardType.Number)
    GlassTextField(password, { password = it }, "পাসওয়ার্ড (কমপক্ষে ৮ অক্ষর)", password = true)
    GlassTextField(confirm, { confirm = it }, "পাসওয়ার্ড আবার লিখুন", password = true)

    Text("নিরাপত্তা প্রশ্ন বেছে নিন", color = g.textPrimary, style = MaterialTheme.typography.titleMedium)
    Text(
        "পাসওয়ার্ড ভুলে গেলে এই প্রশ্নের উত্তর দিয়ে account ফিরে পাবেন।",
        color = g.textSecondary,
        style = MaterialTheme.typography.bodyMedium
    )
    GlassSurface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(vertical = 6.dp)) {
            SecurityQuestions.all.forEach { (id, text) ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { questionId = id }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = questionId == id,
                        onClick = null,
                        colors = RadioButtonDefaults.colors(
                            selectedColor = g.accent,
                            unselectedColor = g.textSecondary
                        )
                    )
                    Text(
                        text,
                        color = g.textPrimary,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(start = 12.dp)
                    )
                }
            }
        }
    }
    GlassTextField(answer, { answer = it }, "আপনার উত্তর")

    GlassButton(
        text = if (vm.busy) "অপেক্ষা করুন..." else "account খুলুন",
        onClick = {
            vm.register(RegisterForm(name, phone, email, birth, password, confirm, questionId, answer))
        },
        enabled = !vm.busy,
        modifier = Modifier.fillMaxWidth()
    )
    LinkText("আগেই account আছে? প্রবেশ করুন", onBack)
}

@Composable
private fun RecoverForm(vm: AuthViewModel, onBack: () -> Unit) {
    val g = LocalGlass.current
    var identifier by rememberSaveable { mutableStateOf("") }
    var answer by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }

    GlassTextField(identifier, { identifier = it }, "email বা ফোন নম্বর", keyboardType = KeyboardType.Email)
    val question = vm.recoveryQuestion
    if (question == null) {
        GlassButton(
            text = if (vm.busy) "অপেক্ষা করুন..." else "প্রশ্ন দেখুন",
            onClick = { vm.startRecovery(identifier) },
            enabled = !vm.busy && identifier.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        )
    } else {
        Text(question, color = g.textPrimary, style = MaterialTheme.typography.titleMedium)
        GlassTextField(answer, { answer = it }, "আপনার উত্তর")
        GlassTextField(password, { password = it }, "নতুন পাসওয়ার্ড", password = true)
        GlassTextField(confirm, { confirm = it }, "নতুন পাসওয়ার্ড আবার লিখুন", password = true)
        GlassButton(
            text = if (vm.busy) "অপেক্ষা করুন..." else "পাসওয়ার্ড বদলান",
            onClick = { vm.resetPassword(identifier, answer, password, confirm, onDone = onBack) },
            enabled = !vm.busy,
            modifier = Modifier.fillMaxWidth()
        )
    }
    LinkText("প্রবেশের পাতায় ফিরে যান", onBack)
}
