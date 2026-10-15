# Live Voice Changer (Android)

Open the app -> ON/OFF switch -> pick Male / Female / Child / Robot voice -> speak. Use headphones.

## Record & Save (v1.1)
Tap Record -> speak -> Stop. Then tap any voice to hear it, press Save WAV (goes to Music/VoiceChanger) or Share.
The raw recording is kept, so you can try every voice on the same recording.

## Build the APK with GitHub
1. Create a new GitHub repository and upload ALL files from this zip (keep the `.github` folder).
2. Open the **Actions** tab -> **Build APK** -> wait for the green tick (or press *Run workflow*).
3. Open the finished run -> **Artifacts** -> download `VoiceChanger-debug-apk` -> unzip -> install `app-debug.apk`.

## Build locally
Android Studio (Hedgehog or newer) -> Open this folder -> Run.

## How it works
Mic (AudioRecord) -> pitch shifter + bass/treble EQ (Dsp.kt) -> speaker/headphones (AudioTrack),
running in a foreground service so it keeps working with the screen off.

## Limits (important)
- Works only inside this app / for what the phone speaker plays. Without root, Android does NOT let
  one app change the microphone input of Facebook, Messenger, WhatsApp, YouTube etc.
- Pitch-shifting quality is "good", not AI-perfect. Edit presets in `Presets.kt`.
- Do not use it to impersonate or deceive others.
