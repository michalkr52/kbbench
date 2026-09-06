package com.kbbench.app.settings

import android.content.Context
import android.util.Log
import org.json.JSONObject

/**
 * Persists benchmark configuration across process restarts.
 *
 * Values are small and read once at [com.kbbench.app.viewmodel.CameraViewModel] construction, so
 * SharedPreferences is used directly rather than an asynchronous store that would briefly surface
 * defaults to the UI.
 */
class BenchmarkSettingsStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Enabled algorithm names, or `null` when the user has never made a selection. */
    fun loadEnabledAlgorithms(): Set<String>? =
        prefs.getStringSet(KEY_ENABLED_ALGORITHMS, null)

    fun saveEnabledAlgorithms(names: Set<String>) {
        prefs.edit().putStringSet(KEY_ENABLED_ALGORITHMS, names).apply()
    }

    /** Non-default parameter overrides keyed by algorithm name, then by parameter id. */
    fun loadAlgorithmParameters(): Map<String, Map<String, Double>> {
        val raw = prefs.getString(KEY_ALGORITHM_PARAMETERS, null) ?: return emptyMap()
        return try {
            val root = JSONObject(raw)
            buildMap {
                root.keys().forEach { algorithmName ->
                    val perAlgorithm = root.getJSONObject(algorithmName)
                    val values = buildMap {
                        perAlgorithm.keys().forEach { parameterId ->
                            put(parameterId, perAlgorithm.getDouble(parameterId))
                        }
                    }
                    if (values.isNotEmpty()) put(algorithmName, values)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Discarding unreadable algorithm parameters", e)
            emptyMap()
        }
    }

    fun saveAlgorithmParameters(values: Map<String, Map<String, Double>>) {
        val root = JSONObject()
        values.forEach { (algorithmName, perAlgorithm) ->
            if (perAlgorithm.isEmpty()) return@forEach
            val entry = JSONObject()
            perAlgorithm.forEach { (parameterId, value) -> entry.put(parameterId, value) }
            root.put(algorithmName, entry)
        }
        prefs.edit().putString(KEY_ALGORITHM_PARAMETERS, root.toString()).apply()
    }

    /** Last chosen `ImageFormat` constant, or `null` when the user has never chosen one. */
    fun loadCaptureFormat(): Int? =
        if (prefs.contains(KEY_CAPTURE_FORMAT)) prefs.getInt(KEY_CAPTURE_FORMAT, 0) else null

    fun saveCaptureFormat(format: Int) {
        prefs.edit().putInt(KEY_CAPTURE_FORMAT, format).apply()
    }

    private companion object {
        const val TAG = "BenchmarkSettings"
        const val PREFS_NAME = "kbbench_settings"
        const val KEY_ENABLED_ALGORITHMS = "enabled_algorithms"
        const val KEY_ALGORITHM_PARAMETERS = "algorithm_parameters"
        const val KEY_CAPTURE_FORMAT = "capture_format"
    }
}
