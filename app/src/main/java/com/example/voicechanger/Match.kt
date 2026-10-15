package com.example.voicechanger

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Decodes an mp3 from the assets folder to mono float PCM at AudioUtil.SR. */
object AssetDecoder {
    fun decode(ctx: Context, name: String, maxSec: Int): FloatArray? {
        var afd: AssetFileDescriptor? = null
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            afd = ctx.assets.openFd(name)
            ex.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            var track = -1
            var fmt: MediaFormat? = null
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                if ((f.getString(MediaFormat.KEY_MIME) ?: "").startsWith("audio/")) { track = i; fmt = f; break }
            }
            if (track < 0 || fmt == null) return null
            ex.selectTrack(track)
            var inSr = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(fmt, null, null, 0)
            codec.start()

            val info = MediaCodec.BufferInfo()
            var data = FloatArray(inSr * 30)
            var len = 0
            var inDone = false
            var outDone = false
            while (!outDone) {
                if (!inDone) {
                    val ii = codec.dequeueInputBuffer(10000)
                    if (ii >= 0) {
                        val buf = codec.getInputBuffer(ii)
                        val n = if (buf != null) ex.readSampleData(buf, 0) else -1
                        if (n < 0) {
                            codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inDone = true
                        } else {
                            codec.queueInputBuffer(ii, 0, n, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val oi = codec.dequeueOutputBuffer(info, 10000)
                if (oi >= 0) {
                    val ob = codec.getOutputBuffer(oi)
                    if (ob != null && info.size > 0) {
                        ob.position(info.offset)
                        ob.limit(info.offset + info.size)
                        val sb = ob.order(ByteOrder.nativeOrder()).asShortBuffer()
                        val total = sb.remaining()
                        var k = 0
                        while (k + ch <= total) {
                            var sum = 0f
                            for (c in 0 until ch) sum += sb.get(k + c).toFloat()
                            if (len >= data.size) data = data.copyOf(data.size * 2)
                            data[len++] = sum / ch / 32768f
                            k += ch
                        }
                    }
                    codec.releaseOutputBuffer(oi, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outDone = true
                    if (len >= inSr * maxSec) outDone = true
                } else if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val of = codec.outputFormat
                    inSr = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    ch = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                }
            }

            // linear resample to AudioUtil.SR
            val outLen = (len.toLong() * AudioUtil.SR / inSr).toInt()
            val out = FloatArray(outLen)
            val step = inSr.toDouble() / AudioUtil.SR
            for (i in 0 until outLen) {
                val pos = i * step
                val i0 = pos.toInt()
                val fr = (pos - i0).toFloat()
                val a = data[min(i0, len - 1)]
                val b = data[min(i0 + 1, len - 1)]
                out[i] = a + (b - a) * fr
            }
            return out
        } catch (e: Exception) {
            return null
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            ex.release()
            try { afd?.close() } catch (_: Exception) {}
        }
    }
}

object Fft {
    fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while ((j and bit) != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2.0 * PI / len
            val wr = cos(ang); val wi = sin(ang)
            var i = 0
            while (i < n) {
                var cr = 1.0; var ci = 0.0
                val h = len / 2
                for (k in 0 until h) {
                    val ur = re[i + k]; val ui = im[i + k]
                    val xr = re[i + k + h]; val xi = im[i + k + h]
                    val vr = xr * cr - xi * ci
                    val vi = xr * ci + xi * cr
                    re[i + k] = ur + vr; im[i + k] = ui + vi
                    re[i + k + h] = ur - vr; im[i + k + h] = ui - vi
                    val ncr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr
                    cr = ncr
                }
                i += len
            }
            len = len shl 1
        }
    }
}

object Bands {
    val edges = floatArrayOf(100f, 200f, 315f, 500f, 800f, 1250f, 2000f, 3150f, 5000f, 8000f, 12000f)
    val N = edges.size - 1
    val center = FloatArray(N) { sqrt(edges[it] * edges[it + 1]) }
}

/** Long-term average spectrum in 10 bands (dB, mean removed). */
class Ltas {
    private val size = 1024
    private val re = DoubleArray(size)
    private val im = DoubleArray(size)
    private val win = DoubleArray(size) { 0.5 - 0.5 * cos(2.0 * PI * it / (size - 1)) }
    private val pow = DoubleArray(Bands.N)
    private var n = 0
    private val lo = IntArray(Bands.N) { (Bands.edges[it] * size / AudioUtil.SR).roundToInt() }
    private val hi = IntArray(Bands.N) { max(lo[it] + 1, (Bands.edges[it + 1] * size / AudioUtil.SR).roundToInt()) }

