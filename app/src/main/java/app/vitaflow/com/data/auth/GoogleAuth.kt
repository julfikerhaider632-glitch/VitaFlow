package app.vitaflow.com.data.auth

import android.content.Context
import android.util.Base64
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import org.json.JSONObject

/**
 * Fill this in after the Firebase step: Firebase console > Authentication > Sign-in method > Google >
 * "Web SDK configuration" > Web client ID (looks like 1234-abcd.apps.googleusercontent.com).
 */
object GoogleConfig {
    const val WEB_CLIENT_ID = ""
}

data class GoogleIdentity(val sub: String, val email: String?, val name: String?)

sealed interface GoogleResult {
    data class Ok(val identity: GoogleIdentity) : GoogleResult
    data class Err(val message: String) : GoogleResult
}

/** Asks Google for the user's identity. The sign-in itself needs internet, everything after it stays on the phone. */
suspend fun requestGoogleIdentity(activityContext: Context): GoogleResult {
    if (GoogleConfig.WEB_CLIENT_ID.isBlank()) {
        return GoogleResult.Err("Google login এখনো setup হয়নি (Web client ID বসানো হয়নি)।")
    }
    return try {
        val option = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(false)
            .setServerClientId(GoogleConfig.WEB_CLIENT_ID)
            .build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        val result = CredentialManager.create(activityContext).getCredential(activityContext, request)
        val cred = result.credential
        if (cred is CustomCredential && cred.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            val google = GoogleIdTokenCredential.createFrom(cred.data)
            val payload = google.idToken.split('.').getOrNull(1)
                ?: return GoogleResult.Err("Google-এর উত্তর পড়া যায়নি।")
            val json = JSONObject(
                String(Base64.decode(payload, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP), Charsets.UTF_8)
            )
            val sub = json.optString("sub")
            if (sub.isEmpty()) return GoogleResult.Err("Google-এর উত্তর পড়া যায়নি।")
            GoogleResult.Ok(
                GoogleIdentity(
                    sub = sub,
                    email = json.optString("email").takeIf { it.isNotEmpty() }?.lowercase(),
                    name = google.displayName ?: json.optString("name").takeIf { it.isNotEmpty() }
                )
            )
        } else {
            GoogleResult.Err("Google account পাওয়া যায়নি।")
        }
    } catch (e: GetCredentialException) {
        GoogleResult.Err("Google login হয়নি: ${e.message ?: "বাতিল হয়েছে"}")
    } catch (e: Exception) {
        GoogleResult.Err("Google login হয়নি: ${e.message ?: "অজানা সমস্যা"}")
    }
}
