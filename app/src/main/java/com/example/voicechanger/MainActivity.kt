package com.example.voicechanger

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var sw: SwitchCompat
    private lateinit var group: RadioGroup
    private lateinit var seek: SeekBar
    private lateinit var fineLabel: TextView
    private var selected = 0
    private var updating = false

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (hasMic()) {
                startVoice()
            } else {
                setSwitch(false)
                Toast.makeText(this, "Microphone permission is required", Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        sw = findViewById(R.id.switchOn)
        group = findViewById(R.id.voiceGroup)
        seek = findViewById(R.id.fineSeek)
        fineLabel = findViewById(R.id.fineLabel)

        Presets.ALL.forEachIndexed { i, p ->
            val rb = RadioButton(this)
            rb.id = 1000 + i
            rb.text = p.name
            rb.textSize = 16f
            group.addView(rb)
        }
        group.check(1000)

        group.setOnCheckedChangeListener { _, id ->
            selected = id - 1000
            if (sw.isChecked) sendStart()
        }

        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, progress: Int, fromUser: Boolean) {
                fineLabel.text = "Fine tune pitch: ${fine()} semitones"
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {
                if (sw.isChecked) sendStart()
            }
        })

        sw.setOnCheckedChangeListener { _, on ->
            if (updating) return@setOnCheckedChangeListener
            if (on) {
                if (hasMic()) startVoice() else requestPerms()
            } else {
                stopVoice()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        setSwitch(VoiceService.running)
    }

    private fun fine(): Float = (seek.progress - 12) * 0.5f

    private fun setSwitch(v: Boolean) {
        updating = true
        sw.isChecked = v
        updating = false
    }

    private fun hasMic() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun requestPerms() {
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        permLauncher.launch(perms.toTypedArray())
    }

    private fun startVoice() {
        setSwitch(true)
        sendStart()
    }

    private fun sendStart() {
        val i = Intent(this, VoiceService::class.java)
            .setAction(VoiceService.ACTION_START)
            .putExtra(VoiceService.EXTRA_PRESET, selected)
            .putExtra(VoiceService.EXTRA_FINE, fine())
        ContextCompat.startForegroundService(this, i)
    }

    private fun stopVoice() {
        startService(Intent(this, VoiceService::class.java).setAction(VoiceService.ACTION_STOP))
    }
}
