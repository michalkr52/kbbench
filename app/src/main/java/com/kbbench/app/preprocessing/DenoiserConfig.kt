package com.kbbench.app.preprocessing

import com.kbbench.algorithm.base.Denoiser
import com.kbbench.algorithm.preprocessing.GuidedFilterDenoiser
import java.util.Locale
import org.json.JSONObject

enum class DenoiserChoice {
    OFF,
    GUIDED_FILTER,
    GUIDED_FILTER_COLOR,
}

data class DenoiserConfig(
    val choice: DenoiserChoice = DenoiserChoice.OFF,
    val radius: Int = 8,
    val eps: Double = 0.2 * 0.2,
) {
    init {
        require(radius in 1..32) { "Denoiser radius must be between 1 and 32" }
        require(eps.isFinite() && eps in 0.001..0.25) {
            "Denoiser epsilon must be between 0.001 and 0.25"
        }
    }

    val isEnabled: Boolean get() = choice != DenoiserChoice.OFF

    fun create(): Denoiser? = when (choice) {
        DenoiserChoice.OFF -> null
        DenoiserChoice.GUIDED_FILTER -> GuidedFilterDenoiser(radius, eps, colorGuidance = false)
        DenoiserChoice.GUIDED_FILTER_COLOR -> GuidedFilterDenoiser(radius, eps, colorGuidance = true)
    }

    fun parameters(): Map<String, Double> = mapOf(
        "radius" to radius.toDouble(),
        "eps" to eps,
    )

    fun summary(): String? = if (!isEnabled) null else {
        val name = when (choice) {
            DenoiserChoice.OFF -> error("Disabled denoiser has no summary")
            DenoiserChoice.GUIDED_FILTER -> "Guided Filter"
            DenoiserChoice.GUIDED_FILTER_COLOR -> "Guided Filter Color"
        }
        "$name / r=$radius / eps=${"%.3f".format(Locale.US, eps)}"
    }
}

object DenoiserConfigJson {
    fun encode(config: DenoiserConfig): JSONObject = JSONObject()
        .put("choice", config.choice.name.lowercase(Locale.ROOT))
        .put("radius", config.radius)
        .put("eps", config.eps)

    fun decode(json: JSONObject): DenoiserConfig = DenoiserConfig(
        choice = DenoiserChoice.valueOf(json.getString("choice").uppercase(Locale.ROOT)),
        radius = json.optInt("radius", 8),
        eps = json.optDouble("eps", 0.2 * 0.2),
    )
}
