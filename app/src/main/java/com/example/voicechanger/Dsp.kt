package com.example.voicechanger

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Real-time delay-line pitch shifter (two crossfading taps). */
class PitchShifter(sampleRate: Int) {
    private val size = 8192
    private val mask = size - 1
    private val buf = FloatArray(size)
    private val win = sampleRate * 0.045f      // ~45 ms window
    private var w = 0
    private var phase = 0f
    var ratio = 1f

    fun process(x: Float): Float {
        buf[w and mask] = x
        phase += (1f - ratio) / win
        while (phase >= 1f) phase -= 1f
        while (phase < 0f) phase += 1f

        var out = 0f
        for (tap in 0..1) {
            var p = phase + tap * 0.5f
            if (p >= 1f) p -= 1f
            val delay = p * win
            val pos = w - delay
            val i0 = Math.floor(pos.toDouble()).toInt()
            val frac = pos - i0
            val a = buf[i0 and mask]
            val b = buf[(i0 + 1) and mask]
            val v = a + (b - a) * frac
            val s = sin(PI.toFloat() * p)
            out += v * s * s
        }
        w++
        return out
    }
}

/** RBJ biquad shelving filter. */
class Biquad private constructor(
    private val b0: Float, private val b1: Float, private val b2: Float,
    private val a1: Float, private val a2: Float
) {
    private var z1 = 0f
    private var z2 = 0f

    fun process(x: Float): Float {
        val y = b0 * x + z1
        z1 = b1 * x - a1 * y + z2
        z2 = b2 * x - a2 * y
        return y
    }

    companion object {
        fun lowShelf(fs: Int, f0: Float, gainDb: Float): Biquad {
            val a = 10.0.pow(gainDb / 40.0).toFloat()
            val w0 = (2 * PI * f0 / fs).toFloat()
            val c = cos(w0); val s = sin(w0)
            val alpha = s / 2f * sqrt(2f)
            val sa = 2f * sqrt(a) * alpha
            val b0 = a * ((a + 1) - (a - 1) * c + sa)
            val b1 = 2 * a * ((a - 1) - (a + 1) * c)
            val b2 = a * ((a + 1) - (a - 1) * c - sa)
            val a0 = (a + 1) + (a - 1) * c + sa
            val a1 = -2 * ((a - 1) + (a + 1) * c)
            val a2 = (a + 1) + (a - 1) * c - sa
            return Biquad(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)
        }

        fun highShelf(fs: Int, f0: Float, gainDb: Float): Biquad {
            val a = 10.0.pow(gainDb / 40.0).toFloat()
            val w0 = (2 * PI * f0 / fs).toFloat()
            val c = cos(w0); val s = sin(w0)
            val alpha = s / 2f * sqrt(2f)
            val sa = 2f * sqrt(a) * alpha
            val b0 = a * ((a + 1) + (a - 1) * c + sa)
            val b1 = -2 * a * ((a - 1) + (a + 1) * c)
            val b2 = a * ((a + 1) + (a - 1) * c - sa)
            val a0 = (a + 1) - (a - 1) * c + sa
            val a1 = 2 * ((a - 1) - (a + 1) * c)
            val a2 = (a + 1) - (a - 1) * c - sa
            return Biquad(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)
        }
    }
}
