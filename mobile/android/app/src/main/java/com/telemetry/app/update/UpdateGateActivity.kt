package com.telemetry.app.update

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.telemetry.app.BuildConfig
import com.telemetry.app.MainActivity
import java.io.File

private const val ACTION_CHECK_UPDATE = "com.telemetry.preview.CHECK_UPDATE"
private const val CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L
private const val FAILURE_RETRY_MS = 15 * 60 * 1000L

private val UpdateInk = Color(0xFF07111F)
private val UpdateCard = Color(0xFF0D1B2C)
private val UpdateBlue = Color(0xFF2F86FF)
private val UpdateCyan = Color(0xFF38E2C2)
private val UpdateText = Color(0xFFF4F8FF)
private val UpdateMuted = Color(0xFF9DAFC7)

private sealed interface GateState {
    data object Checking : GateState
    data object Current : GateState
    data class Available(val info: UpdateInfo) : GateState
    data class Downloading(val info: UpdateInfo) : GateState
    data class Ready(val info: UpdateInfo, val file: File) : GateState
    data class Error(val message: String) : GateState
}

class UpdateGateActivity : ComponentActivity() {
    private var state by mutableStateOf<GateState>(GateState.Checking)
    private var progress by mutableIntStateOf(0)
    private val manualMode get() = intent?.action == ACTION_CHECK_UPDATE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = UpdateBlue,
                    secondary = UpdateCyan,
                    background = UpdateInk,
                    surface = UpdateCard,
                    onBackground = UpdateText,
                    onSurface = UpdateText
                )
            ) {
                UpdateGateUi(
                    state = state,
                    progress = progress,
                    onCheck = { checkForUpdate(force = true) },
                    onUpdate = ::downloadUpdate,
                    onInstall = ::installReadyUpdate,
                    onContinue = ::openTelemetry
                )
            }
        }

        if (shouldSkipAutomaticCheck()) {
            openTelemetry()
        } else {
            checkForUpdate(force = manualMode)
        }
    }

    private fun shouldSkipAutomaticCheck(): Boolean {
        if (manualMode) return false
        val prefs = getSharedPreferences("telemetry-updates", MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val lastSuccess = prefs.getLong("last_success", 0L)
        val lastAttempt = prefs.getLong("last_attempt", 0L)
        return (now - lastSuccess < CHECK_INTERVAL_MS) ||
            (lastSuccess == 0L && now - lastAttempt < FAILURE_RETRY_MS)
    }

    private fun checkForUpdate(force: Boolean) {
        state = GateState.Checking
        val prefs = getSharedPreferences("telemetry-updates", MODE_PRIVATE)
        prefs.edit().putLong("last_attempt", System.currentTimeMillis()).apply()

        Thread {
            runCatching { UpdateClient.check() }
                .onSuccess { info ->
                    runOnUiThread {
                        prefs.edit().putLong("last_success", System.currentTimeMillis()).apply()
                        if (info.updateAvailable && info.versionCode > BuildConfig.VERSION_CODE) {
                            state = GateState.Available(info)
                        } else if (force || manualMode) {
                            state = GateState.Current
                        } else {
                            openTelemetry()
                        }
                    }
                }
                .onFailure { error ->
                    runOnUiThread {
                        if (force || manualMode) {
                            state = GateState.Error(error.message ?: "Could not check for updates")
                        } else {
                            openTelemetry()
                        }
                    }
                }
        }.start()
    }

    private fun downloadUpdate(info: UpdateInfo) {
        state = GateState.Downloading(info)
        progress = 0
        Thread {
            runCatching {
                UpdateClient.download(this, info) { value ->
                    runOnUiThread { progress = value }
                }
            }.onSuccess { file ->
                runOnUiThread {
                    state = GateState.Ready(info, file)
                    installReadyUpdate()
                }
            }.onFailure { error ->
                runOnUiThread {
                    state = GateState.Error(error.message ?: "Update download failed")
                }
            }
        }.start()
    }

    private fun installReadyUpdate() {
        val ready = state as? GateState.Ready ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:$packageName")
                )
            )
            return
        }
        startActivity(UpdateClient.installerIntent(this, ready.file))
    }

    private fun openTelemetry() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}

@Composable
private fun UpdateGateUi(
    state: GateState,
    progress: Int,
    onCheck: () -> Unit,
    onUpdate: (UpdateInfo) -> Unit,
    onInstall: () -> Unit,
    onContinue: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(UpdateInk)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Telemetry", color = UpdateText, fontSize = 38.sp, fontWeight = FontWeight.Bold)
        Text("Android Preview Update Channel", color = UpdateMuted, fontSize = 13.sp)
        Spacer(Modifier.height(28.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = UpdateCard),
            shape = RoundedCornerShape(22.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                when (state) {
                    GateState.Checking -> {
                        Text("Checking for updates…", color = UpdateText, fontSize = 18.sp)
                        Text("Current ${BuildConfig.VERSION_NAME}", color = UpdateMuted, fontSize = 12.sp)
                    }
                    GateState.Current -> {
                        Text("You’re up to date", color = UpdateCyan, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Text("Version ${BuildConfig.VERSION_NAME}", color = UpdateMuted)
                        Spacer(Modifier.height(16.dp))
                        UpdateButton("Check Update", onCheck)
                    }
                    is GateState.Available -> {
                        Text("Update available", color = UpdateCyan, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(6.dp))
                        Text("${BuildConfig.VERSION_NAME}  →  ${state.info.versionName}", color = UpdateText, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(10.dp))
                        Text(state.info.notes, color = UpdateMuted, fontSize = 12.sp, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(18.dp))
                        UpdateButton("Download & Install", { onUpdate(state.info) })
                    }
                    is GateState.Downloading -> {
                        Text("Downloading update…", color = UpdateText, fontSize = 19.sp)
                        Spacer(Modifier.height(10.dp))
                        Text("$progress%", color = UpdateCyan, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                        Text("APK will be verified before installation.", color = UpdateMuted, fontSize = 11.sp)
                    }
                    is GateState.Ready -> {
                        Text("Update verified", color = UpdateCyan, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                        Text("Android may ask you to allow installs from Telemetry Preview.", color = UpdateMuted, fontSize = 12.sp, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(16.dp))
                        UpdateButton("Install Update", onInstall)
                    }
                    is GateState.Error -> {
                        Text("Update check unavailable", color = Color(0xFFFFB2BA), fontSize = 19.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(6.dp))
                        Text(state.message, color = UpdateMuted, fontSize = 12.sp, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(16.dp))
                        UpdateButton("Check Update", onCheck)
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            Button(
                onClick = onContinue,
                colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent, contentColor = UpdateBlue)
            ) {
                Text("Continue to Telemetry")
            }
        }
        Text("Offline messaging never depends on this update service.", color = UpdateMuted, fontSize = 10.sp, textAlign = TextAlign.Center)
    }
}

@Composable
private fun UpdateButton(label: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(54.dp),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(containerColor = UpdateBlue)
    ) {
        Text(label, color = Color.White, fontWeight = FontWeight.Bold)
    }
}
