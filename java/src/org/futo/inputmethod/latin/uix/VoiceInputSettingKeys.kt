package org.futo.inputmethod.latin.uix

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey

val ENABLE_SOUND = SettingsKey(
    key = booleanPreferencesKey("enable_sounds"),
    default = true
)

val VERBOSE_PROGRESS = SettingsKey(
    key = booleanPreferencesKey("verbose_progress"),
    default = false
)

val ENABLE_ENGLISH = SettingsKey(
    key = booleanPreferencesKey("enable_english"),
    default = true
)

val ENABLE_MULTILINGUAL = SettingsKey(
    key = booleanPreferencesKey("enable_multilingual"),
    default = false
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

// KBP: auto-stop on silence cut people off mid-thought — default OFF now.
// Users who want it can re-enable in Voice Input settings.
val USE_VAD_AUTOSTOP = SettingsKey(
    key = booleanPreferencesKey("use_vad_autostop"),
    default = true
)

val ENGLISH_MODEL_INDEX = SettingsKey(
    key = intPreferencesKey("english_model_index"),
    default = 0
)

val MULTILINGUAL_MODEL_INDEX = SettingsKey(
    key = intPreferencesKey("multilingual_model_index"),
    default = 1
)

val LANGUAGE_TOGGLES = SettingsKey(
    key = stringSetPreferencesKey("enabled_languages"),
    default = setOf()
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
