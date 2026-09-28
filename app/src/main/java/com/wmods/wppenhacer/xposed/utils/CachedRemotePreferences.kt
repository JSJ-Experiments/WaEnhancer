package com.wmods.wppenhacer.xposed.utils

import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Keeps remote module settings in the WhatsApp process. Initialization takes one snapshot; after
 * that, hooks never need to cold-start the module's provider on WhatsApp's main thread.
 */
class CachedRemotePreferences(private val remote: SharedPreferences) : SharedPreferences {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val refreshExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "WAE-PreferenceRefresh").apply { isDaemon = true }
    }
    private val listeners = CopyOnWriteArraySet<SharedPreferences.OnSharedPreferenceChangeListener>()
    private val refreshRunning = AtomicBoolean(false)
    private val refreshPending = AtomicBoolean(false)

    @Volatile
    private var snapshot: Map<String, Any?> = emptyMap()

    @Volatile
    private var lastRefreshStart = 0L

    private val remoteListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        refreshAsync(force = true)
    }

    init {
        val start = SystemClock.elapsedRealtime()
        Log.i(TAG, "initial snapshot start pid=${Process.myPid()}")
        try {
            remote.registerOnSharedPreferenceChangeListener(remoteListener)
        } catch (error: Exception) {
            Log.w(TAG, "could not register preference change observer", error)
        }
        try {
            snapshot = readSnapshot()
            lastRefreshStart = start
            Log.i(TAG, "initial snapshot complete in ${SystemClock.elapsedRealtime() - start}ms; entries=${snapshot.size}")
        } catch (error: Exception) {
            Log.w(TAG, "initial snapshot failed after ${SystemClock.elapsedRealtime() - start}ms", error)
            refreshAsync(force = true)
        }
    }

    /** Refresh on a worker after returning to WhatsApp; never wait for the provider on the UI thread. */
    fun refreshAsync(force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastRefreshStart < REFRESH_INTERVAL_MS) return
        refreshPending.set(true)
        if (!refreshRunning.compareAndSet(false, true)) return

        refreshExecutor.execute {
            try {
                do {
                    refreshPending.set(false)
                    lastRefreshStart = SystemClock.elapsedRealtime()
                    Log.i(TAG, "background snapshot start pid=${Process.myPid()}")
                    try {
                        val updated = readSnapshot()
                        val previous = snapshot
                        snapshot = updated
                        val changedKeys = (previous.keys + updated.keys).filter { previous[it] != updated[it] }
                        if (changedKeys.isNotEmpty() && listeners.isNotEmpty()) {
                            mainHandler.post {
                                changedKeys.forEach { key ->
                                    listeners.forEach { listener ->
                                        listener.onSharedPreferenceChanged(this, key)
                                    }
                                }
                            }
                        }
                        Log.i(TAG, "background snapshot complete in ${SystemClock.elapsedRealtime() - lastRefreshStart}ms; entries=${updated.size}")
                    } catch (error: Exception) {
                        Log.w(TAG, "background snapshot failed after ${SystemClock.elapsedRealtime() - lastRefreshStart}ms", error)
                    }
                } while (refreshPending.get())
            } finally {
                refreshRunning.set(false)
                if (refreshPending.get()) refreshAsync(force = true)
            }
        }
    }

    private fun readSnapshot(): Map<String, Any?> = remote.all.mapValues { (_, value) ->
        if (value is Set<*>) value.toSet() else value
    }

    override fun getAll(): Map<String, *> = snapshot.toMap()

    override fun getString(key: String, defValue: String?): String? =
        snapshot[key]?.let { it as String } ?: defValue

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, defValues: Set<String?>?): Set<String?>? =
        snapshot[key]?.let { (it as Set<String?>).toSet() } ?: defValues

    override fun getInt(key: String, defValue: Int): Int =
        snapshot[key]?.let { it as Int } ?: defValue

    override fun getLong(key: String, defValue: Long): Long =
        snapshot[key]?.let { it as Long } ?: defValue

    override fun getFloat(key: String, defValue: Float): Float =
        snapshot[key]?.let { it as Float } ?: defValue

    override fun getBoolean(key: String, defValue: Boolean): Boolean =
        snapshot[key]?.let { it as Boolean } ?: defValue

    override fun contains(key: String): Boolean = snapshot.containsKey(key)

    // The provider denies writes, so do not issue a pointless main-thread IPC on commit/apply.
    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        override fun putString(key: String, value: String?): SharedPreferences.Editor = this
        override fun putStringSet(key: String, values: Set<String?>?): SharedPreferences.Editor = this
        override fun putInt(key: String, value: Int): SharedPreferences.Editor = this
        override fun putLong(key: String, value: Long): SharedPreferences.Editor = this
        override fun putFloat(key: String, value: Float): SharedPreferences.Editor = this
        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor = this
        override fun remove(key: String): SharedPreferences.Editor = this
        override fun clear(): SharedPreferences.Editor = this
        override fun commit(): Boolean = false
        override fun apply() = Unit
    }

    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        listeners.add(listener)
    }

    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        listeners.remove(listener)
    }

    private companion object {
        const val TAG = "WAE-Prefs"
        const val REFRESH_INTERVAL_MS = 60_000L
    }
}
