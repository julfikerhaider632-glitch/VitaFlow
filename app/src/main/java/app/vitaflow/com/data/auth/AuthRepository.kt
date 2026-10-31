package app.vitaflow.com.data.auth

import android.util.Patterns
import app.vitaflow.com.data.SessionStore
import app.vitaflow.com.data.db.AccountDao
import app.vitaflow.com.data.db.AccountEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.util.Locale

sealed interface SessionState {
    data object Loading : SessionState
    data object LoggedOut : SessionState
    data class LoggedIn(val account: AccountEntity) : SessionState
}

sealed interface AuthResult {
    data class Ok(val account: AccountEntity) : AuthResult
    data class Err(val message: String) : AuthResult
}

data class RegisterForm(
    val name: String,
    val phone: String,
    val email: String,
    val birth: String,
    val password: String,
    val confirm: String,
    val questionId: Int,
    val answer: String
)

object SecurityQuestions {
    val all: List<Pair<Int, String>> = listOf(
        1 to "আপনার প্রিয় খাবারের নাম কী?",
        2 to "আপনার best friend-এর নাম কী?",
        3 to "আপনার প্রিয় জায়গা কোনটা?",
        4 to "আপনার প্রথম স্কুলের নাম কী?",
        5 to "ছোটবেলার ডাকনাম কী ছিল?",
        6 to "আপনার প্রিয় খেলোয়াড় বা মানুষ কে?"
    )

    fun text(id: Int): String? = all.firstOrNull { it.first == id }?.second
}

