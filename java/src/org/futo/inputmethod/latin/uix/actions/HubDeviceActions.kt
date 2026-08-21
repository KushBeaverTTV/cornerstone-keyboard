package org.futo.inputmethod.latin.uix.actions

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.provider.AlarmClock
import android.util.Log
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

// Find-my-phone is a prototype stub: the hub has no such endpoint today
// (verified 404 on 2026-08-21), so callers must treat `false` as "not wired
// yet" and say so in the UI — never pretend the request did something.
private const val FIND_MY_PHONE_ENDPOINT = "http://100.100.194.62:8877/findmyphone"

/**
 * Toggle the rear flashlight. CameraManager.setTorchMode needs no CAMERA
 * permission (it never opens the camera), so this is the one device action
 * an IME can do cleanly. Returns false when no flash unit exists or the
 * toggle was rejected (camera busy, device policy) — callers announce that.
 */
internal fun setTorch(context: Context, on: Boolean): Boolean {
    val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
    if (cameraManager == null) {
        Log.w("HubDeviceActions", "no CameraManager service")
        return false
    }
    val cameraId = try {
        cameraManager.cameraIdList.firstOrNull { id ->
            cameraManager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        }
    } catch (e: Exception) {
        Log.w("HubDeviceActions", "listing cameras failed", e)
        null
    }
    if (cameraId == null) {
        Log.w("HubDeviceActions", "no camera with a flash unit")
        return false
    }
    return try {
        cameraManager.setTorchMode(cameraId, on)
        true
    } catch (e: Exception) {
        Log.w("HubDeviceActions", "setTorchMode($on) failed", e)
        false
    }
}

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
        Log.i("HubDeviceActions", "SET_ALARM not granted, falling back to SHOW_ALARMS")
    } catch (e: ActivityNotFoundException) {
        Log.i("HubDeviceActions", "no handler for SET_ALARM, falling back to SHOW_ALARMS")
    }

    val showAlarms = Intent(AlarmClock.ACTION_SHOW_ALARMS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return try {
        context.startActivity(showAlarms)
        true
    } catch (e: Exception) {
        Log.w("HubDeviceActions", "no clock app handles alarms", e)
        false
    }
}

/**
 * Ask the hub to ring the phone. Same Bearer contract as postToAgent. True
 * only on a 2xx — a 404 (endpoint doesn't exist yet) or any network failure
 * both come back false so the UI can report "not wired yet" honestly.
 */
internal fun requestFindMyPhone(token: String): Boolean {
    val request = Request.Builder()
        .url(FIND_MY_PHONE_ENDPOINT)
        .header("Authorization", "Bearer $token")
        .post("{}".toRequestBody(null))
        .build()
    return try {
        agentHttpClient.newCall(request).execute().use { it.isSuccessful }
    } catch (e: Exception) {
        Log.w("HubDeviceActions", "find-my-phone request failed", e)
        false
    }
}
