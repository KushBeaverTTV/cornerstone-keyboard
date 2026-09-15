package org.futo.inputmethod.latin.uix.settings.pages

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import org.futo.inputmethod.latin.R
import org.futo.inputmethod.latin.uix.ANIMATE_BUBBLE
import org.futo.inputmethod.latin.uix.AUDIO_FOCUS
import org.futo.inputmethod.latin.uix.CAN_EXPAND_SPACE
import org.futo.inputmethod.latin.uix.DISALLOW_SYMBOLS
import org.futo.inputmethod.latin.uix.ENABLE_SOUND
import org.futo.inputmethod.latin.uix.PREFER_BLUETOOTH

import org.futo.inputmethod.latin.uix.USE_PERSONAL_DICT
import org.futo.inputmethod.latin.uix.USE_SYSTEM_VOICE_INPUT
import org.futo.inputmethod.latin.uix.USE_VAD_AUTOSTOP
import org.futo.inputmethod.latin.uix.VOICE_KEEP_WARM
import org.futo.inputmethod.latin.uix.settings.NavigationItemStyle
import org.futo.inputmethod.latin.uix.settings.UserSettingsMenu
import org.futo.inputmethod.latin.uix.settings.useDataStoreValue
import org.futo.inputmethod.latin.uix.settings.userSettingDecorationOnly
import org.futo.inputmethod.latin.uix.settings.userSettingNavigationItem
import org.futo.inputmethod.latin.uix.settings.userSettingToggleDataStore
import org.futo.voiceinput.shared.ggml.ParakeetModelDownloader

private val visibilityCheckCornerstoneVoiceReady = @Composable {
    useDataStoreValue(USE_SYSTEM_VOICE_INPUT) == false &&
        ParakeetModelDownloader.isModelFullyDownloaded(LocalContext.current)
}

private const val BLUETOOTH_CONNECT_REQUEST_CODE = 43

// Routing the mic through a Bluetooth device needs BLUETOOTH_CONNECT on API
// 31+ — ask for it the moment the user enables the toggle, not mid-dictation.
@Composable
private fun BluetoothMicPermissionRequest() {
    val preferBluetooth = useDataStoreValue(PREFER_BLUETOOTH)
    val context = LocalContext.current
    LaunchedEffect(preferBluetooth) {
        if (preferBluetooth && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            (context as? Activity)?.requestPermissions(
                arrayOf(Manifest.permission.BLUETOOTH_CONNECT),
                BLUETOOTH_CONNECT_REQUEST_CODE
            )
        }
    }
}

val VoiceInputMenu = UserSettingsMenu(
    title = R.string.voice_input_settings_title,
    navPath = "voiceInput", registerNavPath = true,
    settings = listOf(
        userSettingDecorationOnly { CornerstoneVoiceStatusCard() },

        //if(!systemVoiceInput.value) {
        userSettingToggleDataStore(
            title = R.string.voice_input_settings_indication_sounds,
            subtitle = R.string.voice_input_settings_indication_sounds_subtitle,
            setting = ENABLE_SOUND
        ).copy(visibilityCheck = visibilityCheckCornerstoneVoiceReady),

        userSettingToggleDataStore(
            title = R.string.voice_input_settings_use_personal_dict,
            subtitle = R.string.voice_input_settings_use_personal_dict_subtitle,
            setting = USE_PERSONAL_DICT
        ).copy(visibilityCheck = visibilityCheckCornerstoneVoiceReady),

        userSettingToggleDataStore(
            title = R.string.voice_input_settings_use_bluetooth_mic,
            subtitle = R.string.voice_input_settings_use_bluetooth_mic_subtitle,
            setting = PREFER_BLUETOOTH
        ).copy(visibilityCheck = visibilityCheckCornerstoneVoiceReady),

        userSettingDecorationOnly { BluetoothMicPermissionRequest() },

        userSettingToggleDataStore(
            title = R.string.voice_input_settings_audio_focus,
            subtitle = R.string.voice_input_settings_audio_focus_subtitle,
            setting = AUDIO_FOCUS
        ).copy(visibilityCheck = visibilityCheckCornerstoneVoiceReady),

        userSettingToggleDataStore(
            title = R.string.voice_input_settings_suppress_symbols,
            setting = DISALLOW_SYMBOLS
        ).copy(visibilityCheck = visibilityCheckCornerstoneVoiceReady),

        userSettingToggleDataStore(
            title = R.string.voice_input_settings_long_form,
            subtitle = R.string.voice_input_settings_long_form_subtitle,
            setting = CAN_EXPAND_SPACE
        ).copy(visibilityCheck = visibilityCheckCornerstoneVoiceReady),

        userSettingToggleDataStore(
            title = R.string.voice_input_settings_autostop_vad,
            subtitle = R.string.voice_input_settings_autostop_vad_subtitle,
            setting = USE_VAD_AUTOSTOP
        ).copy(visibilityCheck = visibilityCheckCornerstoneVoiceReady),

        userSettingToggleDataStore(
            title = R.string.voice_input_settings_keep_warm,
            subtitle = R.string.voice_input_settings_keep_warm_subtitle,
            setting = VOICE_KEEP_WARM
        ).copy(visibilityCheck = visibilityCheckCornerstoneVoiceReady),

        userSettingToggleDataStore(
            title = R.string.voice_input_settings_animate_bubble,
            subtitle = R.string.voice_input_settings_animate_bubble_subtitle,
            setting = ANIMATE_BUBBLE
        ).copy(visibilityCheck = visibilityCheckCornerstoneVoiceReady),

        userSettingNavigationItem(
            title = R.string.voice_input_settings_change_models,
            subtitle = R.string.voice_input_settings_change_models_subtitle,
            style = NavigationItemStyle.Misc,
            navigateTo = "languages"
        ).copy(visibilityCheck = visibilityCheckCornerstoneVoiceReady),
        //}
    )
)