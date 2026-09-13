package com.kbbench.app.preprocessing

import com.kbbench.algorithm.preprocessing.HighlightMode
import com.kbbench.algorithm.preprocessing.LensShadingMode
import com.kbbench.algorithm.preprocessing.PreprocessingConfig
import com.kbbench.algorithm.preprocessing.TransferCurve
import com.kbbench.algorithm.preprocessing.TransferEncoding
import com.kbbench.algorithm.preprocessing.WhiteBalanceGains
import com.kbbench.algorithm.preprocessing.WhiteBalanceMode
import java.util.Locale
import org.json.JSONObject

object PreprocessingProfileJson {
    fun encode(config: PreprocessingConfig): JSONObject {
        val transfer = JSONObject()
            .put("encoding", config.transferCurve.encoding.name.lowercase(Locale.ROOT))
            .put("log_strength", config.transferCurve.logStrength)

        val manualWhiteBalance = if (config.whiteBalance == WhiteBalanceMode.MANUAL) {
            JSONObject()
                .put("red", config.manualWhiteBalance.red.toDouble())
                .put("green", config.manualWhiteBalance.green.toDouble())
                .put("blue", config.manualWhiteBalance.blue.toDouble())
        } else {
            JSONObject.NULL
        }

        val profile = JSONObject()
            .put("transfer", transfer)
            .put("exposure_offset_ev", config.exposureOffsetEv)
            .put("white_balance", config.whiteBalance.name.lowercase(Locale.ROOT))
            .put("manual_white_balance", manualWhiteBalance)
            .put("lens_shading", config.lensShading.name.lowercase(Locale.ROOT))
            .put("highlights", config.highlights.name.lowercase(Locale.ROOT))
        return profile
    }

    fun decode(json: JSONObject): PreprocessingConfig {
        val transfer = json.getJSONObject("transfer")
        val gains = json.optJSONObject("manual_white_balance")
        return PreprocessingConfig(
            transferCurve = TransferCurve(
                TransferEncoding.valueOf(transfer.getString("encoding").uppercase(Locale.ROOT)),
                transfer.getDouble("log_strength"),
            ),
            exposureOffsetEv = json.getDouble("exposure_offset_ev"),
            whiteBalance = WhiteBalanceMode.valueOf(json.getString("white_balance").uppercase(Locale.ROOT)),
            manualWhiteBalance = gains?.let {
                WhiteBalanceGains(
                    it.getDouble("red").toFloat(), it.getDouble("green").toFloat(), it.getDouble("blue").toFloat(),
                )
            } ?: WhiteBalanceGains(),
            lensShading = LensShadingMode.valueOf(json.getString("lens_shading").uppercase(Locale.ROOT)),
            highlights = HighlightMode.valueOf(json.getString("highlights").uppercase(Locale.ROOT)),
        )
    }
}
