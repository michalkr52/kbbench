package com.kbbench.app.preprocessing

import com.kbbench.algorithm.preprocessing.PreprocessingConfig
import com.kbbench.algorithm.preprocessing.ShadingMap
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RawCaptureSnapshotTest {
    @Test
    fun captureDecisionsRoundTripWithoutChangingMapSamples() {
        val snapshot = RawCaptureSnapshot(
            rawWidth = 4096,
            rawHeight = 3072,
            whiteLevel = 4095,
            blackLevel = 64,
            cfaPattern = 0,
            metadataWhiteBalanceGains = listOf(2.0f, 1.0f, 1.5f),
            colorMatrix = List(9) { (it + 1) / 10f },
            shadingMap = ShadingMapSnapshot.from(ShadingMap(
                floatArrayOf(1f, 1.1f, 1.2f, 1.3f, 1.4f, 1.5f, 1.6f, 1.7f,
                1.8f, 1.9f, 2f, 2.1f, 2.2f, 2.3f, 2.4f, 2.5f,
            ), 2, 2)),
            crop = RawCropSnapshot(8, 8, 4080, 3056),
            orientationDegrees = 90,
        )

        val decoded = RawCaptureSnapshot.fromJson(JSONObject(snapshot.toJson().toString()))

        assertEquals(snapshot, decoded)
        assertEquals(snapshot.shadingMap!!.gains, decoded.shadingMap!!.toShadingMap().gains.toList())
    }

    @Test(expected = IllegalArgumentException::class)
    fun futureSnapshotVersionIsRejected() {
        RawCaptureSnapshot.fromJson(RawCaptureSnapshot(
            4, 4, 1023, 64, 0, null, null, null, null, 0,
        ).toJson().put("version", RawCaptureSnapshot.VERSION + 1))
    }

    @Test
    fun renderedRecordDoesNotPretendToHaveRawCaptureDecisions() {
        val json = PreprocessingRecord().toJson()
        assertTrue(json.isNull("capture_snapshot"))
        assertNull(json.optJSONObject("capture_snapshot"))
        assertEquals(PreprocessingConfig(), PreprocessingProfileJson.decode(json.getJSONObject("profile")))
    }
}