    fun reset() { n = 0; pow.fill(0.0) }

    /** frame must hold 1024 samples. minAlpha 0 = true mean, >0 = running average. */
    fun add(frame: FloatArray, minAlpha: Float) {
        for (i in 0 until size) { re[i] = frame[i] * win[i]; im[i] = 0.0 }
        Fft.fft(re, im)
        n++
        val alpha = max(1.0 / n, minAlpha.toDouble())
        for (b in 0 until Bands.N) {
            var s = 0.0
            for (k in lo[b] until hi[b]) s += re[k] * re[k] + im[k] * im[k]
            val p = s / (hi[b] - lo[b])
            pow[b] = if (n == 1) p else (1 - alpha) * pow[b] + alpha * p
        }
    }

    fun profileDb(): FloatArray? {
        if (n == 0) return null
        val db = FloatArray(Bands.N) { (10.0 * log10(pow[it] + 1e-12)).toFloat() }
        val mean = db.average().toFloat()
        for (i in db.indices) db[i] -= mean
        return db
    }
}

object Detect {
    private const val DSR = 11025f

    /** d = 512 samples at 11025 Hz. Returns F0 in Hz or 0 if unvoiced. */
    fun f0(d: FloatArray, gate: Float = 0.01f): Float {
        var e = 0f
        for (v in d) e += v * v
        if (sqrt(e / d.size) < gate) return 0f
        val minLag = 27
        val maxLag = 157
        val r = FloatArray(maxLag + 2)
        var best = 0
        var bestR = 0f
        for (lag in (minLag - 1)..(maxLag + 1)) {
            var s = 0f
            for (i in 0 until d.size - lag) s += d[i] * d[i + lag]
            val v = s / e
            r[lag] = v
            if (lag in minLag..maxLag && v > bestR) { bestR = v; best = lag }
        }
        if (best == 0 || bestR < 0.5f) return 0f
        val half = best / 2
        if (half >= minLag && r[half] > 0.85f * bestR) return DSR / half
        val a = r[best - 1]; val b = r[best]; val c = r[best + 1]
        val den = a - 2f * b + c
        val shift = if (den != 0f) (0.5f * (a - c) / den).coerceIn(-1f, 1f) else 0f
        return DSR / (best + shift)
    }

    /** Median pitch of a whole signal (44100 Hz input). */
    fun medianF0(pcm: FloatArray): Float {
        val dec = FloatArray(pcm.size / 4) { (pcm[it * 4] + pcm[it * 4 + 1] + pcm[it * 4 + 2] + pcm[it * 4 + 3]) / 4f }
        val frame = FloatArray(512)
        val list = ArrayList<Float>()
        var s = 0
        while (s + 512 <= dec.size) {
            System.arraycopy(dec, s, frame, 0, 512)
            val f = f0(frame)
            if (f > 0f) list.add(f)
            s += 256
        }
        if (list.size < 10) return 0f
        list.sort()
        return list[list.size / 2]
    }
}

/** The voice from assets/default_voice.mp3: measured pitch and tone. */
object TargetVoice {
    @Volatile var f0 = 0f
    @Volatile var profile: FloatArray? = null
    @Volatile var status = "Loading default voice..."
    @Volatile private var started = false

    val ready: Boolean get() = profile != null && f0 > 0f

