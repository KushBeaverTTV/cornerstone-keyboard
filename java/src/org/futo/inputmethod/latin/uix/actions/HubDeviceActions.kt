package org.futo.inputmethod.latin.uix.actions

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.AlarmClock
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent

/**
 * Real device control for the hub (v2). Every function here does something an
 * Android IME can genuinely do, honestly tiered:
 *
 * - Instant, no permissions: volume (AudioManager.adjustStreamVolume with
 *   FLAG_SHOW_UI so the system HUD confirms), media transport keys
 *   (dispatchMediaKeyEvent DOWN/UP pairs), vibrate (VIBRATE is already
 *   declared in the app manifest for keypress haptics).
 * - One-time grant: screen brightness needs WRITE_SETTINGS; without it we
 *   launch ACTION_MANAGE_WRITE_SETTINGS and say so instead of pretending.
 * - Deliberately absent: Wi-Fi toggle. Silent third-party toggles have been
 *   blocked since Android 10 — a fake row would be theater.
 */
private const val TAG = "HubDeviceActions"

// ---- Sound & media ----

internal fun deviceVolume(context: Context, raise: Boolean) {
    val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
    audio.adjustStreamVolume(
        AudioManager.STREAM_MUSIC,
        if (raise) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
        AudioManager.FLAG_SHOW_UI
    )
}

/** Toggles media-stream mute based on its real current state. */
internal fun deviceMuteToggle(context: Context) {
    val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
    val direction = if (audio.isStreamMute(AudioManager.STREAM_MUSIC)) {
        AudioManager.ADJUST_UNMUTE
    } else {
        AudioManager.ADJUST_MUTE
    }
    audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
}

/** Media transport via key-event pairs — the standard IME-legal route. */
internal fun deviceMediaKey(context: Context, keycode: Int) {
    val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
    audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keycode))
    audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keycode))
}

// ---- Brightness ----

internal sealed class BrightnessResult {
    data class Set(val level: Int) : BrightnessResult()
    object NeedsGrant : BrightnessResult()
    object Unavailable : BrightnessResult()
}

/**
 * Step the manual brightness by [delta], clamped to 1..255. Without the
 * WRITE_SETTINGS grant, opens the grant screen once — after that it just works.
 */
internal fun adjustBrightness(context: Context, delta: Int): BrightnessResult {
    val resolver = context.contentResolver
    return try {
        if (!Settings.System.canWrite(context)) {
            val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .setData(Uri.parse("package:${context.packageName}"))
            try {
                context.startActivity(intent)
            } catch (e: ActivityNotFoundException) {
                Log.w(TAG, "no write-settings screen", e)
            }
            BrightnessResult.NeedsGrant
        } else {
            // Ignore auto-brightness's negative values; step from the last manual level.
            val current = Settings.System.getInt(
                resolver,
                Settings.System.SCREEN_BRIGHTNESS,
                128
            ).coerceIn(1, 255)
            val next = (current + delta).coerceIn(1, 255)
            Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, next)
            BrightnessResult.Set(next)
        }
    } catch (e: Exception) {
        Log.w(TAG, "brightness adjustment failed", e)
        BrightnessResult.Unavailable
    }
}

// ---- Vibrate ----

internal fun deviceVibrate(context: Context) {
    try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            manager?.defaultVibrator?.vibrate(
                VibrationEffect.createOneShot(300, VibrationEffect.DEFAULT_AMPLITUDE)
            )
        } else {
            @Suppress("DEPRECATION")
            val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            vibrator?.vibrate(VibrationEffect.createOneShot(300, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    } catch (e: Exception) {
        Log.w(TAG, "vibrate failed", e)
    }
}

// ---- Flashlight ----

/**
 * Toggle the rear flashlight. CameraManager.setTorchMode needs no CAMERA
 * permission (it never opens the camera). Returns false when no flash unit
 * exists or the toggle was rejected (camera busy → CameraAccessException,
 * device policy) — callers announce that.
 */
internal fun setTorch(context: Context, on: Boolean): Boolean {
    val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
    if (cameraManager == null) {
        Log.w(TAG, "no CameraManager service")
        return false
    }
    val cameraId = try {
        cameraManager.cameraIdList.firstOrNull { id ->
            cameraManager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        }
    } catch (e: Exception) {
        Log.w(TAG, "listing cameras failed", e)
        null
    }
    if (cameraId == null) {
        Log.w(TAG, "no camera with a flash unit")
        return false
    }
    return try {
        cameraManager.setTorchMode(cameraId, on)
        true
    } catch (e: Exception) {
        Log.w(TAG, "setTorchMode($on) failed", e)
        false
    }
}

// ---- Alarm ----

/**
 * Open the clock app's alarm flow. ACTION_SET_ALARM requires the SET_ALARM
 * permission, which we deliberately do NOT declare (no new permissions for
 * this wave) — so on devices that enforce it the direct intent throws
 * SecurityException and we fall back to ACTION_SHOW_ALARMS, which needs no
 * permission and still lands the user one tap from "new alarm". Returns
 * false only when no clock app handles either intent.
 */
internal fun openAlarmSetter(context: Context): Boolean {
    val setAlarm = Intent(AlarmClock.ACTION_SET_ALARM).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(setAlarm)
        return true
    } catch (e: SecurityException) {
        // Expected without SET_ALARM — fall through to the permission-free intent.
        Log.i(TAG, "SET_ALARM not granted, falling back to SHOW_ALARMS")
    } catch (e: ActivityNotFoundException) {
        Log.i(TAG, "no handler for SET_ALARM, falling back to SHOW_ALARMS")
    }

    val showAlarms = Intent(AlarmClock.ACTION_SHOW_ALARMS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return try {
        context.startActivity(showAlarms)
        true
    } catch (e: Exception) {
        Log.w(TAG, "no clock app handles alarms", e)
        false
    }
}
