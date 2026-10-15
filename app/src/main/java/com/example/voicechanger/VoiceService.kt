package com.example.voicechanger

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

class VoiceService : Service() {

    companion object {
        const val ACTION_START = "com.example.voicechanger.START"
        const val ACTION_STOP = "com.example.voicechanger.STOP"
        const val EXTRA_PRESET = "preset"
        const val EXTRA_FINE = "fine"
        private const val CHANNEL = "voice_changer"
        @Volatile var running = false
    }

    private val engine = VoiceEngine()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            engine.stop()
            running = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val idx = (intent?.getIntExtra(EXTRA_PRESET, 0) ?: 0).coerceIn(0, Presets.ALL.size - 1)
        engine.preset = Presets.ALL[idx]
        engine.fineTune = intent?.getFloatExtra(EXTRA_FINE, 0f) ?: 0f

        if (!running) {
            startAsForeground()
            engine.start()
            running = true
        }
        return START_NOT_STICKY
    }

    private fun startAsForeground() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Voice Changer", NotificationManager.IMPORTANCE_LOW)
        )
        val n: Notification = Notification.Builder(this, CHANNEL)
            .setContentTitle("Voice Changer is ON")
            .setContentText("Your voice is being changed live")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(1, n)
        }
    }

    override fun onDestroy() {
        engine.stop()
        running = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
