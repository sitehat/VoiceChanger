package com.example.voicechanger

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import kotlin.math.max

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
        val sr = AudioUtil.SR
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

        val proc = VoiceProcessor(sr)
        val inBuf = ShortArray(frame)
        val outBuf = ShortArray(frame)

        try {
            rec.startRecording()
            track.play()
            while (running) {
                val n = rec.read(inBuf, 0, frame)
                if (n <= 0) continue
                proc.configure(preset, fineTune)
                for (i in 0 until n) {
                    outBuf[i] = AudioUtil.toShort(proc.process(inBuf[i] / 32768f))
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
