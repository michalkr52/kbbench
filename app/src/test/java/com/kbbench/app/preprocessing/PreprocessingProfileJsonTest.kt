package com.kbbench.app.preprocessing

import com.kbbench.algorithm.preprocessing.HighlightMode
import com.kbbench.algorithm.preprocessing.AppliedLensShading
import com.kbbench.algorithm.preprocessing.PreprocessingConfig
import com.kbbench.algorithm.preprocessing.ResolvedRawPreprocessing
import com.kbbench.algorithm.preprocessing.TransferCurve
import com.kbbench.algorithm.preprocessing.TransferEncoding
import com.kbbench.algorithm.preprocessing.WhiteBalanceGains
import com.kbbench.algorithm.preprocessing.WhiteBalanceMode
import com.kbbench.algorithm.preprocessing.WhiteBalanceSource
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreprocessingProfileJsonTest {
    @Test
    fun everyCurveRoundTripsWithExplicitDefaultsAndManualValues() {
        for (encoding in TransferEncoding.entries) {
            val profile = PreprocessingConfig(
                transferCurve = TransferCurve(encoding, 37.5), exposureOffsetEv = -1.25,
                whiteBalance = WhiteBalanceMode.MANUAL, manualWhiteBalance = WhiteBalanceGains(2f, 1f, 0.7f),
                highlights = HighlightMode.CLIP_CHANNELS,
            )
            val json = PreprocessingProfileJson.encode(profile)
            assertTrue(json.has("lens_shading"))
            assertEquals(profile, PreprocessingProfileJson.decode(JSONObject(json.toString())))
        }
        assertEquals(PreprocessingConfig(), PreprocessingProfileJson.decode(PreprocessingProfileJson.encode(PreprocessingConfig())))
    }

    @Test(expected = IllegalArgumentException::class)
    fun futureProfileVersionIsRejected() {
        PreprocessingProfileJson.decode(PreprocessingProfileJson.encode(PreprocessingConfig()).put("version", 100))
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidExposureIsRejected() {
        PreprocessingProfileJson.decode(PreprocessingProfileJson.encode(PreprocessingConfig()).put("exposure_offset_ev", 100))
    }

    @Test
    fun renderedAndNativeRawDomainsAreDistinguished() {
        val profile = PreprocessingConfig(transferCurve = TransferCurve(TransferEncoding.LINEAR))
        val rendered = PreprocessingRecord(profile, PreprocessingSource.PLATFORM_DNG).toJson()
        assertFalse(rendered.getBoolean("raw_controls_available"))
        assertEquals("rendered_derived", rendered.getString("signal_origin"))
        assertEquals("SRGB", rendered.getJSONObject("display").getString("transfer"))
        val raw = PreprocessingRecord(profile, PreprocessingSource.CAMERA_RAW).toJson()
        assertTrue(raw.getBoolean("raw_controls_available"))
        assertEquals("uncalibrated_camera_rgb", raw.getString("primaries"))
        assertEquals(profile, PreprocessingProfileJson.decode(raw.getJSONObject("profile")))
    }

    @Test
    fun runRecordKeepsItsProfileWhenPreferenceChanges() {
        var preference = PreprocessingConfig(transferCurve = TransferCurve(TransferEncoding.LOG, 21.0))
        val run = PreprocessingRecord(preference)
        preference = preference.copy(exposureOffsetEv = 2.0)
        assertEquals(0.0, PreprocessingProfileJson.decode(run.toJson().getJSONObject("profile")).exposureOffsetEv, 0.0)
        assertEquals(2.0, preference.exposureOffsetEv, 0.0)
    }

    @Test
    fun stageOrderOmitsUnavailableCorrectionsAndDefaultRenderedReencoding() {
        val profile = PreprocessingConfig()
        val settings = ResolvedRawPreprocessing(
            profile, WhiteBalanceGains(1f, 1f, 1f), WhiteBalanceSource.METADATA_MISSING,
            AppliedLensShading.UNAVAILABLE, 0, 0,
        )
        val raw = PreprocessingRecord(profile, PreprocessingSource.CAMERA_RAW, listOf(settings)).toJson()
        val stages = raw.getJSONArray("effective_raw_frames").getJSONObject(0).getJSONArray("stage_order")
        val names = (0 until stages.length()).map { stages.getString(it) }
        assertFalse("lens_shading_clip" in names)
        assertFalse("color_matrix" in names)
        assertTrue("bilinear_demosaic" in names)
        val renderedStages = PreprocessingRecord().toJson().getJSONArray("rendered_stage_order")
        assertEquals(1, renderedStages.length())
        assertEquals("decode_srgb", renderedStages.getString(0))
    }
}
