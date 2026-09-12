package com.kbbench.app.preprocessing

import com.kbbench.algorithm.preprocessing.HighlightMode
import com.kbbench.algorithm.preprocessing.LensShadingMode
import com.kbbench.algorithm.preprocessing.PreprocessingConfig
import com.kbbench.algorithm.preprocessing.TransferCurve
import com.kbbench.algorithm.preprocessing.TransferEncoding
import com.kbbench.algorithm.preprocessing.WhiteBalanceGains
import com.kbbench.algorithm.preprocessing.WhiteBalanceMode
import org.json.JSONObject

object PreprocessingProfileJson {
    fun encode(config: PreprocessingConfig): JSONObject = JSONObject()
        .put("version", PreprocessingConfig.VERSION)
        .put("transfer", JSONObject()
            .put("version", TransferCurve.VERSION)
            .put("encoding", config.transferCurve.encoding.name)
            .put("log_strength", config.transferCurve.logStrength))
        .put("exposure_offset_ev", config.exposureOffsetEv)
        .put("white_balance", config.whiteBalance.name)
        .put("manual_white_balance", JSONObject()
            .put("red", config.manualWhiteBalance.red.toDouble())
            .put("green", config.manualWhiteBalance.green.toDouble())
            .put("blue", config.manualWhiteBalance.blue.toDouble()))
        .put("lens_shading", config.lensShading.name)
        .put("highlights", config.highlights.name)

    fun decode(json: JSONObject): PreprocessingConfig {
        require(json.getInt("version") == PreprocessingConfig.VERSION) { "Unsupported preprocessing profile version" }
        val transfer = json.getJSONObject("transfer")
        require(transfer.getInt("version") == TransferCurve.VERSION) { "Unsupported transfer version" }
        val gains = json.getJSONObject("manual_white_balance")
        return PreprocessingConfig(
            transferCurve = TransferCurve(
                TransferEncoding.valueOf(transfer.getString("encoding")), transfer.getDouble("log_strength"),
            ),
            exposureOffsetEv = json.getDouble("exposure_offset_ev"),
            whiteBalance = WhiteBalanceMode.valueOf(json.getString("white_balance")),
            manualWhiteBalance = WhiteBalanceGains(
                gains.getDouble("red").toFloat(), gains.getDouble("green").toFloat(), gains.getDouble("blue").toFloat(),
            ),
            lensShading = LensShadingMode.valueOf(json.getString("lens_shading")),
            highlights = HighlightMode.valueOf(json.getString("highlights")),
        )
    }
}
