package org.futo.inputmethod.latin.uix.settings

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.booleanPreferencesKey
import kotlinx.coroutines.launch
import org.futo.inputmethod.latin.BuildConfig
import org.futo.inputmethod.latin.uix.SettingsKey
import org.futo.inputmethod.latin.uix.setSetting
import org.futo.inputmethod.latin.uix.settings.pages.CornerstoneBase
import org.futo.inputmethod.latin.uix.settings.pages.CornerstoneCard

import org.futo.inputmethod.latin.uix.settings.pages.CornerstoneElevated
import org.futo.inputmethod.latin.uix.settings.pages.CornerstoneMuted
import org.futo.inputmethod.latin.uix.settings.pages.CornerstoneOutline
import org.futo.inputmethod.latin.uix.settings.pages.CornerstoneYellow


@Composable
private fun SetupContainer(
    step: Int,
    totalSteps: Int,
    title: String,
    description: String,
    actionLabel: String,
    onAction: () -> Unit,
    noticeTitle: String? = null,
    notice: String? = null,
    footer: String = "You can switch keyboards or disable Cornerstone at any time in Android Settings.",
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CornerstoneBase)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(top = 72.dp, bottom = 24.dp),
    ) {
        Text(
            "CORNERSTONE",
            color = CornerstoneYellow,
            fontWeight = FontWeight.Black,
            fontSize = 23.sp,
            letterSpacing = 1.1.sp,
        )
        Text(
            "LOCAL  ·  PRIVATE  ·  YOURS",
            color = CornerstoneMuted,
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp,
            letterSpacing = 1.1.sp,
        )
        Spacer(Modifier.height(26.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, CornerstoneOutline),
            colors = CardDefaults.cardColors(containerColor = CornerstoneCard),
        ) {
            Column(modifier = Modifier.padding(22.dp)) {
                Text(
                    "STEP $step OF $totalSteps",
                    color = CornerstoneYellow,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    letterSpacing = 0.8.sp,
                    modifier = Modifier.clearAndSetSemantics {
                        text = AnnotatedString("Step $step of $totalSteps")
                    },
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    repeat(totalSteps) { index ->
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(5.dp)
                                .background(
                                    if (index < step) CornerstoneYellow else CornerstoneElevated,
                                    RoundedCornerShape(3.dp),
                                ),
                        )
                    }
                }

                Spacer(Modifier.height(28.dp))
                Text(
                    title,
                    color = Color.White,
                    fontWeight = FontWeight.Black,
                    fontSize = 29.sp,
                    lineHeight = 34.sp,
                )
                Text(
                    description,
                    color = CornerstoneMuted,
                    fontSize = 16.sp,
                    lineHeight = 23.sp,
                    modifier = Modifier.padding(top = 12.dp),
                )

                if (notice != null) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 20.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = CornerstoneElevated),
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            if (noticeTitle != null) {
                                Text(
                                    noticeTitle,
                                    color = Color.White,
                                    fontWeight = FontWeight.Black,
                                    fontSize = 11.sp,
                                    letterSpacing = 0.7.sp,
                                )
                            }
                            Text(
                                notice,
                                color = CornerstoneMuted,
                                fontSize = 13.sp,
                                lineHeight = 18.sp,
                                modifier = Modifier.padding(top = if (noticeTitle == null) 0.dp else 5.dp),
                            )
                        }
                    }
                }

                Button(
                    onClick = onAction,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 26.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = CornerstoneYellow,
                        contentColor = CornerstoneBase,
                    ),
                ) {
                    Text(
                        actionLabel,
                        fontWeight = FontWeight.Black,
                        fontSize = 15.sp,
                        modifier = Modifier.padding(vertical = 5.dp),
                    )
                }
            }
        }

        Spacer(Modifier.height(24.dp))
        Text(
            footer,
            color = CornerstoneMuted,
            fontSize = 12.sp,
            lineHeight = 17.sp,
            textAlign = TextAlign.Start,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
@Preview(showBackground = true)
fun SetupEnableIME() {
    val context = LocalContext.current
    SetupContainer(
        step = 1,
        totalSteps = 2,
        title = "Enable Cornerstone",
        description = "Android requires you to enable a keyboard before it can be selected. In the next screen, turn on Cornerstone Keyboard.",
        actionLabel = "Open keyboard settings",
        onAction = {
            context.startActivity(
                Intent(Settings.ACTION_INPUT_METHOD_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NO_HISTORY
                },
            )
        },
        noticeTitle = "ABOUT ANDROID'S WARNING",
        notice = "Android shows this standard warning for every third-party keyboard. Cornerstone's local correction model processes text on this device.",
    )
}

@Composable
@Preview(showBackground = true)
fun SetupChangeDefaultIME(doublePackage: Boolean = true) {
    val context = LocalContext.current
    SetupContainer(
        step = 2,
        totalSteps = 2,
        title = "Switch to Cornerstone",
        description = "Tap below to open Android's keyboard picker, then select Cornerstone Keyboard. Return here if Android leaves the picker open; this screen updates automatically.",
        actionLabel = "Open keyboard picker",
        onAction = {
            val inputMethodManager =
                context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            inputMethodManager.showInputMethodPicker()
            (context as? SettingsActivity)?.updateSystemState()
        },
        notice = if (doublePackage) {
            "More than one Cornerstone build is installed. Choose the entry labeled Cornerstone Keyboard."
        } else {
            null
        },
    )
}

val DirectBootWarningDismissed = SettingsKey(
    booleanPreferencesKey("nightly_setup_direct_boot_warning_dismissed"),
    false,
)

@Composable
fun needsToShowDirectBootWarning(): Boolean {
    if (BuildConfig.FLAVOR != "unstable") return false

    val context = LocalContext.current
    val isGraphene = remember {
        context.packageManager.systemAvailableFeatures.any { it.name?.contains("grapheneos") == true } ||
            Build.HOST == "r-0123456789abcdef-0123"
    }
    if (!isGraphene) return false

    return !useDataStoreValue(DirectBootWarningDismissed)
}

@Composable
@Preview(showBackground = true)
fun SetupDirectBootWarning() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    SetupContainer(
        step = 2,
        totalSteps = 2,
        title = "One safety check",
        description = "This is an unstable build. On systems that can disable USB while locked, do not rely on it as your only way to enter a lock-screen password.",
        actionLabel = "I understand",
        onAction = {
            scope.launch { context.setSetting(DirectBootWarningDismissed, true) }
        },
        noticeTitle = "UNSTABLE BUILD",
        notice = "Use a PIN, or keep another reliable unlock path available until this build has been proven on your phone.",
        footer = "This warning only applies to unstable builds on operating systems that can disable USB before first unlock.",
    )
}
