package org.futo.inputmethod.latin.uix.actions

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.futo.inputmethod.latin.R
import org.futo.inputmethod.latin.uix.Action
import org.futo.inputmethod.latin.uix.ActionWindow
import org.futo.inputmethod.latin.uix.CloseResult
import org.futo.inputmethod.latin.uix.KeyboardManagerForAction

private class DeviceActionWindow(val manager: KeyboardManagerForAction) : ActionWindow() {
    val context = manager.getContext()
    private var torchOn by mutableStateOf(false)

    @Composable
    override fun windowName(): String = "Device"

    @Composable
    override fun WindowContents(keyboardShown: Boolean) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            HubSectionLabel("DEVICE")
            Spacer(modifier = Modifier.height(8.dp))
            HubRowCard(
                listOf(
                    HubRow("Volume up") {
                        deviceVolume(context, true)
                        manager.announce("Volume up")
                    },
                    HubRow("Volume down") {
                        deviceVolume(context, false)
                        manager.announce("Volume down")
                    },
                    HubRow(if (torchOn) "Flashlight (on)" else "Flashlight") {
                        if (setTorch(context, !torchOn)) {
                            torchOn = !torchOn
                            manager.announce(if (torchOn) "Flashlight on" else "Flashlight off")
                        }
                    }
                )
            )
        }
    }

    override fun close(): CloseResult {
        if (torchOn) {
            setTorch(context, false)
            torchOn = false
        }
        return CloseResult.Default
    }
}

val DeviceAction = Action(
    icon = R.drawable.settings,
    name = R.string.action_device_title,
    simplePressImpl = null,
    windowImpl = { manager, _ -> DeviceActionWindow(manager) },
)
