package com.example.voicechanger

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.widget.Button
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var sw: SwitchCompat
    private lateinit var group: RadioGroup
    private lateinit var seek: SeekBar
    private lateinit var fineLabel: TextView
    private lateinit var statusText: TextView
    private lateinit var btnRecord: Button
    private lateinit var btnPlay: Button
    private lateinit var btnSave: Button
    private lateinit var btnShare: Button

    private val recorder = VoiceRecorder()
    private val previewer = Previewer()
    private var raw: ShortArray? = null
    private var selected = 0
    private var updating = false
    private var pendingRecord = false

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (hasMic()) {
                if (pendingRecord) { pendingRecord = false; startRecording() } else startVoice()
            } else {
                pendingRecord = false
                setSwitch(false)
                toast("Microphone permission is required")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        sw = findViewById(R.id.switchOn)
        group = findViewById(R.id.voiceGroup)
        seek = findViewById(R.id.fineSeek)
        fineLabel = findViewById(R.id.fineLabel)
        statusText = findViewById(R.id.statusText)
        btnRecord = findViewById(R.id.btnRecord)
        btnPlay = findViewById(R.id.btnPlay)
        btnSave = findViewById(R.id.btnSave)
        btnShare = findViewById(R.id.btnShare)

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
            if (sw.isChecked) sendStart() else autoPreview()
        }

        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, progress: Int, fromUser: Boolean) {
                fineLabel.text = "Fine tune pitch: ${fine()} semitones"
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {
                if (sw.isChecked) sendStart() else autoPreview()
            }
        })

        sw.setOnCheckedChangeListener { _, on ->
            if (updating) return@setOnCheckedChangeListener
            if (on) {
                if (recorder.recording) stopRecording()
                previewer.stop(); btnPlay.text = PLAY_TEXT
                if (hasMic()) startVoice() else requestPerms()
            } else {
                stopVoice()
            }
        }

        btnRecord.setOnClickListener {
            if (recorder.recording) {
                stopRecording()
            } else if (!hasMic()) {
                pendingRecord = true
                requestPerms()
            } else {
                startRecording()
            }
        }

        btnPlay.setOnClickListener {
            if (previewer.playing) {
                previewer.stop()
                btnPlay.text = PLAY_TEXT
            } else {
                playPreview()
            }
        }

        btnSave.setOnClickListener {
            val uri = saveWav()
            if (uri != null) toast("Saved in Music/VoiceChanger") else toast("Save failed")
        }

        btnShare.setOnClickListener {
            val uri = saveWav()
            if (uri == null) {
                toast("Save failed")
            } else if (Build.VERSION.SDK_INT >= 29) {
                val send = Intent(Intent.ACTION_SEND)
                    .setType("audio/wav")
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                startActivity(Intent.createChooser(send, "Share voice"))
            } else {
                toast("Saved: $uri")
            }
        }
    }

    override fun onResume() {
        super.onResume()
        setSwitch(VoiceService.running)
    }

    override fun onPause() {
        super.onPause()
        previewer.stop()
        btnPlay.text = PLAY_TEXT
        if (recorder.recording) stopRecording()
    }

    // ---------- helpers ----------

    private fun fine(): Float = (seek.progress - 12) * 0.5f

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_LONG).show()

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

    // ---------- live mode ----------

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

    // ---------- record / preview / save ----------

    private fun startRecording() {
        previewer.stop()
        btnPlay.text = PLAY_TEXT
        if (sw.isChecked || VoiceService.running) {
            setSwitch(false)
            stopVoice()
        }
        if (recorder.start()) {
            btnRecord.text = "⏹ Stop recording"
            statusText.text = "Recording... speak now"
        } else {
            toast("Could not start the microphone")
        }
    }

    private fun stopRecording() {
        val data = recorder.stop()
        raw = data
        btnRecord.text = "⏺ Record again"
        val has = data.isNotEmpty()
        btnPlay.isEnabled = has
        btnSave.isEnabled = has
        btnShare.isEnabled = has
        statusText.text = if (has)
            "Recorded ${data.size / AudioUtil.SR} sec. Tap any voice above to hear it, then Save."
        else "Nothing recorded. Try again."
        if (has) playPreview()
    }

    private fun autoPreview() {
        if (raw != null && !recorder.recording) playPreview()
    }

    private fun playPreview() {
        val r = raw ?: return
        if (r.isEmpty()) return
        btnPlay.text = "⏹ Stop"
        previewer.play(r, Presets.ALL[selected], fine()) { btnPlay.text = PLAY_TEXT }
    }

    private fun saveWav(): Uri? {
        val r = raw ?: return null
        val p = Presets.ALL[selected]
        val pcm = AudioUtil.render(r, p, fine())
        val name = "Voice_${p.name.replace(Regex("[^A-Za-z]"), "")}_${System.currentTimeMillis()}.wav"
        return try {
            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.Audio.Media.DISPLAY_NAME, name)
                    put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav")
                    put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/VoiceChanger")
                }
                val uri = contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
                    ?: return null
                contentResolver.openOutputStream(uri)?.use { AudioUtil.writeWav(it, pcm, AudioUtil.SR) }
                uri
            } else {
                val dir = getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: filesDir
                val f = File(dir, name)
                f.outputStream().use { AudioUtil.writeWav(it, pcm, AudioUtil.SR) }
                Uri.fromFile(f)
            }
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        private const val PLAY_TEXT = "▶ Play with selected voice"
    }
}
