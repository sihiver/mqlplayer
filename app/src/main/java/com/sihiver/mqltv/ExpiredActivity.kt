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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sihiver.mqltv.repository.AuthRepository
import com.sihiver.mqltv.ui.theme.MQLTVTheme

class ExpiredActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MQLTVTheme {
                ExpiredScreen(
                    username = AuthRepository.getUsername(this@ExpiredActivity),
                    expiresAt = AuthRepository.getExpiresAtRaw(this@ExpiredActivity),
                    onBackToLogin = {
                        AuthRepository.clearSession(this@ExpiredActivity)
                        startActivity(Intent(this@ExpiredActivity, LoginActivity::class.java))
                        finish()
                    }
                )
            }
        }
    }
}

@Composable
private fun ExpiredScreen(
    username: String,
    expiresAt: String,
    onBackToLogin: () -> Unit,
) {
    val isTvDevice = (LocalConfiguration.current.uiMode and Configuration.UI_MODE_TYPE_MASK) == Configuration.UI_MODE_TYPE_TELEVISION
    val buttonFocusRequester = androidx.compose.runtime.remember { FocusRequester() }

    LaunchedEffect(isTvDevice) {
        if (isTvDevice) {
            buttonFocusRequester.requestFocus()
        }
    }

    val padding = if (isTvDevice) 56.dp else 32.dp
    val titleSize = if (isTvDevice) 32.sp else 24.sp
    val bodySize = if (isTvDevice) 18.sp else 14.sp
    val buttonHeight = if (isTvDevice) 56.dp else 48.dp
    val cardMaxWidth = if (isTvDevice) 720.dp else 560.dp

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = cardMaxWidth),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(if (isTvDevice) 32.dp else 20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = "Akun sudah expired",
                        fontSize = titleSize,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "Silahkan perpanjang / hubungi admin",
                        fontSize = bodySize,
                        color = MaterialTheme.colorScheme.onSurface,
                    )

                    if (username.isNotBlank()) {
                        Text(
                            text = "Username: $username",
                            fontSize = bodySize,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    if (expiresAt.isNotBlank()) {
                        Text(
                            text = "Expires at: $expiresAt",
                            fontSize = bodySize,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = onBackToLogin,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(buttonHeight)
                            .focusRequester(buttonFocusRequester)
                    ) {
                        Text(text = "Kembali ke Login", fontSize = bodySize)
                    }
                }
            }
        }
    }
}
