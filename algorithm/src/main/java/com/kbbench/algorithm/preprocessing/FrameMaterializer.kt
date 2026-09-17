package com.kbbench.algorithm.preprocessing

import com.kbbench.algorithm.base.Frame
import com.kbbench.algorithm.base.FrameDomain

/** Converts the fixed preprocessing boundary into the bounded frame terminal algorithms consume. */
object FrameMaterializer {
    fun materialize(frame: Frame, transferCurve: TransferCurve = TransferCurve()): Frame {
        if (!frame.isUnclipped) return frame

        val red = ShortArray(frame.size)
        val green = ShortArray(frame.size)
        val blue = ShortArray(frame.size)
        for (index in 0 until frame.size) {
            red[index] = Frame.store(encode(frame.r(index), frame.domain, transferCurve))
            green[index] = Frame.store(encode(frame.g(index), frame.domain, transferCurve))
            blue[index] = Frame.store(encode(frame.b(index), frame.domain, transferCurve))
        }
        return Frame(
            red = red,
            green = green,
            blue = blue,
            width = frame.width,
            height = frame.height,
            sourceDepth = frame.sourceDepth,
            domain = outputDomain(frame.domain, transferCurve),
        )
    }

    private fun encode(value: Float, domain: FrameDomain, transferCurve: TransferCurve): Float {
        val bounded = value.coerceIn(0f, 1f)
        return if (domain == FrameDomain.LINEAR) {
            transferCurve.encode(bounded.toDouble()).toFloat()
        } else {
            bounded
        }
    }

    private fun outputDomain(domain: FrameDomain, transferCurve: TransferCurve): FrameDomain {
        if (domain != FrameDomain.LINEAR) return domain
        return when (transferCurve.encoding) {
            TransferEncoding.LINEAR -> FrameDomain.LINEAR
            TransferEncoding.SRGB -> FrameDomain.SRGB
            TransferEncoding.LOG -> FrameDomain.LOG
        }
    }
}