class AuthRepository(
    private val dao: AccountDao,
    private val session: SessionStore
) {
    private companion object {
        const val MAX_FAILS = 5
        const val LOCK_MS = 5 * 60 * 1000L
        const val BAD_LOGIN = "email, ফোন নম্বর বা পাসওয়ার্ড ঠিক নয়"
        val BIRTH_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("d/M/uuuu").withResolverStyle(ResolverStyle.STRICT)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun sessionFlow(): Flow<SessionState> = session.accountId.flatMapLatest { id ->
        if (id == null) {
            flowOf(SessionState.LoggedOut)
        } else {
            dao.observe(id).map { acc ->
                if (acc == null) SessionState.LoggedOut else SessionState.LoggedIn(acc)
            }
        }
    }

    suspend fun register(f: RegisterForm): AuthResult = withContext(Dispatchers.Default) {
        val name = f.name.trim()
        if (name.length < 2) return@withContext AuthResult.Err("আপনার নাম লিখুন")
        val phone = normalizePhone(f.phone)
            ?: return@withContext AuthResult.Err("সঠিক ফোন নম্বর দিন")
        val email = f.email.trim().lowercase(Locale.ROOT)
        if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            return@withContext AuthResult.Err("সঠিক email দিন")
        }
        val birth = parseBirth(f.birth)
            ?: return@withContext AuthResult.Err("জন্মতারিখ দিন/মাস/বছর আকারে লিখুন, যেমন 15/03/1998")
        if (f.password.length < 8) return@withContext AuthResult.Err("পাসওয়ার্ড কমপক্ষে ৮ অক্ষরের হতে হবে")
        if (f.password != f.confirm) return@withContext AuthResult.Err("দুটো পাসওয়ার্ড মিলছে না")
        if (SecurityQuestions.text(f.questionId) == null) {
            return@withContext AuthResult.Err("একটি নিরাপত্তা প্রশ্ন বেছে নিন")
        }
        val answer = normalizeAnswer(f.answer)
        if (answer.length < 2) return@withContext AuthResult.Err("নিরাপত্তা প্রশ্নের উত্তর লিখুন")
        if (dao.byEmail(email) != null) return@withContext AuthResult.Err("এই email দিয়ে আগেই account খোলা আছে")
        if (dao.byPhone(phone) != null) return@withContext AuthResult.Err("এই ফোন নম্বর দিয়ে আগেই account খোলা আছে")

        val pwSalt = PasswordHasher.newSalt()
        val ansSalt = PasswordHasher.newSalt()
        val account = AccountEntity(
            name = name,
            phone = phone,
            email = email,
            birthDate = birth.toString(),
            passwordHash = PasswordHasher.hash(f.password.toCharArray(), pwSalt),
            passwordSalt = pwSalt,
            securityQuestionId = f.questionId,
            securityAnswerHash = PasswordHasher.hash(answer.toCharArray(), ansSalt),
            securityAnswerSalt = ansSalt
        )
        val id = dao.insert(account)
        session.set(id)
        AuthResult.Ok(account.copy(id = id))
    }

    suspend fun login(identifier: String, password: String): AuthResult = withContext(Dispatchers.Default) {
        val acc = findByIdentifier(identifier) ?: return@withContext AuthResult.Err(BAD_LOGIN)
        val now = System.currentTimeMillis()
        if (acc.lockedUntil > now) {
            val secs = (acc.lockedUntil - now) / 1000 + 1
            return@withContext AuthResult.Err("অনেকবার ভুল হয়েছে। $secs সেকেন্ড পরে আবার চেষ্টা করুন")
        }
        if (PasswordHasher.verify(password.toCharArray(), acc.passwordSalt, acc.passwordHash)) {
            dao.update(acc.copy(failedAttempts = 0, lockedUntil = 0))
            session.set(acc.id)
            AuthResult.Ok(acc)
        } else {
            val fails = acc.failedAttempts + 1
            val locked = fails >= MAX_FAILS
            dao.update(
                acc.copy(
                    failedAttempts = if (locked) 0 else fails,
                    lockedUntil = if (locked) now + LOCK_MS else 0
                )
            )
            AuthResult.Err(BAD_LOGIN)
        }
    }

    suspend fun logout() {
        session.clear()
    }

    /** Logs in with Google. First time: links to an account with the same email, or opens a new one. */
    suspend fun loginWithGoogle(g: GoogleIdentity): AuthResult = withContext(Dispatchers.Default) {
        dao.byGoogleSub(g.sub)?.let {
            session.set(it.id)
            return@withContext AuthResult.Ok(it)
        }
        g.email?.let { email ->
            dao.byEmail(email)?.let { existing ->
                val linked = existing.copy(googleSub = g.sub)
                dao.update(linked)
                session.set(linked.id)
                return@withContext AuthResult.Ok(linked)
            }
        }
        val account = AccountEntity(
            name = g.name ?: g.email?.substringBefore('@') ?: "Vita Flow user",
            phone = null,
            email = g.email,
            birthDate = "",
            passwordHash = "",
            passwordSalt = "",
            securityQuestionId = 0,
            securityAnswerHash = "",
            securityAnswerSalt = "",
            googleSub = g.sub
        )
        val id = dao.insert(account)
        session.set(id)
        AuthResult.Ok(account.copy(id = id))
    }

    /** Connects a Google account to an existing account (Settings). */
    suspend fun linkGoogle(accountId: Long, g: GoogleIdentity): String? {
        val other = dao.byGoogleSub(g.sub)
        if (other != null && other.id != accountId) return "এই Google account অন্য একটা account-এ যুক্ত আছে।"
        val acc = dao.get(accountId) ?: return "account পাওয়া যায়নি।"
        dao.update(acc.copy(googleSub = g.sub))
        return null
    }

    /** Returns the text of the security question the user picked at registration. */
    suspend fun recoveryQuestion(identifier: String): String? =
        findByIdentifier(identifier)?.let { SecurityQuestions.text(it.securityQuestionId) }

    suspend fun resetPassword(
        identifier: String,
        answer: String,
        newPassword: String,
        confirm: String
    ): AuthResult = withContext(Dispatchers.Default) {
        val acc = findByIdentifier(identifier)
            ?: return@withContext AuthResult.Err("এই email বা ফোন নম্বরে কোনো account পাওয়া যায়নি")
        val now = System.currentTimeMillis()
        if (acc.answerLockedUntil > now) {
            val secs = (acc.answerLockedUntil - now) / 1000 + 1
            return@withContext AuthResult.Err("অনেকবার ভুল হয়েছে। $secs সেকেন্ড পরে আবার চেষ্টা করুন")
        }
        if (newPassword.length < 8) return@withContext AuthResult.Err("পাসওয়ার্ড কমপক্ষে ৮ অক্ষরের হতে হবে")
        if (newPassword != confirm) return@withContext AuthResult.Err("দুটো পাসওয়ার্ড মিলছে না")

        val ok = PasswordHasher.verify(
            normalizeAnswer(answer).toCharArray(), acc.securityAnswerSalt, acc.securityAnswerHash
        )
        if (!ok) {
            val fails = acc.answerFailed + 1
            val locked = fails >= MAX_FAILS
            dao.update(
                acc.copy(
                    answerFailed = if (locked) 0 else fails,
                    answerLockedUntil = if (locked) now + LOCK_MS else 0
                )
            )
            return@withContext AuthResult.Err("উত্তর মেলেনি")
        }
        val salt = PasswordHasher.newSalt()
        val updated = acc.copy(
            passwordSalt = salt,
            passwordHash = PasswordHasher.hash(newPassword.toCharArray(), salt),
            failedAttempts = 0,
            lockedUntil = 0,
            answerFailed = 0,
            answerLockedUntil = 0
        )
        dao.update(updated)
        AuthResult.Ok(updated)
    }

    private suspend fun findByIdentifier(identifier: String): AccountEntity? {
        val id = identifier.trim()
        return if (id.contains('@')) {
            dao.byEmail(id.lowercase(Locale.ROOT))
        } else {
            normalizePhone(id)?.let { dao.byPhone(it) }
        }
    }

    private fun String.toAsciiDigits(): String = map { c ->
        if (c in '\u09E6'..'\u09EF') ('0' + (c - '\u09E6')) else c
    }.joinToString("")

    private fun normalizePhone(raw: String): String? {
        val s = raw.trim().toAsciiDigits()
        val digits = s.filter { it.isDigit() }
        if (digits.length !in 10..15) return null
        return if (s.startsWith("+")) "+$digits" else digits
    }

    private fun normalizeAnswer(raw: String): String =
        raw.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")

    private fun parseBirth(raw: String): LocalDate? {
        val s = raw.trim().toAsciiDigits().replace('-', '/').replace('.', '/')
        val date = runCatching { LocalDate.parse(s, BIRTH_FORMAT) }.getOrNull() ?: return null
        return if (date.isBefore(LocalDate.now()) && date.year >= 1900) date else null
    }
}
