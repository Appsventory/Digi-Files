package com.appsventory.digiapps.nemo.util

import android.content.Context

data class StorageRoot(val path: String, val label: String)

/** Every mounted local storage (internal, SD, USB) as scan roots. */
fun storageRoots(context: Context, internalLabel: String): List<StorageRoot> =
    detectVolumes(context, internalLabel).map { StorageRoot(it.path, it.label) }
