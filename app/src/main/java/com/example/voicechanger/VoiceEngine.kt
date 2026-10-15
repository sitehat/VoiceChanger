package com.example.voicechanger

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tanh

class VoiceEngine {
    @Volatile var preset: Preset = Presets.ALL[0]
    @Volatile var fineTune: Float = 0f
    @Volatile private var running = false
    private var thread: Thread? = null

    fun start() {
        if (running) return
        running = true
        thread = Thread { loop() }.apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    fun stop() {
        running = false
        try { thread?.join(800) } catch (_: Exception) {}
        thread = null
    }

    private fun loop() {
        val sr = 44100
        val frame = 512
        val minIn = AudioRecord.getMinBufferSize(sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val minOut = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)

        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC, sr,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                max(minIn, frame * 4)
            )
        } catch (e: Exception) { running = false; return }

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
            .setBufferSizeInBytes(max(minOut, frame * 4))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()

        val shifter = PitchShifter(sr)
        var currentPreset: Preset? = null
        var bass: Biquad? = null
        var treble: Biquad? = null
        var robotPhase = 0.0
        val robotStep = 2 * PI * 50.0 / sr

        val inBuf = ShortArray(frame)
        val outBuf = ShortArray(frame)

        try {
            rec.startRecording()
            track.play()
            while (running) {
                val n = rec.read(inBuf, 0, frame)
                if (n <= 0) continue

                val p = preset
                if (p !== currentPreset) {
                    currentPreset = p
                    bass = if (p.bassDb != 0f) Biquad.lowShelf(sr, 200f, p.bassDb) else null
                    treble = if (p.trebleDb != 0f) Biquad.highShelf(sr, 4000f, p.trebleDb) else null
                }
                val semis = if (p.semitones == 0f && !p.robot) 0f else p.semitones + fineTune
                shifter.ratio = 2.0.pow(semis / 12.0).toFloat()
                val bypass = (p.semitones == 0f && !p.robot && fineTune == 0f)

                for (i in 0 until n) {
                    var x = inBuf[i] / 32768f
                    if (!bypass) {
                        x = shifter.process(x)
                        bass?.let { x = it.process(x) }
                        treble?.let { x = it.process(x) }
                        if (p.robot) {
                            x *= (0.5 + 0.5 * sin(robotPhase)).toFloat()
                            robotPhase += robotStep
                            if (robotPhase > 2 * PI) robotPhase -= 2 * PI
                        }
                        x = tanh(x * 1.2f)
                    }
                    outBuf[i] = (x.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                }
                track.write(outBuf, 0, n)
            }
        } catch (_: Exception) {
        } finally {
            try { rec.stop() } catch (_: Exception) {}
            rec.release()
            try { track.stop() } catch (_: Exception) {}
            track.release()
            running = false
        }
    }
}
