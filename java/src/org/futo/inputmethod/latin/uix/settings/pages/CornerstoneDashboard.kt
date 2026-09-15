package org.futo.inputmethod.latin.uix.settings.pages

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.launch

import org.futo.inputmethod.latin.uix.POLISH_DICTATION_ONLINE
import org.futo.inputmethod.latin.uix.USE_SYSTEM_VOICE_INPUT

import org.futo.inputmethod.latin.uix.actions.AI_AUTOCORRECT_ENABLED
import org.futo.inputmethod.latin.uix.settings.useDataStore
import org.futo.inputmethod.latin.uix.settings.useDataStoreValue
import org.futo.inputmethod.latin.xlm.correction.CorrectionModelReadiness
import org.futo.inputmethod.latin.xlm.correction.CorrectionOutcome
import org.futo.inputmethod.latin.xlm.correction.ON_DEVICE_LLM_CORRECTION
import org.futo.inputmethod.latin.xlm.correction.OnDeviceCorrection
import org.futo.voiceinput.shared.ggml.ParakeetModelDownloader
import java.text.DateFormat
import java.util.Date

internal val CornerstoneBase = Color(0xFF05070A)
internal val CornerstoneCard = Color(0xFF0B1017)
internal val CornerstoneElevated = Color(0xFF0F161F)
internal val CornerstoneYellow = Color(0xFFFCEE0A)
internal val CornerstoneCyan = Color(0xFF00F2FF)
internal val CornerstoneSuccess = Color(0xFF31D393)
internal val CornerstoneWarning = Color(0xFFF2C94C)
internal val CornerstoneDanger = Color(0xFFFF5370)
internal val CornerstoneMuted = Color(0xFFAAB7C4)
internal val CornerstoneOutline = Color(0xFF26313D)

internal val CornerstoneSettingsColorScheme = darkColorScheme(
    primary = CornerstoneYellow,
    onPrimary = CornerstoneBase,
    secondary = Color.White,
    onSecondary = CornerstoneBase,
    background = CornerstoneBase,
    onBackground = Color.White,
    surface = CornerstoneCard,
    onSurface = Color.White,
    surfaceVariant = CornerstoneElevated,
    onSurfaceVariant = CornerstoneMuted,
    primaryContainer = CornerstoneElevated,
    onPrimaryContainer = Color.White,
    secondaryContainer = CornerstoneCard,
    onSecondaryContainer = CornerstoneMuted,
    error = CornerstoneDanger,
    outline = Color(0xFF38404B),
)

private enum class SelfTestState { Idle, Running, Passed, Failed }

private data class KeyboardState(val enabled: Boolean, val selected: Boolean)

@Composable
internal fun CornerstoneAccentRule(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.foundation.layout.Box(
            Modifier.width(34.dp).height(3.dp).background(CornerstoneYellow, RoundedCornerShape(2.dp)),
        )
        androidx.compose.foundation.layout.Box(
            Modifier.width(12.dp).height(3.dp).background(CornerstoneCyan, RoundedCornerShape(2.dp)),
        )
    }
}

private fun keyboardState(context: Context): KeyboardState {
    val inputMethodManager = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    val enabled = inputMethodManager.enabledInputMethodList.any { it.packageName == context.packageName }
    val selectedId = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.DEFAULT_INPUT_METHOD,
    ).orEmpty()
    return KeyboardState(enabled, selectedId.startsWith(context.packageName))
}

private fun beginVoiceModelDownload(context: Context) {
    if (
        Build.VERSION.SDK_INT >= 33 &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED &&
        context is Activity
    ) {
        ActivityCompat.requestPermissions(
            context,
            arrayOf(Manifest.permission.POST_NOTIFICATIONS),
            42,
        )
    }
    ParakeetModelDownloader.downloadIfNeededAsync(context)
}

