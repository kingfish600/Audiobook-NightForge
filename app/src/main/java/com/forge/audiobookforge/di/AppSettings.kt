package com.forge.audiobookforge.di

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Small typed wrapper over SharedPreferences, exposed as StateFlows for Compose. */
class AppSettings(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("forge_settings", Context.MODE_PRIVATE)

    private val _numThreads = MutableStateFlow(prefs.getInt(KEY_THREADS, 4))
    val numThreads: StateFlow<Int> = _numThreads.asStateFlow()

    private val _preferInt8 = MutableStateFlow(prefs.getBoolean(KEY_INT8, true))
    val preferInt8: StateFlow<Boolean> = _preferInt8.asStateFlow()

    private val _requireCharging = MutableStateFlow(prefs.getBoolean(KEY_CHARGING, false))
    val requireCharging: StateFlow<Boolean> = _requireCharging.asStateFlow()

    private val _segmentChars = MutableStateFlow(prefs.getInt(KEY_SEGMENT, 280))
    val segmentChars: StateFlow<Int> = _segmentChars.asStateFlow()

    private val _codec = MutableStateFlow(prefs.getString(KEY_CODEC, "aac") ?: "aac")
    val codec: StateFlow<String> = _codec.asStateFlow()

    private val _exportTreeUri = MutableStateFlow(prefs.getString(KEY_EXPORT_TREE, null))
    val exportTreeUri: StateFlow<String?> = _exportTreeUri.asStateFlow()

    fun setNumThreads(v: Int) { prefs.edit().putInt(KEY_THREADS, v).apply(); _numThreads.value = v }
    fun setPreferInt8(v: Boolean) { prefs.edit().putBoolean(KEY_INT8, v).apply(); _preferInt8.value = v }
    fun setRequireCharging(v: Boolean) { prefs.edit().putBoolean(KEY_CHARGING, v).apply(); _requireCharging.value = v }
    fun setCodec(v: String) { prefs.edit().putString(KEY_CODEC, v).apply(); _codec.value = v }
    fun setExportTreeUri(v: String?) {
        prefs.edit().putString(KEY_EXPORT_TREE, v).apply(); _exportTreeUri.value = v
    }
    fun setSegmentChars(v: Int) {
        val snapped = (v / 40) * 40
        prefs.edit().putInt(KEY_SEGMENT, snapped).apply(); _segmentChars.value = snapped
    }

    companion object {
        const val KEY_THREADS = "num_threads"
        const val KEY_INT8 = "prefer_int8"
        const val KEY_CHARGING = "require_charging"
        const val KEY_SEGMENT = "segment_chars"
        const val KEY_CODEC = "audio_codec"
        const val KEY_EXPORT_TREE = "export_tree_uri"
    }
}
