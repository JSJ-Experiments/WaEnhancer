package com.wmods.wppenhacer.provider

import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.crossbowffs.remotepreferences.RemotePreferenceProvider
import com.wmods.wppenhacer.BuildConfig

class RemotePreferenceProvider : RemotePreferenceProvider(
    BuildConfig.APPLICATION_ID + ".preferences",
    arrayOf(BuildConfig.APPLICATION_ID + "_preferences")
) {
    override fun onCreate(): Boolean {
        val start = SystemClock.elapsedRealtime()
        Log.i("WAE-Prefs", "provider onCreate start pid=${Process.myPid()}")
        return try {
            super.onCreate()
        } finally {
            Log.i("WAE-Prefs", "provider onCreate complete in ${SystemClock.elapsedRealtime() - start}ms")
        }
    }

    override fun checkAccess(prefFileName: String, prefKey: String, write: Boolean): Boolean {
        return !write
    }
}