    fun load(ctx: Context) {
        if (started) return
        started = true
        Thread {
            val pcm = AssetDecoder.decode(ctx, "default_voice.mp3", 90)
            if (pcm == null || pcm.size < AudioUtil.SR) {
                status = "default_voice.mp3 not found in assets (or too short)"
                return@Thread
            }
            val f = Detect.medianF0(pcm)
            if (f <= 0f) {
                status = "No clear voice found in default_voice.mp3"
                return@Thread
            }
            var rms = 0.0
            for (v in pcm) rms += v * v
            val gate = max(0.005, 0.3 * sqrt(rms / pcm.size)).toFloat()
            val ltas = Ltas()
            val frame = FloatArray(1024)
            var s = 0
            while (s + 1024 <= pcm.size) {
                System.arraycopy(pcm, s, frame, 0, 1024)
                var e = 0f
                for (v in frame) e += v * v
                if (sqrt(e / 1024) > gate) ltas.add(frame, 0f)
                s += 1024
            }
            val prof = ltas.profileDb()
            if (prof == null) {
                status = "Could not analyse default_voice.mp3"
                return@Thread
            }
            f0 = f
            profile = prof
            status = "Default voice ready (pitch ${f.toInt()} Hz)"
        }.start()
    }
}

/** Makes ANY input voice follow the pitch and tone of TargetVoice. */
class MatchProcessor(private val sr: Int, private val meanMode: Boolean = false) {
    private var shifter = PitchShifter(sr)
    private val eq = Array(Bands.N) { Biquad.peaking(sr, Bands.center[it], 1.2f, 0f) }
    private val gains = FloatArray(Bands.N)
    private var adapt = true
    private var extraRatio = 1f
    private var ratio = 1f
    private var initialized = false

    private val dec = FloatArray(512)
    private val tmp = FloatArray(512)
    private var decPos = 0
    private var sinceHop = 0
    private var accSum = 0f
    private var accCnt = 0
    private val hist = FloatArray(30)
    private var histN = 0

    private val ltas = Ltas()
    private val outBuf = FloatArray(1024)
    private var outPos = 0
    private var frames = 0

    fun setFine(f: Float) { extraRatio = 2.0.pow(f / 12.0).toFloat() }

    fun process(x: Float): Float {
        if (adapt) trackInput(x)
        shifter.ratio = ratio * extraRatio
        var y = shifter.process(x)
        if (adapt) trackOutput(y)
        for (f in eq) y = f.process(y)
        return kotlin.math.tanh(y * 1.2f)
    }

    private fun trackInput(x: Float) {
        accSum += x
        accCnt++
        if (accCnt < 4) return
        dec[decPos] = accSum / 4f
        decPos = (decPos + 1) % 512
        accSum = 0f
        accCnt = 0
        sinceHop++
        if (sinceHop < 256) return
        sinceHop = 0
        for (i in 0 until 512) tmp[i] = dec[(decPos + i) % 512]
        val f = Detect.f0(tmp)
        if (f > 0f) onVoiced(f)
    }

    private fun onVoiced(f: Float) {
        val t = TargetVoice.f0
        if (t <= 0f) return
        hist[histN % 30] = f
        histN++
        val n = min(histN, 30)
        val sorted = hist.copyOf(n)
        sorted.sort()
        val med = sorted[n / 2]
        val semis = (12.0 * ln((t / med).toDouble()) / ln(2.0)).coerceIn(-14.0, 14.0)
        val desired = 2.0.pow(semis / 12.0).toFloat()
        if (!initialized) { ratio = desired; initialized = true } else ratio += 0.05f * (desired - ratio)
    }

    private fun trackOutput(y: Float) {
        outBuf[outPos++] = y
        if (outPos < 1024) return
        outPos = 0
        var e = 0f
        for (v in outBuf) e += v * v
        if (sqrt(e / 1024) < 0.01f) return
        val tp = TargetVoice.profile ?: return
        ltas.add(outBuf, if (meanMode) 0f else 0.03f)
        frames++
        if (frames % 8 != 0) return
        val src = ltas.profileDb() ?: return
        for (i in 0 until Bands.N) {
            val g = (tp[i] - src[i]).coerceIn(-6f, 6f)
            gains[i] = 0.85f * gains[i] + 0.15f * g
            eq[i].update(Biquad.peaking(sr, Bands.center[i], 1.2f, gains[i]))
        }
    }

