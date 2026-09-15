package org.futo.voiceinput.shared.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.text
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.futo.voiceinput.shared.R
import org.futo.voiceinput.shared.types.MagnitudeState
import org.futo.voiceinput.shared.ui.theme.Typography

data class MicrophoneDeviceState(
    val bluetoothAvailable: Boolean,
    val bluetoothActive: Boolean,
    val bluetoothPreferredByUser: Boolean,
    val setBluetooth: (Boolean) -> Unit,
    val deviceName: String
)

@Composable
fun AnimatedRecognizeCircle(magnitude: MutableFloatState = mutableFloatStateOf(0.5f)) {
    val radius = animateValueChanges(magnitude.floatValue, 100)
    val color = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)

    val radiusMod = with(LocalDensity.current) {
        80.dp.toPx()
    }

    Canvas(modifier = Modifier.fillMaxSize()) {
        val drawRadius = radiusMod * (0.8f + radius * 2.0f)
        drawCircle(color = color, radius = drawRadius)
    }
}

@Composable
private fun FakeToast(modifier: Modifier, message: String?) {
    val visible = remember { mutableStateOf(false) }

    LaunchedEffect(message) {
        if(message != null) {
            visible.value = true
            delay(2500L)
            visible.value = false
        } else {
            visible.value = false
        }
    }

    if(message != null) {
        AnimatedVisibility(
            visible = visible.value,
            modifier = modifier
                .alpha(0.9f)
                .background(MaterialTheme.colorScheme.surfaceDim, RoundedCornerShape(100))
                .padding(16.dp),
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Box {
                Text(message, modifier = Modifier.align(Alignment.Center), style = Typography.labelSmall, color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

@Composable
private fun BoxScope.BluetoothToggleIcon(device: MutableState<MicrophoneDeviceState>? = null) {
    FakeToast(modifier = Modifier.align(Alignment.BottomCenter).offset(y = (-16).dp),
        message = if(device?.value?.bluetoothActive == true) {
            stringResource(R.string.using_bluetooth_mic, device.value.deviceName)
        } else if(device?.value?.bluetoothAvailable == true || device?.value?.bluetoothPreferredByUser == true) {
            stringResource(R.string.using_built_in_mic, device.value.deviceName)
        } else {
            null
        }
    )

    if(device?.value?.bluetoothAvailable == true) {
        val bluetoothColor = MaterialTheme.colorScheme.primary
        val iconColor = if(device.value.bluetoothActive) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.onSurface
        }

        IconButton(modifier = Modifier
            .align(Alignment.BottomEnd)
            .offset(x = (-16).dp, y = (-16).dp)
            .drawBehind {
                val radius = size.height / 4.0f
                drawRoundRect(
                    bluetoothColor,
                    topLeft = Offset(size.width * 0.1f, size.height * 0.05f),
                    size = Size(size.width * 0.8f, size.height * 0.9f),
                    cornerRadius = CornerRadius(radius, radius),
                    style = if (device.value.bluetoothActive) {
                        Fill
                    } else {
                        Stroke(width = 4.0f)
                    }
                )
            }
            .clearAndSetSemantics {
                this.text = AnnotatedString("Use bluetooth mic")
                this.role = Role.Switch
                this.toggleableState = ToggleableState(device.value.bluetoothActive)
            },
            onClick = {
                device.value.setBluetooth(!device.value.bluetoothActive)
            },
        ) {
            Icon(
                painter = painterResource(id = R.drawable.bluetooth),
                contentDescription = null,
                tint = iconColor
            )
        }
    }
}

@Composable
private fun VoiceStatusBar(
    autoStopSecondsRemaining: Int?,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val statusText = if (autoStopSecondsRemaining == null) {
        stringResource(R.string.listening)
    } else {
        stringResource(R.string.voice_silence_detected)
    }
    val hintText = autoStopSecondsRemaining?.let {
        pluralStringResource(R.plurals.voice_stopping_in_seconds, it, it)
    } ?: stringResource(R.string.voice_auto_finish_hint)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = statusText,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
                style = Typography.labelSmall,
            )
            Text(
                text = hintText,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Button(
            onClick = onDone,
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
            contentPadding = PaddingValues(horizontal = 22.dp, vertical = 10.dp),
        ) {
            Text(stringResource(R.string.voice_finish_now), fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun InnerRecognize(
    magnitude: MutableFloatState = mutableFloatStateOf(0.5f),
    state: MutableState<MagnitudeState> = mutableStateOf(MagnitudeState.MIC_MAY_BE_BLOCKED),
    device: MutableState<MicrophoneDeviceState>? = null,
    autoStopSecondsRemaining: Int? = null,
    onDone: () -> Unit = {},
) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AnimatedRecognizeCircle(magnitude = magnitude)

        Icon(
            painter = painterResource(R.drawable.mic_2_),
            contentDescription = stringResource(R.string.stop_recording),
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onSurface,
        )

        val text = when (state.value) {
            MagnitudeState.NOT_TALKED_YET -> stringResource(R.string.try_saying_something)
            MagnitudeState.MIC_MAY_BE_BLOCKED -> stringResource(R.string.no_audio_detected_is_your_microphone_blocked)
            MagnitudeState.TALKING -> stringResource(R.string.listening)
        }

        Text(
            text,
            modifier = Modifier
                .fillMaxWidth()
                .offset(x = 0.dp, y = 48.dp),
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface,
        )

        VoiceStatusBar(
            autoStopSecondsRemaining = autoStopSecondsRemaining,
            onDone = onDone,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
        BluetoothToggleIcon(device)
    }
}


@Composable
fun ColumnScope.RecognizeLoadingCircle(text: String = "Initializing...") {
    CircularProgressIndicator(
        modifier = Modifier.align(Alignment.CenterHorizontally),
        color = MaterialTheme.colorScheme.primary
    )
    Spacer(modifier = Modifier.height(8.dp))
    Text(text, modifier = Modifier.align(Alignment.CenterHorizontally))
}

@Composable
fun ColumnScope.PartialDecodingResult(
    text: String = "I am speaking […]",
    autoStopSecondsRemaining: Int? = null,
    onDone: () -> Unit = {},
) {
    VoiceStatusBar(
        autoStopSecondsRemaining = autoStopSecondsRemaining,
        onDone = onDone,
    )
    Surface(
        modifier = Modifier
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)),
    ) {
        Text(
            text,
            modifier = Modifier
                .align(Alignment.Start)
                .padding(12.dp)
                .defaultMinSize(0.dp, 64.dp),
            textAlign = TextAlign.Start,
            color = MaterialTheme.colorScheme.onSurface,
            style = Typography.bodyMedium,
        )
    }
}

@Composable
fun ColumnScope.RecognizeMicError(openSettings: () -> Unit) {
    Box(Modifier
        .fillMaxSize()
        .clickable { openSettings() }) {
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                stringResource(R.string.grant_microphone_permission_to_use_voice_input),
                modifier = Modifier
                    .padding(8.dp, 2.dp)
                    .align(Alignment.CenterHorizontally),
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface
            )
            Icon(
                Icons.Default.Settings,
                contentDescription = stringResource(R.string.grant_microphone_permission_to_use_voice_input),
                modifier = Modifier.size(32.dp),
                tint = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}