@Composable
private fun StatusLine(
    label: String,
    value: String,
    color: Color,
    detail: String? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        androidx.compose.foundation.layout.Box(
            modifier = Modifier.padding(top = 6.dp).size(7.dp).background(color, CircleShape),
        )
        Column(modifier = Modifier.padding(start = 12.dp, end = 10.dp).weight(1f)) {
            Text(label, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            if (detail != null) {
                Text(detail, color = CornerstoneMuted, fontSize = 12.sp, lineHeight = 16.sp)
            }
        }
        Text(
            value,
            color = color,
            fontWeight = FontWeight.Black,
            fontSize = 9.sp,
            letterSpacing = 0.45.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .background(color.copy(alpha = 0.11f), RoundedCornerShape(8.dp))
                .padding(horizontal = 7.dp, vertical = 3.dp),
        )
    }
}

@Composable
private fun CornerstoneToggle(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Text(subtitle, color = CornerstoneMuted, fontSize = 12.sp, lineHeight = 16.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = CornerstoneBase,
                checkedTrackColor = CornerstoneYellow,
                uncheckedThumbColor = CornerstoneMuted,
                uncheckedTrackColor = CornerstoneElevated,
                uncheckedBorderColor = CornerstoneMuted,
            ),
        )
    }
}

@Composable
fun CornerstoneVoiceStatusCard() {
    val context = LocalContext.current
    val systemVoiceProvider = useDataStore(USE_SYSTEM_VOICE_INPUT)
    val useCornerstoneVoice = !systemVoiceProvider.value
    val ready = ParakeetModelDownloader.isModelFullyDownloaded(context)
    val progress = ParakeetModelDownloader.progressPercent.intValue
    val error = ParakeetModelDownloader.lastError.value
    val downloading = useCornerstoneVoice && !ready && error == null && progress in 0..99

    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, CornerstoneOutline),
        colors = CardDefaults.cardColors(containerColor = CornerstoneCard),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            CornerstoneAccentRule(Modifier.padding(bottom = 18.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "CORNERSTONE VOICE",
                        color = CornerstoneYellow,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        letterSpacing = 0.9.sp,
                    )
                    Text(
                        "Parakeet TDT 0.6B v2",
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 18.sp,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    Text(
                        "On-device speech recognition",
                        color = CornerstoneMuted,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                    )
                }
                Text(
                    when {
                        !useCornerstoneVoice -> "SYSTEM VOICE"
                        ready -> "READY"
                        error != null -> "PAUSED"
                        downloading -> "$progress%"
                        else -> "DOWNLOAD REQUIRED"
                    },
                    color = when {
                        !useCornerstoneVoice -> CornerstoneMuted
                        ready -> CornerstoneSuccess
                        error != null -> CornerstoneWarning
                        downloading -> CornerstoneYellow
                        else -> CornerstoneWarning
                    },
                    fontWeight = FontWeight.Bold,
                    fontSize = 9.sp,
                    letterSpacing = 0.4.sp,
                    modifier = Modifier
                        .background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 7.dp, vertical = 3.dp),
                )
            }

            HorizontalDivider(
                color = Color.White.copy(alpha = 0.08f),
                modifier = Modifier.padding(vertical = 16.dp),
            )
            CornerstoneToggle(
                title = "Use Cornerstone Voice",
                subtitle = if (useCornerstoneVoice) {
                    if (ready) {
                        "Selected · ready for on-device dictation"
                    } else {
                        "Selected for setup · activates after download"
                    }
                } else {
                    "Off · Android's system voice provider will be used"
                },
                checked = useCornerstoneVoice,
                onCheckedChange = { systemVoiceProvider.setValue(!it) },
            )

            if (useCornerstoneVoice) {
                Text(
                    "661 MB · resumable download",
                    color = Color.White,
                    fontSize = 13.sp,
                )
                Text(
                    "Source: ${ParakeetModelDownloader.sourceLabel}",
                    color = CornerstoneMuted,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 3.dp),
                )
                Text(
                    "After download, speech recognition runs on this device. Audio is not uploaded. Online polish, if enabled separately, may send the transcript.",
                    color = CornerstoneMuted,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )

                if (downloading) {
                    LinearProgressIndicator(
                        progress = { progress / 100f },
                        modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                        color = CornerstoneYellow,
                        trackColor = CornerstoneElevated,
                    )
                    if (ParakeetModelDownloader.progressText.value.isNotBlank()) {
                        Text(
                            ParakeetModelDownloader.progressText.value,
                            color = CornerstoneMuted,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }

                if (error != null) {
                    Text(
                        "Download paused. Your partial download is preserved and will resume when the connection is available.",
                        color = CornerstoneWarning,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
            } else {
                Text(
                    "Cornerstone cannot guarantee whether an external voice provider sends audio or transcripts over the network.",
                    color = CornerstoneWarning,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            if (useCornerstoneVoice && !ready && !downloading) {
                Button(
                    onClick = { beginVoiceModelDownload(context) },
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = CornerstoneYellow,
                        contentColor = CornerstoneBase,
                    ),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text(
                        if (error == null) "Download model · 661 MB" else "Resume download",
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

@Composable
fun CornerstoneDashboard() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    var selfTest by remember { mutableStateOf(SelfTestState.Idle) }
    var selfTestDetail by remember { mutableStateOf<String?>(null) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh += 1
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val keyboard = remember(refresh) { keyboardState(context) }
    val correctionModel = remember(refresh, selfTest) { OnDeviceCorrection.modelStatus(context) }
    val lastRun = remember(refresh, selfTest) { OnDeviceCorrection.lastRun(context) }
    val correctionEnabled = useDataStore(ON_DEVICE_LLM_CORRECTION)
    val fixChipEnabled = useDataStore(AI_AUTOCORRECT_ENABLED)
    val onlineFallback = useDataStoreValue(POLISH_DICTATION_ONLINE)
    val voiceReady = ParakeetModelDownloader.isModelFullyDownloaded(context)
    val voiceError = ParakeetModelDownloader.lastError.value
    val voiceProgress = ParakeetModelDownloader.progressPercent.intValue

    val modelValue = when (correctionModel.readiness) {
        CorrectionModelReadiness.Verified -> "VERIFIED"
        CorrectionModelReadiness.Installed -> "NOT VERIFIED"
        CorrectionModelReadiness.Bundled -> "BUNDLED"
    }
    val modelColor = when (correctionModel.readiness) {
        CorrectionModelReadiness.Verified -> CornerstoneSuccess
        CorrectionModelReadiness.Installed -> CornerstoneWarning
        CorrectionModelReadiness.Bundled -> CornerstoneYellow
    }
    val modelDetail = when (correctionModel.readiness) {
        CorrectionModelReadiness.Verified -> "Hash-verified and ready for local correction"
        CorrectionModelReadiness.Installed -> "Installed; integrity verification has not run yet"
        CorrectionModelReadiness.Bundled -> "Included in the app; verification prepares its private copy"
    }

    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, CornerstoneOutline),
        colors = CardDefaults.cardColors(containerColor = CornerstoneCard),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            CornerstoneAccentRule(Modifier.padding(bottom = 18.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "MY CORNERSTONE",
                        color = CornerstoneYellow,
                        fontWeight = FontWeight.Black,
                        fontSize = 18.sp,
                        letterSpacing = 0.9.sp,
                    )
                    Text(
                        "Your voice. Your words. You stay in control.",
                        color = CornerstoneMuted,
                        fontSize = 12.sp,
                    )
                }

            }

            Spacer(Modifier.height(18.dp))
            HorizontalDivider(color = Color.White.copy(alpha = 0.09f))
            Spacer(Modifier.height(8.dp))

            StatusLine(
                label = "Cornerstone Keyboard",
                value = when {
                    keyboard.selected -> "ACTIVE"
                    keyboard.enabled -> "SELECT IT"
                    else -> "ENABLE IT"
                },
                color = if (keyboard.selected) CornerstoneSuccess else CornerstoneWarning,
                detail = when {
                    keyboard.selected -> "Selected as Android's current keyboard"
                    keyboard.enabled -> "Enabled, but another keyboard is selected"
                    else -> "Not enabled in Android keyboard settings"
                },
            )

            StatusLine(
                label = "Correction model",
                value = modelValue,
                color = modelColor,
                detail = "${correctionModel.displayName} · ${correctionModel.modelFamily.substringBefore(" · Q4_")} · 529 MB\n$modelDetail",
            )

            StatusLine(
                label = "Cornerstone Voice",
                value = when {
                    voiceReady -> "READY"
                    voiceError != null -> "PAUSED"
                    voiceProgress in 0..99 -> "$voiceProgress%"
                    else -> "NOT INSTALLED"
                },
                color = when {
                    voiceReady -> CornerstoneSuccess
                    voiceError != null -> CornerstoneWarning
                    voiceProgress in 0..99 -> CornerstoneYellow
                    else -> CornerstoneMuted
                },
                detail = "Optional 661 MB download\nOn-device speech recognition after download",
            )

            StatusLine(
                label = "Local correction privacy",
                value = if (onlineFallback) "ONLINE FALLBACK ON" else "ON DEVICE",
                color = if (onlineFallback) CornerstoneWarning else CornerstoneYellow,
                detail = if (onlineFallback) {
                    "Remote dictation polish is enabled"
                } else {
                    "Text stays on this phone during local correction"
                },
            )

            if (lastRun != null) {
                val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(lastRun.timestampMillis))
                StatusLine(
                    label = "Last local run",
                    value = lastRun.result.uppercase(),
                    color = if (lastRun.result == "applied") CornerstoneSuccess else CornerstoneWarning,
                    detail = "This device · $time",
                )
            }

            Spacer(Modifier.height(10.dp))
            HorizontalDivider(color = Color.White.copy(alpha = 0.09f))
            Spacer(Modifier.height(8.dp))

            CornerstoneToggle(
                title = "Local AI correction",
                subtitle = "Repair punctuation and recognition errors while preserving your voice",
                checked = correctionEnabled.value,
                onCheckedChange = correctionEnabled.setValue,
            )
            CornerstoneToggle(
                title = "Show Fix with AI",
                subtitle = "Show a manual on-device correction action above the keys",
                checked = fixChipEnabled.value,
                onCheckedChange = fixChipEnabled.setValue,
            )

            Button(
                onClick = {
                    selfTest = SelfTestState.Running
                    selfTestDetail = "Checking model integrity and one protected sentence…"
                    scope.launch {
                        val outcome = OnDeviceCorrection.correct(
                            context,
                            "do not delete this folder",
                            enabled = true,
                        )
                        when (outcome) {
                            is CorrectionOutcome.Applied -> {
                                selfTest = SelfTestState.Passed
                                selfTestDetail = "Passed locally · \"${outcome.prediction}\""
                            }
                            is CorrectionOutcome.Unchanged -> {
                                selfTest = SelfTestState.Failed
                                selfTestDetail = "Did not pass · ${outcome.reason}"
                            }
                        }
                        refresh += 1
                    }
                },
                enabled = selfTest != SelfTestState.Running,
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = CornerstoneYellow,
                    contentColor = CornerstoneBase,
                    disabledContainerColor = CornerstoneElevated,
                    disabledContentColor = CornerstoneMuted,
                ),
                shape = RoundedCornerShape(12.dp),
            ) {
                if (selfTest == SelfTestState.Running) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = CornerstoneBase,
                    )
                    Text("  Verifying on this device…", fontWeight = FontWeight.Bold)
                } else {
                    Text(
                        if (selfTest == SelfTestState.Passed) "Verify again" else "Verify correction model",
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            if (selfTestDetail != null) {
                Text(
                    selfTestDetail!!,
                    color = when (selfTest) {
                        SelfTestState.Passed -> CornerstoneSuccess
                        SelfTestState.Failed -> CornerstoneDanger
                        SelfTestState.Running -> CornerstoneYellow
                        SelfTestState.Idle -> CornerstoneMuted
                    },
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }

            if (!keyboard.selected) {
                Button(
                    onClick = {
                        if (keyboard.enabled) {
                            val inputMethodManager = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                            inputMethodManager.showInputMethodPicker()
                        } else {
                            context.startActivity(
                                android.content.Intent(Settings.ACTION_INPUT_METHOD_SETTINGS).apply {
                                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                },
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = CornerstoneElevated,
                        contentColor = CornerstoneYellow,
                    ),
                    shape = RoundedCornerShape(9.dp),
                ) {
                    Text(
                        if (keyboard.enabled) "Select Cornerstone Keyboard" else "Enable Cornerstone Keyboard",
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}