    private fun restart() {
        ltas.reset()
        frames = 0
        outPos = 0
        shifter = PitchShifter(sr)
    }

    private fun freeze() {
        adapt = false
        shifter = PitchShifter(sr)
    }

    companion object {
        /** Learn the pitch shift and tone correction from a whole recording first. */
        fun warmed(sr: Int, raw: ShortArray): MatchProcessor {
            val m = MatchProcessor(sr, true)
            for (s in raw) m.process(s / 32768f)
            m.restart()
            for (s in raw) m.process(s / 32768f)
            m.freeze()
            return m
        }
    }
}

/** Best-quality path for recordings: whole-clip analysis, WSOLA pitch shift, fixed tone EQ. */
object MatchOffline {
    @Volatile var lastInfo = ""

    fun render(raw: ShortArray, fine: Float): ShortArray {
        val tp = TargetVoice.profile
        val tf = TargetVoice.f0
        if (tp == null || tf <= 0f || raw.size < 4096) return raw.copyOf()
        val x = FloatArray(raw.size) { raw[it] / 32768f }

        // Step 1: pitch: median over the WHOLE recording -> one fixed shift
        val med = Detect.medianF0(x)
        val semis = if (med > 0f) (12.0 * ln((tf / med).toDouble()) / ln(2.0)).coerceIn(-14.0, 14.0) else 0.0
        val rTotal = 2.0.pow((semis + fine) / 12.0)
        // split the change: part moves the voice-box tone (formants), the rest only moves pitch
        val rho = rTotal.pow(0.35)
        var y = x.copyOf()
        if (abs(rho - 1.0) > 0.005) y = pitchShift(y, rho)
        val rb = rTotal / rho
        if (abs(rb - 1.0) > 0.005) y = psola(y, rb)
        lastInfo = "Your pitch ${med.toInt()} Hz -> default voice ${tf.toInt()} Hz (shift ${"%.1f".format(semis)} semitones)"

        // Step 2: tone: compare average spectrum with the target voice, one fixed EQ
        var rms = 0.0
        for (v in y) rms += v * v
        val gate = max(0.005, 0.3 * sqrt(rms / y.size)).toFloat()
        val ltas = Ltas()
        val frame = FloatArray(1024)
        var s = 0
        while (s + 1024 <= y.size) {
            System.arraycopy(y, s, frame, 0, 1024)
            var e = 0f
            for (v in frame) e += v * v
            if (sqrt(e / 1024) > gate) ltas.add(frame, 0f)
            s += 1024
        }
        val src = ltas.profileDb()
        if (src != null) {
            for (b in 0 until Bands.N) {
                val g = (tp[b] - src[b]).coerceIn(-6f, 6f)
                if (abs(g) < 0.3f) continue
                val f = Biquad.peaking(AudioUtil.SR, Bands.center[b], 1.0f, g)
                for (i in y.indices) y[i] = f.process(y[i])
            }
        }

        // Step 3: level
        var peak = 0f
        for (v in y) peak = max(peak, abs(v))
        val scale = if (peak > 1e-4f) min(0.89f / peak, 8f) else 1f
        return ShortArray(y.size) { AudioUtil.toShort(y[it] * scale) }
    }

