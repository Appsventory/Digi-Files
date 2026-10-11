package com.appsventory.digiapps.nemo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.ui.res.stringResource
import com.appsventory.digiapps.nemo.ui.AppsScreen
import com.appsventory.digiapps.nemo.ui.theme.setDigiContent

/** Lists every installed app (user + system). Tap to launch. */
class AppsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setDigiContent {
            AppsScreen(
                title = stringResource(R.string.tool_user_apps),
                onBack = { finish() }
            )
        }
    }
}
