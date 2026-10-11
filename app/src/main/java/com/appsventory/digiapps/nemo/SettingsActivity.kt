package com.appsventory.digiapps.nemo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.ui.res.stringResource
import com.appsventory.digiapps.nemo.ui.SettingsScreen
import com.appsventory.digiapps.nemo.ui.theme.setDigiContent

/** App settings (UI only for now; rows are not wired yet). */
class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setDigiContent {
            SettingsScreen(
                title = stringResource(R.string.menu_settings),
                onBack = { finish() }
            )
        }
    }
}
