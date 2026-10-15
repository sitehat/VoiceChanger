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
    private var b0: Float, private var b1: Float, private var b2: Float,
    private var a1: Float, private var a2: Float
) {
    /** Change coefficients but keep filter memory (no clicks). */
    fun update(o: Biquad) { b0 = o.b0; b1 = o.b1; b2 = o.b2; a1 = o.a1; a2 = o.a2 }

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

        fun peaking(fs: Int, f0: Float, q: Float, gainDb: Float): Biquad {
            val a = 10.0.pow(gainDb / 40.0).toFloat()
            val w0 = (2 * PI * f0 / fs).toFloat()
            val c = cos(w0); val alpha = sin(w0) / (2f * q)
            val b0 = 1 + alpha * a
            val b1 = -2 * c
            val b2 = 1 - alpha * a
            val a0 = 1 + alpha / a
            val a2 = 1 - alpha / a
            return Biquad(b0 / a0, b1 / a0, b2 / a0, b1 / a0, a2 / a0)
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

/** Full voice chain: pitch shift + EQ + robot + soft clip. Used for live mode, preview and saving. */
class VoiceProcessor(private val sr: Int) {
    private val shifter = PitchShifter(sr)
    private var cur: Preset? = null
    private var bass: Biquad? = null
    private var treble: Biquad? = null
    private var presence: Biquad? = null
    private var match: MatchProcessor? = null
    private var robot = false
    private var bypass = true
    private var robotPhase = 0.0
    private val robotStep = 2 * PI * 50.0 / sr

    fun configure(p: Preset, fine: Float) {
        if (p !== cur) {
            cur = p
            bass = if (p.bassDb != 0f) Biquad.lowShelf(sr, 200f, p.bassDb) else null
            treble = if (p.trebleDb != 0f) Biquad.highShelf(sr, 4000f, p.trebleDb) else null
            presence = if (p.presenceDb != 0f) Biquad.peaking(sr, 2800f, 0.8f, p.presenceDb) else null
            robot = p.robot
        }
        if (p.match) {
            if (match == null) match = MatchProcessor(sr)
            match?.setFine(fine)
        }
        bypass = !p.match && (p.semitones == 0f && !p.robot && fine == 0f)
        val semis = if (p.semitones == 0f && !p.robot) 0f else p.semitones + fine
        shifter.ratio = 2.0.pow(semis / 12.0).toFloat()
    }

    /** For recordings: learn pitch/tone of the whole clip first (Default Voice only). */
    fun prepare(raw: ShortArray) {
        if (cur?.match == true) match = MatchProcessor.warmed(sr, raw)
    }

    fun process(input: Float): Float {
        if (cur?.match == true) return match?.process(input) ?: input
        if (bypass) return input
        var x = shifter.process(input)
        bass?.let { x = it.process(x) }
        treble?.let { x = it.process(x) }
        presence?.let { x = it.process(x) }
        if (robot) {
            x *= (0.5 + 0.5 * sin(robotPhase)).toFloat()
            robotPhase += robotStep
            if (robotPhase > 2 * PI) robotPhase -= 2 * PI
        }
        return kotlin.math.tanh(x * 1.2f)
    }
}