    /** Pitch-synchronous overlap-add: changes pitch but keeps the speaker's tone (formants). */
    private fun psola(x: FloatArray, r: Double): FloatArray {
        val n = x.size
        val sr = AudioUtil.SR
        val dec = FloatArray(n / 4) { (x[it * 4] + x[it * 4 + 1] + x[it * 4 + 2] + x[it * 4 + 3]) / 4f }
        val nf = max(1, (dec.size - 512) / 256 + 1)
        val f0 = FloatArray(nf)
        val frame = FloatArray(512)
        for (j in 0 until nf) {
            val s = j * 256
            if (s + 512 > dec.size) break
            System.arraycopy(dec, s, frame, 0, 512)
            f0[j] = Detect.f0(frame, 0.003f)
        }
        for (j in 1 until nf - 1) {
            val a = f0[j - 1]; val b = f0[j]; val c = f0[j + 1]
            if (a > 0f && b > 0f && c > 0f) {
                val med = max(min(a, b), min(max(a, b), c))
                if (abs(b - med) / med > 0.2f) f0[j] = med
            }
        }
        fun f0At(p: Int): Float = f0[((p - 1024) / 1024.0).roundToInt().coerceIn(0, nf - 1)]

        // pitch marks
        val marks = ArrayList<Int>()
        val per = ArrayList<Int>()
        val voiced = ArrayList<Boolean>()
        var m = 0
        var prevVoiced = false
        while (m < n) {
            val f = f0At(m)
            if (f > 0f) {
                val t = (sr / f).toInt().coerceIn(100, 650)
                if (!prevVoiced) {
                    var bm = m
                    var bv = -1e9f
                    for (i in m until min(n, m + t)) if (x[i] > bv) { bv = x[i]; bm = i }
                    m = bm
                }
                marks.add(m); per.add(t); voiced.add(true)
                prevVoiced = true
                val lo = m + (0.7 * t).toInt()
                if (lo >= n) break
                val hi = min(n - 1, m + (1.3 * t).toInt())
                var bm = lo
                var bv = -1e9f
                for (i in lo..hi) if (x[i] > bv) { bv = x[i]; bm = i }
                m = bm
            } else {
                marks.add(m); per.add(220); voiced.add(false)
                prevVoiced = false
                m += 220
            }
        }

        // overlap-add with new spacing
        val y = FloatArray(n)
        val cnt = marks.size
        var t = 0.0
        var k = 0
        while (t < n && cnt > 0) {
            while (k + 1 < cnt && abs(marks[k + 1] - t) <= abs(marks[k] - t)) k++
            val mk = marks[k]
            val len = per[k]
            val v = voiced[k]
            val g = if (v) (1.0 / r).toFloat() else 1f
            val c = t.toInt()
            for (i in -len..len) {
                val s = mk + i
                val d = c + i
                if (s < 0 || s >= n || d < 0 || d >= n) continue
                val w = (0.5 + 0.5 * cos(PI * i / len)).toFloat()
                y[d] += x[s] * w * g
            }
            t += if (v) len / r else len.toDouble()
        }
        return y
    }

    private fun pitchShift(x: FloatArray, r: Double): FloatArray {
        val st = wsola(x, r)
        val out = FloatArray(x.size)
        for (i in out.indices) {
            val pos = i * r
            val i0 = pos.toInt()
            if (i0 + 1 >= st.size) break
            val fr = (pos - i0).toFloat()
            out[i] = st[i0] + (st[i0 + 1] - st[i0]) * fr
        }
        return out
    }

    /** Time-stretch by alpha without changing pitch. */
    private fun wsola(x: FloatArray, alpha: Double): FloatArray {
        val w = 1536
        val hs = 768
        val delta = 480
        val outLen = (x.size * alpha).toInt()
        val y = FloatArray(outLen + 2 * w)
        val win = FloatArray(w) { (0.5 - 0.5 * cos(2.0 * PI * it / w)).toFloat() }
        for (i in 0 until w) y[i] += x[i] * win[i]
        var prev = 0
        var k = 1
        while (true) {
            val outPos = k * hs
            if (outPos >= outLen) break
            val nominal = (outPos / alpha).toInt()
            val target = prev + hs
            val lo = max(0, nominal - delta)
            val hi = min(x.size - w, nominal + delta)
            if (hi < lo) break
            var best = lo
            var bestScore = -1e30f
            var p = lo
            while (p <= hi) {
                var c = 0f
                var e = 0f
                var i = 0
                while (i < hs) {
                    val a = x[p + i]
                    c += a * x[target + i]
                    e += a * a
                    i += 4
                }
                val score = c / sqrt(e + 1e-6f)
                if (score > bestScore) { bestScore = score; best = p }
                p++
            }
            for (i in 0 until w) y[outPos + i] += x[best + i] * win[i]
            prev = best
            k++
        }
        return y.copyOf(outLen)
    }
}
