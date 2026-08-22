package org.futo.inputmethod.latin.uix.actions

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import okhttp3.Request
import org.json.JSONObject
import org.futo.inputmethod.latin.BuildConfig
import androidx.datastore.preferences.core.booleanPreferencesKey
import org.futo.inputmethod.latin.uix.SettingsKey
import org.futo.inputmethod.latin.uix.ANIMATE_BUBBLE
import org.futo.inputmethod.latin.uix.AUDIO_FOCUS
import org.futo.inputmethod.latin.uix.ActionWindow
import org.futo.inputmethod.latin.uix.CAN_EXPAND_SPACE
import org.futo.inputmethod.latin.uix.CloseResult
import org.futo.inputmethod.latin.uix.DISALLOW_SYMBOLS
import org.futo.inputmethod.latin.uix.KeyboardManagerForAction
import org.futo.inputmethod.latin.uix.PREFER_BLUETOOTH
import org.futo.inputmethod.latin.uix.ResourceHelper
import org.futo.inputmethod.latin.uix.USE_VAD_AUTOSTOP
import org.futo.inputmethod.latin.uix.getSetting
import org.futo.inputmethod.latin.uix.setSettingBlocking
import org.futo.inputmethod.latin.uix.settings.SettingsActivity
import android.view.KeyEvent
import org.futo.voiceinput.shared.BUILTIN_PARAKEET_MODEL
import org.futo.voiceinput.shared.ModelDoesNotExistException
import org.futo.voiceinput.shared.RecognizerView
import org.futo.voiceinput.shared.RecognizerViewListener
import org.futo.voiceinput.shared.RecognizerViewSettings
import org.futo.voiceinput.shared.RecordingSettings
import org.futo.voiceinput.shared.ggml.ParakeetModelDownloader
import org.futo.voiceinput.shared.types.ModelLoader
import org.futo.voiceinput.shared.types.getLanguageFromWhisperString
import org.futo.voiceinput.shared.ui.MicrophoneDeviceState
import org.futo.voiceinput.shared.whisper.DecodingConfiguration
import org.futo.voiceinput.shared.whisper.ModelManager
import org.futo.voiceinput.shared.whisper.MultiModelRunConfiguration
import java.util.Locale


    /**
     * Real device control (v2): sound/media via AudioManager, brightness via
     * Settings.System (one-time WRITE_SETTINGS grant walked through on first
     * use), vibrate, flashlight with true state tracking, alarm. No Wi-Fi row:
     * silent third-party toggles have been impossible since Android 10.
     */
    @Composable
    internal fun AgentHubWindow.DeviceActionsSection(agentId: String) {
        val lastAnswer = AgentMemory.lastAnswer(agentId)

        Spacer(modifier = Modifier.height(20.dp))
        HubSectionLabel("DEVICE CONTROL")
        Text(
            "Real controls for this phone. Brightness asks for a one-time settings grant.",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, bottom = 10.dp)
        )

        HubSectionLabel("SOUND & MEDIA")
        Spacer(modifier = Modifier.height(6.dp))
        HubRowCard(
            rows = listOf(
                HubRow("Volume up") { deviceVolume(context, raise = true) },
                HubRow("Volume down") { deviceVolume(context, raise = false) },
                HubRow("Mute / unmute media") { deviceMuteToggle(context) },
                HubRow("Play / pause") { deviceMediaKey(context, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) },
                HubRow("Next track") { deviceMediaKey(context, KeyEvent.KEYCODE_MEDIA_NEXT) },
                HubRow("Previous track") { deviceMediaKey(context, KeyEvent.KEYCODE_MEDIA_PREVIOUS) }
            )
        )

        Spacer(modifier = Modifier.height(12.dp))
        HubSectionLabel("DEVICE")
        Spacer(modifier = Modifier.height(6.dp))
        HubRowCard(
            rows = listOf(
                HubRow("Brightness up") {
                    val result = adjustBrightness(context, +30)
                    manager.announce(
                        when (result) {
                            is BrightnessResult.Set -> "Brightness ${result.level}"
                            BrightnessResult.NeedsGrant -> "Grant modify-settings once, then it works"
                            BrightnessResult.Unavailable -> "Brightness unavailable on this device"
                        }
                    )
                },
                HubRow("Brightness down") {
                    val result = adjustBrightness(context, -30)
                    manager.announce(
                        when (result) {
                            is BrightnessResult.Set -> "Brightness ${result.level}"
                            BrightnessResult.NeedsGrant -> "Opening modify-settings grant"
                            BrightnessResult.Unavailable -> "Brightness unavailable on this device"
                        }
                    )
                },
                HubRow("Vibrate") { deviceVibrate(context) },
                HubRow(if (torchOn) "Flashlight (on)" else "Flashlight") {
                    if (setTorch(context, !torchOn)) {
                        torchOn = !torchOn
                        manager.announce(if (torchOn) "Flashlight on" else "Flashlight off")
                    } else {
                        manager.announce("Flashlight unavailable or camera busy")
                    }
                },
                HubRow("Set alarm") {
                    if (!openAlarmSetter(context)) manager.announce("No clock app on this device")
                }
            )
        )

        // Copy-last-answer stays under the agent, not the device.
        val copyEnabled = lastAnswer != null
        if (copyEnabled) {
            Spacer(modifier = Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                HubChip(text = "Copy last answer") { lastAnswer?.let { copyAnswer(it) } }
            }
        }
    }

