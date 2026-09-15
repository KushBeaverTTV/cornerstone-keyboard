package org.futo.inputmethod.latin.uix

import androidx.datastore.preferences.core.booleanPreferencesKey

val ENABLE_SOUND = SettingsKey(
    key = booleanPreferencesKey("enable_sounds"),
    default = true
)

val DISALLOW_SYMBOLS = SettingsKey(
    key = booleanPreferencesKey("disallow_symbols"),
    default = true
)

val PREFER_BLUETOOTH = SettingsKey(
    key = booleanPreferencesKey("prefer_bluetooth_recording"),
    default = false
)

val CAN_EXPAND_SPACE = SettingsKey(
    key = booleanPreferencesKey("can_expand_space"),
    default = true
)

val AUDIO_FOCUS = SettingsKey(
    key = booleanPreferencesKey("request_audio_focus"),
    default = true
)

// KBP: auto-stop on silence. ON by default, but smarter than the old gate that
// cut people off mid-thought: a ~3s silence window with countdown PLUS a ~1s
// minimum-speech requirement (VadPolicy), and trailing non-speech is trimmed
// from the final decode (TrailingTrimPolicy) so noise can't become garbage text.
val USE_VAD_AUTOSTOP = SettingsKey(
    key = booleanPreferencesKey("use_vad_autostop"),
    default = true
)

val USE_PERSONAL_DICT = SettingsKey(
    key = booleanPreferencesKey("use_personal_dict_voice_input"),
    default = true
)

val ANIMATE_BUBBLE = SettingsKey(
    key = booleanPreferencesKey("animate_bubble"),
    default = true
)

// KBP: online punctuation+capitalization polish for final transcripts. Strictly
// opt-in: defaults OFF, never blocks insertion, falls back to offline-processed
// text on any failure.
val POLISH_DICTATION_ONLINE = SettingsKey(
    key = booleanPreferencesKey("polish_dictation_online"),
    default = false
)

// KBP: keep the Parakeet engine resident and pre-load it when the keyboard
// appears, so the first mic tap never pays the multi-second model load. This
// is the same "always warm" behavior the Cornerstone Voice bubble gets from
// being a foreground service. Costs ~600MB of RAM while the keyboard process
// lives; turn off on a memory-starved device.
val VOICE_KEEP_WARM = SettingsKey(
    key = booleanPreferencesKey("voice_keep_engine_warm"),
    default = true
)
