package com.appsventory.digiapps.nemo.util

import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration

/** True on Android TV / set-top boxes (remote / DPAD navigation, no touch). */
fun Context.isTelevision(): Boolean {
    val uiMode = resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK
    return uiMode == Configuration.UI_MODE_TYPE_TELEVISION ||
        packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
}
