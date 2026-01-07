package com.sihiver.mqltv

import android.content.Intent
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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
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

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(text = "Login")
                    Spacer(modifier = Modifier.height(16.dp))

                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it },
                        label = { Text("Username") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("Password") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (errorMessage.isNotBlank()) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(text = errorMessage)
                    }

                    Spacer(modifier = Modifier.height(16.dp))

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
                        modifier = Modifier.fillMaxWidth()
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
