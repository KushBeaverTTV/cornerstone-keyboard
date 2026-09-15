package org.futo.inputmethod.latin.uix.settings.pages


import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text

import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment.Companion.CenterVertically
import androidx.compose.ui.Modifier

import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import org.futo.inputmethod.latin.BuildConfig
import org.futo.inputmethod.latin.R
import org.futo.inputmethod.latin.uix.LocalNavController

import org.futo.inputmethod.latin.uix.USE_SYSTEM_VOICE_INPUT
import org.futo.inputmethod.latin.uix.settings.NavigationItem
import org.futo.inputmethod.latin.uix.settings.NavigationItemStyle
import org.futo.inputmethod.latin.uix.settings.UserSetting
import org.futo.inputmethod.latin.uix.settings.UserSettingsMenu
import org.futo.inputmethod.latin.uix.settings.render
import org.futo.inputmethod.latin.uix.settings.useDataStoreValue
import org.futo.inputmethod.latin.uix.settings.userSettingDecorationOnly
import org.futo.inputmethod.latin.uix.settings.userSettingNavigationItem
import org.futo.inputmethod.latin.uix.theme.Typography

private fun homeSection(label: String) = userSettingDecorationOnly {
    Text(
        label,
        color = CornerstoneYellow,
        fontWeight = FontWeight.Black,
        fontSize = 11.sp,
        letterSpacing = 1.0.sp,
        modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 3.dp),
    )
}

val HomeScreenLite = UserSettingsMenu(
    title = R.string.settings_home_title,
    navPath = "home", registerNavPath = false,
    settings = listOf(
        homeSection("INPUT"),
        userSettingNavigationItem(
            title = R.string.language_settings_title,
            style = NavigationItemStyle.HomePrimary,
            navigateTo = "languages",
            icon = R.drawable.globe
        ),
        userSettingNavigationItem(
            title = R.string.prediction_settings_title,
            style = NavigationItemStyle.HomePrimary,
            navigateTo = PredictiveTextMenu.navPath,
            icon = R.drawable.text_prediction
        ),
        userSettingNavigationItem(
            title = R.string.settings_keyboard_typing_title,
            style = NavigationItemStyle.HomePrimary,
            navigateTo = "keyboardAndTyping",
            icon = R.drawable.keyboard
        ),

        userSettingNavigationItem(
            title = SwipeMenu.title,
            style = NavigationItemStyle.HomePrimary,
            navigateTo = SwipeMenu.navPath,
            icon = R.drawable.swipe_icon
        ),

        UserSetting(
            name = R.string.voice_input_settings_title
        ) {
            val navController = LocalNavController.current
            NavigationItem(
                title = stringResource(R.string.voice_input_settings_title),
                style = NavigationItemStyle.HomePrimary,
                subtitle = if(useDataStoreValue(USE_SYSTEM_VOICE_INPUT)) {
                    "Android system voice selected"
                } else { "On-device speech recognition" },
                navigate = { navController!!.navigate(VoiceInputMenu.navPath) },
                icon = painterResource(R.drawable.mic_fill)
            )
        },

        homeSection("APPEARANCE & CONTROLS"),
        userSettingNavigationItem(
            title = R.string.action_settings_title,
            style = NavigationItemStyle.HomePrimary,
            navigateTo = "actions",
            icon = R.drawable.smile
        ),

        userSettingNavigationItem(
            title = R.string.theme_settings_title,
            style = NavigationItemStyle.HomePrimary,
            navigateTo = "themes",
            icon = R.drawable.themes
        ),

        homeSection("AI & TOOLS"),
        userSettingNavigationItem(
            title = R.string.settings_agents_title,
            style = NavigationItemStyle.HomePrimary,
            navigateTo = "agents",
            icon = R.drawable.agent
        ),
        userSettingNavigationItem(
            title = R.string.dev_settings_title,
            style = NavigationItemStyle.HomePrimary,
            navigateTo = "developer",
            icon = R.drawable.code
        ).copy(visibilityCheck = {
            useDataStoreValue(IS_DEVELOPER) == true || LocalInspectionMode.current
        }),

        homeSection("ABOUT"),
        userSettingNavigationItem(
            title = R.string.misc_settings_title,
            style = NavigationItemStyle.MiscNoArrow,
            navigateTo = "misc",
        ),

        userSettingNavigationItem(
            title = R.string.credits_menu_title,
            style = NavigationItemStyle.MiscNoArrow,
            navigateTo = "credits",
        ),
    )
)

@Preview(showBackground = true)
@Composable
fun HomeScreen(navController: NavHostController = rememberNavController()) {
    val scrollState = rememberScrollState()

    Column {
        Column(
            modifier = Modifier
                .weight(1.0f)
                .fillMaxWidth()
                .verticalScroll(scrollState)
        ) {
            Spacer(modifier = Modifier.height(4.dp))
            Row(Modifier.padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 8.dp)) {
                Text("Cornerstone", style = Typography.Heading.Medium, modifier = Modifier
                    .align(CenterVertically)
                    .weight(1.0f))

                Spacer(Modifier.width(4.dp))

                IconButton(onClick = {
                    navController.navigate("search")
                }) {
                    Icon(Icons.Default.Search, contentDescription = stringResource(
                        R.string.settings_search_menu_title
                    ))
                }
            }
            CornerstoneAccentRule(Modifier.padding(start = 16.dp, bottom = 8.dp))

            CornerstoneDashboard()

            HomeScreenLite.render(showTitle = false)


            Spacer(modifier = Modifier.height(16.dp))

            Text(
                "v${BuildConfig.VERSION_NAME}",
                style = Typography.Small,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}