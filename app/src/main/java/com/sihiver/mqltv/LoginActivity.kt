package com.sihiver.mqltv

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
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
            startActivity(Intent(this, MainActivity::class.java))
            finish()
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
                            .ifBlank { "http://192.168.0.2:8080" }
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

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(screenPadding),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(text = "Login", fontSize = titleFontSize)
                    Spacer(modifier = Modifier.height(fieldSpacing))

                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it },
                        label = { Text("Username") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        keyboardActions = KeyboardActions(
                            onNext = { passwordFocusRequester.requestFocus() }
                        ),
                        modifier = Modifier.fillMaxWidth()
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

                    Spacer(modifier = Modifier.height(fieldSpacing))

                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("Password") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
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
                        Spacer(modifier = Modifier.height(fieldSpacing))
                        Text(text = errorMessage)
                    }

                    Spacer(modifier = Modifier.height(fieldSpacing))

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
                                        // Persist login session
                                        AuthRepository.saveSession(
                                            context = this@LoginActivity,
                                            serverBaseUrl = result.serverBaseUrl,
                                            username = result.username,
                                            playlistUrl = result.playlistUrl,
                                        )

                                        // Use playlist URL from API as the source for channel refresh
                                        ChannelRepository.clearPlaylistUrls(this@LoginActivity)
                                        ChannelRepository.addPlaylistUrl(this@LoginActivity, result.playlistUrl)

                                        isLoading = false
                                        startActivity(Intent(this@LoginActivity, MainActivity::class.java))
                                        finish()
                                    }

                                    is LoginResult.Error -> {
                                        isLoading = false
                                        errorMessage = result.message
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
                            CircularProgressIndicator()
                            Spacer(modifier = Modifier.height(0.dp))
                            Text(text = " Loading...")
                        } else {
                            Text(text = "Login")
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

            if (status !in 200..299) {
                return@withContext LoginResult.Error("HTTP $status: $body")
            }

            val json = JSONObject(body)
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
            )
        }
    }
}
