package com.sihiver.mqltv

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sihiver.mqltv.repository.AuthRepository
import com.sihiver.mqltv.repository.ChannelRepository
import com.sihiver.mqltv.ui.theme.MQLTVTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

class LoginActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // If already logged in, jump straight into app
        if (AuthRepository.isLoggedIn(this)) {
            lifecycleScope.launch {
                // If user got renewed server-side, playlist may be accessible again.
                // Probe once to clear cached expired flag.
                try {
                    AuthRepository.probeExpiredFromPlaylistUrl(this@LoginActivity)
                } catch (_: Exception) {
                }

                if (AuthRepository.isExpiredNow(this@LoginActivity)) {
                    startActivity(
                        Intent(this@LoginActivity, ExpiredActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                    finish()
                    return@launch
                }

                startActivity(
                    Intent(this@LoginActivity, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                finish()
            }
            return
        }

        setContent {
            MQLTVTheme {
                val scope = rememberCoroutineScope()

                val isTvDevice = (LocalConfiguration.current.uiMode and Configuration.UI_MODE_TYPE_MASK) == Configuration.UI_MODE_TYPE_TELEVISION

                val usernameFocusRequester = remember { FocusRequester() }
                val passwordFocusRequester = remember { FocusRequester() }
                val loginButtonFocusRequester = remember { FocusRequester() }

                val serverBaseUrl by remember {
                    mutableStateOf(
                        AuthRepository.getServerBaseUrl(this@LoginActivity)
                            .ifBlank { "http://192.168.15.10:8080" }
                    )
                }
                var username by remember { mutableStateOf("") }
                var password by remember { mutableStateOf("") }

                var isLoading by remember { mutableStateOf(false) }
                var errorMessage by remember { mutableStateOf("") }

                LaunchedEffect(isTvDevice) {
                    if (isTvDevice) {
                        usernameFocusRequester.requestFocus()
                    }
                }

                val titleFontSize = if (isTvDevice) 28.sp else 20.sp
                val fieldSpacing = if (isTvDevice) 16.dp else 12.dp
                val screenPadding = if (isTvDevice) 48.dp else 24.dp
                val buttonHeight = if (isTvDevice) 56.dp else 48.dp
                val cardMaxWidth = if (isTvDevice) 640.dp else 520.dp

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(screenPadding),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 0.dp)
                                .padding(horizontal = 0.dp)
                                .let { base ->
                                    if (isTvDevice) base else base
                                }
                                .widthIn(max = cardMaxWidth),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surface,
                            ),
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(if (isTvDevice) 32.dp else 20.dp),
                                verticalArrangement = Arrangement.spacedBy(fieldSpacing),
                            ) {
                                Text(
                                    text = "Login",
                                    fontSize = titleFontSize,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )

                                OutlinedTextField(
                                    value = username,
                                    onValueChange = { username = it },
                                    label = { Text("Username") },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                                    keyboardActions = KeyboardActions(
                                        onNext = { passwordFocusRequester.requestFocus() }
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .focusRequester(usernameFocusRequester)
                                        .onKeyEvent { event ->
                                            if (!isTvDevice) return@onKeyEvent false
                                            if (event.type != KeyEventType.KeyUp) return@onKeyEvent false
                                            when (event.key) {
                                                Key.DirectionDown -> {
                                                    passwordFocusRequester.requestFocus()
                                                    true
                                                }
                                                else -> false
                                            }
                                        }
                                )

                                OutlinedTextField(
                                    value = password,
                                    onValueChange = { password = it },
                                    label = { Text("Password") },
                                    singleLine = true,
                                    visualTransformation = PasswordVisualTransformation(),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .focusRequester(passwordFocusRequester)
                                        .onKeyEvent { event ->
                                            if (!isTvDevice) return@onKeyEvent false
                                            if (event.type != KeyEventType.KeyUp) return@onKeyEvent false
                                            when (event.key) {
                                                Key.DirectionDown -> {
                                                    loginButtonFocusRequester.requestFocus()
                                                    true
                                                }
                                                Key.DirectionUp -> {
                                                    usernameFocusRequester.requestFocus()
                                                    true
                                                }
                                                else -> false
                                            }
                                        },
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                    keyboardActions = KeyboardActions(
                                        onDone = { loginButtonFocusRequester.requestFocus() }
                                    )
                                )

                                if (errorMessage.isNotBlank()) {
                                    Text(
                                        text = errorMessage,
                                        color = MaterialTheme.colorScheme.error,
                                        fontSize = if (isTvDevice) 16.sp else 13.sp,
                                    )
                                }

                                Button(
                                    onClick = {
                                        errorMessage = ""
                                        isLoading = true
                                        scope.launch {
                                            val result = try {
                                                login(serverBaseUrl, username, password)
                                            } catch (e: Exception) {
                                                LoginResult.Error(e.message ?: "Login failed")
                                            }

                                            when (result) {
                                                is LoginResult.Success -> {
                                                    AuthRepository.savePassword(this@LoginActivity, password)

                                                    AuthRepository.saveSession(
                                                        context = this@LoginActivity,
                                                        serverBaseUrl = result.serverBaseUrl,
                                                        username = result.username,
                                                        playlistUrl = result.playlistUrl,
                                                        expiresAtRaw = result.expiresAtRaw,
                                                        expiresAtMillis = result.expiresAtMillis,
                                                        isExpiredServer = result.isExpired,
                                                    )

                                                    ChannelRepository.clearPlaylistUrls(this@LoginActivity)
                                                    ChannelRepository.addPlaylistUrl(this@LoginActivity, result.playlistUrl)

                                                    isLoading = false
                                                    if (result.isExpired) {
                                                        startActivity(Intent(this@LoginActivity, ExpiredActivity::class.java))
                                                        finish()
                                                    } else {
                                                        startActivity(Intent(this@LoginActivity, MainActivity::class.java))
                                                        finish()
                                                    }
                                                }

                                                is LoginResult.Error -> {
                                                    isLoading = false
                                                    errorMessage = result.message
                                                }

                                                is LoginResult.Expired -> {
                                                    AuthRepository.savePassword(this@LoginActivity, password)

                                                    AuthRepository.saveSession(
                                                        context = this@LoginActivity,
                                                        serverBaseUrl = result.serverBaseUrl,
                                                        username = result.username,
                                                        playlistUrl = result.playlistUrl,
                                                        expiresAtRaw = result.expiresAtRaw,
                                                        expiresAtMillis = result.expiresAtMillis,
                                                        isExpiredServer = true,
                                                    )

                                                    if (result.playlistUrl.isNotBlank()) {
                                                        ChannelRepository.clearPlaylistUrls(this@LoginActivity)
                                                        ChannelRepository.addPlaylistUrl(this@LoginActivity, result.playlistUrl)
                                                    }

                                                    isLoading = false
                                                    startActivity(Intent(this@LoginActivity, ExpiredActivity::class.java))
                                                    finish()
                                                }
                                            }
                                        }
                                    },
                                    enabled = !isLoading,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(buttonHeight)
                                        .focusRequester(loginButtonFocusRequester)
                                        .onKeyEvent { event ->
                                            if (!isTvDevice) return@onKeyEvent false
                                            if (event.type != KeyEventType.KeyUp) return@onKeyEvent false
                                            when (event.key) {
                                                Key.DirectionUp -> {
                                                    passwordFocusRequester.requestFocus()
                                                    true
                                                }
                                                else -> false
                                            }
                                        }
                                ) {
                                    if (isLoading) {
                                        androidx.compose.foundation.layout.Row(
                                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            CircularProgressIndicator(
                                                strokeWidth = 2.dp,
                                                modifier = Modifier.height(20.dp),
                                            )
                                            Text(text = "Loading...")
                                        }
                                    } else {
                                        Text(text = "Login")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private sealed class LoginResult {
        data class Success(
            val serverBaseUrl: String,
            val username: String,
            val playlistUrl: String,
            val expiresAtRaw: String,
            val expiresAtMillis: Long,
            val isExpired: Boolean,
        ) : LoginResult()

        data class Expired(
            val serverBaseUrl: String,
            val username: String,
            val expiresAtRaw: String,
            val expiresAtMillis: Long,
            val message: String,
            val playlistUrl: String = "",
        ) : LoginResult()

        data class Error(val message: String) : LoginResult()
    }

    private suspend fun login(serverBaseUrlRaw: String, usernameRaw: String, passwordRaw: String): LoginResult {
        val serverBaseUrl = serverBaseUrlRaw.trim().ifBlank { "http://192.168.0.2:8080" }.removeSuffix("/")
        val username = usernameRaw.trim()
        val password = passwordRaw.trim()

        if (username.isBlank()) return LoginResult.Error("Username wajib diisi")
        if (password.isBlank()) return LoginResult.Error("Password wajib diisi")

        return withContext(Dispatchers.IO) {
            val url = URL("$serverBaseUrl/api/user/login")
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15000
                readTimeout = 15000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
            }

            val payload = JSONObject()
                .put("username", username)
                .put("password", password)
                .toString()

            connection.outputStream.use { os ->
                os.write(payload.toByteArray(Charsets.UTF_8))
            }

            val status = connection.responseCode
            val body = try {
                val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                BufferedReader(InputStreamReader(stream)).use { it.readText() }
            } catch (_: Exception) {
                ""
            }

            val json = try {
                if (body.isNotBlank()) JSONObject(body) else JSONObject()
            } catch (_: Exception) {
                JSONObject()
            }

            if (status !in 200..299) {
                val message = json.optString("message", "").ifBlank { "HTTP $status" }
                val data = json.optJSONObject("data")

                val expiresAtRaw = data?.optString("expires_at", "")?.trim().orEmpty()
                val expiresAtMillis = AuthRepository.parseExpiresAtMillis(expiresAtRaw)
                val isExpiredServer = data?.optBoolean("is_expired", false) ?: false
                val daysRemaining = data?.optInt("days_remaining", 0) ?: 0

                val playlistPath = data?.optString("playlist_url", "")?.trim().orEmpty()
                val playlistUrl = if (playlistPath.startsWith("http://") || playlistPath.startsWith("https://")) {
                    playlistPath
                } else if (playlistPath.isNotBlank()) {
                    val normalized = if (playlistPath.startsWith("/")) playlistPath else "/$playlistPath"
                    "$serverBaseUrl$normalized"
                } else {
                    ""
                }

                val looksExpired =
                    status == 403 ||
                        isExpiredServer ||
                        daysRemaining <= 0 ||
                        message.contains("expired", ignoreCase = true) ||
                        message.contains("kadaluarsa", ignoreCase = true)

                if (looksExpired) {
                    return@withContext LoginResult.Expired(
                        serverBaseUrl = serverBaseUrl,
                        username = username,
                        expiresAtRaw = expiresAtRaw,
                        expiresAtMillis = expiresAtMillis,
                        message = message,
                        playlistUrl = playlistUrl,
                    )
                }

                return@withContext LoginResult.Error("HTTP $status: $body")
            }
            val code = json.optInt("code", -1)
            if (code != 0) {
                val message = json.optString("message", "Login gagal")
                return@withContext LoginResult.Error(message)
            }

            val data = json.optJSONObject("data")
                ?: return@withContext LoginResult.Error("Response tidak valid: data kosong")

            val playlistPath = data.optString("playlist_url", "").trim()
            if (playlistPath.isBlank()) {
                return@withContext LoginResult.Error("Response tidak valid: playlist_url kosong")
            }

            val expiresAtRaw = data.optString("expires_at", "").trim()
            val expiresAtMillis = AuthRepository.parseExpiresAtMillis(expiresAtRaw)
            val isExpiredServer = data.optBoolean("is_expired", false)
            val daysRemaining = data.optInt("days_remaining", 0)
            val isExpired = isExpiredServer || daysRemaining <= 0 || (expiresAtMillis > 0L && System.currentTimeMillis() >= expiresAtMillis)

            val fullPlaylistUrl = if (playlistPath.startsWith("http://") || playlistPath.startsWith("https://")) {
                playlistPath
            } else {
                val normalized = if (playlistPath.startsWith("/")) playlistPath else "/$playlistPath"
                "$serverBaseUrl$normalized"
            }

            LoginResult.Success(
                serverBaseUrl = serverBaseUrl,
                username = username,
                playlistUrl = fullPlaylistUrl,
                expiresAtRaw = expiresAtRaw,
                expiresAtMillis = expiresAtMillis,
                isExpired = isExpired,
            )
        }
    }

}
