package com.appsventory.digiapps.nemo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.ui.res.stringResource
import com.appsventory.digiapps.nemo.ui.UpdateScreen
import com.appsventory.digiapps.nemo.ui.theme.setDigiContent

/** Dedicated screen opened from Settings → Version. */
class UpdateActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setDigiContent {
            UpdateScreen(
                title = stringResource(R.string.update_title),
                onBack = { finish() }
            )
        }
    }
}
