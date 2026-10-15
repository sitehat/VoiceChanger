package com.example.voicechanger

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min

object AudioUtil {
    const val SR = 44100

    fun toShort(x: Float): Short = (x.coerceIn(-1f, 1f) * 32767f).toInt().toShort()

    /** Apply the selected voice to a raw recording. */
    fun render(raw: ShortArray, p: Preset, fine: Float): ShortArray {
        val proc = VoiceProcessor(SR)
        proc.configure(p, fine)
        proc.prepare(raw)
        val out = ShortArray(raw.size)
        for (i in raw.indices) out[i] = toShort(proc.process(raw[i] / 32768f))
        return out
    }

    fun writeWav(os: OutputStream, pcm: ShortArray, sr: Int) {
        val dataLen = pcm.size * 2
        val bb = ByteBuffer.allocate(44 + dataLen).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray())
        bb.putInt(36 + dataLen)
        bb.put("WAVE".toByteArray())
        bb.put("fmt ".toByteArray())
        bb.putInt(16)
        bb.putShort(1.toShort())      // PCM
        bb.putShort(1.toShort())      // mono
        bb.putInt(sr)
        bb.putInt(sr * 2)
        bb.putShort(2.toShort())
        bb.putShort(16.toShort())
        bb.put("data".toByteArray())
        bb.putInt(dataLen)
        for (s in pcm) bb.putShort(s)
        os.write(bb.array())
    }
}

/** Records the raw (unchanged) microphone voice so any voice can be applied afterwards. */
class VoiceRecorder {
    @Volatile var recording = false
    private var thread: Thread? = null
    private var data = ShortArray(AudioUtil.SR * 30)
    private var len = 0
    private val maxLen = AudioUtil.SR * 600   // 10 minutes

    fun start(): Boolean {
        if (recording) return true
        val sr = AudioUtil.SR
        val minIn = AudioRecord.getMinBufferSize(sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC, sr,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                max(minIn, 4096)
            )
        } catch (e: Exception) { return false }
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); return false }

        len = 0
        recording = true
        thread = Thread {
            val buf = ShortArray(1024)
            try {
                rec.startRecording()
                while (recording) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n <= 0) continue
                    if (len + n > maxLen) { recording = false; break }
                    if (len + n > data.size) data = data.copyOf(max(data.size * 2, len + n))
                    System.arraycopy(buf, 0, data, len, n)
                    len += n
                }
            } catch (_: Exception) {
            } finally {
                try { rec.stop() } catch (_: Exception) {}
                rec.release()
                recording = false
            }
        }.also { it.start() }
        return true
    }

    fun stop(): ShortArray {
        recording = false
        try { thread?.join(1500) } catch (_: Exception) {}
        thread = null
        return data.copyOf(len)
    }
}

/** Plays a raw recording through the selected voice. */
class Previewer {
    @Volatile var playing = false
    @Volatile private var gen = 0

    fun stop() {
        gen++
        playing = false
    }

    fun play(raw: ShortArray, p: Preset, fine: Float, onDone: () -> Unit) {
        val myGen = ++gen
        playing = true
        val handler = Handler(Looper.getMainLooper())
        Thread {
            val sr = AudioUtil.SR
            val minOut = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sr)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(max(minOut, 8192))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            val proc = VoiceProcessor(sr)
            proc.configure(p, fine)
            proc.prepare(raw)
            val chunk = ShortArray(1024)
            try {
                track.play()
                var i = 0
                while (gen == myGen && i < raw.size) {
                    val n = min(chunk.size, raw.size - i)
                    for (k in 0 until n) chunk[k] = AudioUtil.toShort(proc.process(raw[i + k] / 32768f))
                    track.write(chunk, 0, n)
                    i += n
                }
            } catch (_: Exception) {
            } finally {
                try { track.stop() } catch (_: Exception) {}
                track.release()
                if (gen == myGen) {
                    playing = false
                    handler.post(onDone)
                }
            }
        }.start()
    }
}
