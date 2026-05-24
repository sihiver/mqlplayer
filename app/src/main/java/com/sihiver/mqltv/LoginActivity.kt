package com.sihiver.mqltv

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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

import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen

class LoginActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // If already logged in, jump straight into app
        // Jika sudah login, langsung masuk app — ExpiryWatcher di MainActivity yang akan
        // menangani pengecekan expiry secara berkala (setelah delay) agar tidak salah kick-out
        // hanya karena server sesaat balas 401/403 saat buka app.
        if (AuthRepository.isLoggedIn(this)) {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            finish()
            return
        }

        setContent {
            MQLTVTheme {
                val scope = rememberCoroutineScope()

                val forceTvMode = remember {
                    getSharedPreferences("video_settings", MODE_PRIVATE)
                        .getBoolean("force_tv_mode", false)
                }
                val actualTvDevice =
                    (LocalConfiguration.current.uiMode and Configuration.UI_MODE_TYPE_MASK) == Configuration.UI_MODE_TYPE_TELEVISION
                val isTvDevice = actualTvDevice || forceTvMode

                val usernameFocusRequester = remember { FocusRequester() }
                val passwordFocusRequester = remember { FocusRequester() }
                val loginButtonFocusRequester = remember { FocusRequester() }

                val serverBaseUrl by remember {
                    mutableStateOf(
                        AuthRepository.getServerBaseUrl(this@LoginActivity)
                    )
                }
                var username by remember { mutableStateOf(AuthRepository.getUsername(this@LoginActivity)) }
                var password by remember { mutableStateOf(AuthRepository.getPassword(this@LoginActivity)) }

                var isLoading by remember { mutableStateOf(false) }
                var errorMessage by remember { mutableStateOf("") }
                var loginFocused by remember { mutableStateOf(false) }

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

                                fun handleLoginClick() {
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
                                                    appKey = result.appKey,
                                                    expiresAtRaw = result.expiresAtRaw,
                                                    expiresAtMillis = result.expiresAtMillis,
                                                    isExpiredServer = result.isExpired,
                                                    playlistId = result.playlistId,
                                                )

                                                ChannelRepository.clearPlaylistUrls(this@LoginActivity)
                                                ChannelRepository.addPlaylistUrl(this@LoginActivity, result.playlistUrl)
                                                ChannelRepository.removeDefaultSamplePlaylistForLoggedInUser(this@LoginActivity)

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
                                                    appKey = result.appKey,
                                                    expiresAtRaw = result.expiresAtRaw,
                                                    expiresAtMillis = result.expiresAtMillis,
                                                    isExpiredServer = true,
                                                    playlistId = result.playlistId,
                                                )

                                                if (result.playlistUrl.isNotBlank()) {
                                                    ChannelRepository.clearPlaylistUrls(this@LoginActivity)
                                                    ChannelRepository.addPlaylistUrl(this@LoginActivity, result.playlistUrl)
                                                    ChannelRepository.removeDefaultSamplePlaylistForLoggedInUser(this@LoginActivity)
                                                }

                                                isLoading = false
                                                startActivity(Intent(this@LoginActivity, ExpiredActivity::class.java))
                                                finish()
                                            }
                                        }
                                    }
                                }

                                val loginShape = RoundedCornerShape(12.dp)
                                val loginEnabled = !isLoading
                                val loginBackground = if (loginEnabled) Color(0xFFE50914) else Color(0xFF7A0C10)

                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(buttonHeight)
                                        .background(loginBackground, loginShape)
                                        .focusRequester(loginButtonFocusRequester)
                                        .onFocusChanged { loginFocused = it.isFocused }
                                        .border(
                                            border = if (isTvDevice && loginFocused) BorderStroke(2.dp, Color(0xFFE50914)) else BorderStroke(0.dp, Color.Transparent),
                                            shape = loginShape,
                                        )
                                        .clickable(enabled = loginEnabled) { handleLoginClick() }
                                        .focusable()
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
                                        },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (isLoading) {
                                        androidx.compose.foundation.layout.Row(
                                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            CircularProgressIndicator(
                                                color = Color.White,
                                                strokeWidth = 2.dp,
                                                modifier = Modifier.height(20.dp),
                                            )
                                            Text(
                                                text = "Loading...",
                                                color = Color.White,
                                                fontSize = if (isTvDevice) 18.sp else 16.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                textAlign = TextAlign.Center,
                                                maxLines = 1,
                                            )
                                        }
                                    } else {
                                        Text(
                                            text = "Login",
                                            color = Color.White,
                                            fontSize = if (isTvDevice) 18.sp else 16.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            textAlign = TextAlign.Center,
                                            maxLines = 1,
                                            modifier = Modifier.fillMaxWidth(),
                                        )
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
            val appKey: String,
            val expiresAtRaw: String,
            val expiresAtMillis: Long,
            val isExpired: Boolean,
            /** >0: `GET /public/m3u/{id}.m3u` (mql_manager). */
            val playlistId: Long = -1L,
        ) : LoginResult()

        data class Expired(
            val serverBaseUrl: String,
            val username: String,
            val appKey: String,
            val expiresAtRaw: String,
            val expiresAtMillis: Long,
            val message: String,
            val playlistUrl: String = "",
            val playlistId: Long = -1L,
        ) : LoginResult()

        data class Error(val message: String) : LoginResult()
    }

    private suspend fun login(serverBaseUrlRaw: String, usernameRaw: String, passwordRaw: String): LoginResult {
        val inputTrimmed = serverBaseUrlRaw.trim()
        val hadScheme = inputTrimmed.contains("://")
        val serverBaseUrl = AuthRepository.normalizeServerBaseUrl(inputTrimmed)
        val username = usernameRaw.trim()
        val password = passwordRaw.trim()

        if (username.isBlank()) return LoginResult.Error("Username wajib diisi")
        if (password.isBlank()) return LoginResult.Error("Password wajib diisi")

        return withContext(Dispatchers.IO) {
            fun doLogin(baseUrl: String): Pair<Int, JSONObject> {
                val url = URL("$baseUrl/public/login")
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

                return status to json
            }

            var usedBaseUrl = serverBaseUrl
            val (status, json) = try {
                doLogin(usedBaseUrl)
            } catch (e: Exception) {
                // If user entered a host without scheme, try http:// as fallback.
                if (!hadScheme) {
                    val host = inputTrimmed.ifBlank { "iptv.mqlspot.my.id:8088" }.removeSuffix("/")
                    val httpBase = AuthRepository.normalizeServerBaseUrl("http://$host")
                    usedBaseUrl = httpBase
                    try {
                        doLogin(usedBaseUrl)
                    } catch (_: Exception) {
                        throw e
                    }
                } else {
                    throw e
                }
            }

            if (status !in 200..299) {
                val message = json.optString("error", "").ifBlank { "HTTP $status" }
                return@withContext LoginResult.Error(message)
            }
            // mql_manager public: POST /public/login
            // Respons umum: ok, user (appKey, expiresAt, …), opsional playlistId / publicPlaylistUrl.
            // M3U publik: GET /public/m3u/{playlistId}.m3u atau GET /public/users/{appKey}/playlist.m3u
            val ok = json.optBoolean("ok", false)
            if (!ok) {
                return@withContext LoginResult.Error("Login gagal")
            }

            val user = json.optJSONObject("user")
                ?: return@withContext LoginResult.Error("Response tidak valid: user kosong")

            val appKey = user.optString("appKey", "").trim()
            if (appKey.isBlank()) {
                return@withContext LoginResult.Error("Response tidak valid: appKey kosong")
            }

            val playlistId = AuthRepository.parsePlaylistIdFromLoginJson(json, user)

            val playlistPath = json.optString("publicPlaylistUrl", "").trim()
            if (playlistPath.isNotBlank() && playlistId <= 0L) {
                val expectedSegment = "/public/users/${appKey}/"
                val candidate = if (playlistPath.startsWith("http://") || playlistPath.startsWith("https://")) {
                    try {
                        URL(playlistPath).path
                    } catch (_: Exception) {
                        playlistPath
                    }
                } else {
                    playlistPath
                }
                if (candidate.contains("/public/users/") && !candidate.contains(expectedSegment)) {
                    return@withContext LoginResult.Error("Playlist tidak sesuai user (appKey mismatch)")
                }
            }

            val expiresAtRaw = user.optString("expiresAt", "").trim()
            val expiresAtMillis = AuthRepository.parseExpiresAtMillis(expiresAtRaw)
            val isExpired = expiresAtMillis > 0L && System.currentTimeMillis() >= expiresAtMillis

            val fullPlaylistUrl = if (playlistId > 0L) {
                AuthRepository.buildPublicM3uByPlaylistId(usedBaseUrl, playlistId)
            } else {
                AuthRepository.buildPublicPlaylistM3uUrl(usedBaseUrl, appKey)
            }

            if (isExpired) {
                return@withContext LoginResult.Expired(
                    serverBaseUrl = usedBaseUrl,
                    username = username,
                    appKey = appKey,
                    expiresAtRaw = expiresAtRaw,
                    expiresAtMillis = expiresAtMillis,
                    message = "Akun sudah expired",
                    playlistUrl = fullPlaylistUrl,
                    playlistId = playlistId,
                )
            }

            LoginResult.Success(
                serverBaseUrl = usedBaseUrl,
                username = username,
                playlistUrl = fullPlaylistUrl,
                appKey = appKey,
                expiresAtRaw = expiresAtRaw,
                expiresAtMillis = expiresAtMillis,
                isExpired = false,
                playlistId = playlistId,
            )
        }
    }

}
