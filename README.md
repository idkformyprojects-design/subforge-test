# SubForge (v1.7.0)

Android app that makes subtitles for any video, in any language, using your own **free** Google Gemini key.

## What works in this version

1. **Choose a video** (MP4, MKV, MOV, WebM… through Android's file picker).
2. **Make subtitles from**
   - **Speech in the video**: the phone pulls out the sound (16 kHz mono MP3, ~1.2 MB per 5 minutes) and sends it to Gemini in 5-minute parts. Gemini writes timed subtitles, translated into the language you pick (or kept in the original language).
   - **Existing subtitle track** (SRT/ASS/VTT/mov_text inside an MKV/MP4): copied out and translated.
3. **Edit** every line (text, start/end time), add or delete lines, shift all timings.
4. **Save** a `.srt` file, or **export the video**:
   - MP4 + subtitle track (fast, no quality loss)
   - MKV + subtitle track (fast, works with any audio format)
   - Burn subtitles into the picture (slow, but shows on every player, WhatsApp, etc.)

The video never leaves the phone. Only the sound goes to Gemini.

### New in v1.7.0
- **Video preview in the editor** with the current subtitle drawn on the picture; tap a line to play from it,
  hold to edit; the spoken line is highlighted and followed. "Video" in the header hides or shows it.
- **New app icon** (adaptive, with an Android 13+ themed version). Play Store icon and feature graphic in `store-assets/`.

### New in v1.6.0 (Play Store preparation)
- Targets **Android 16 (API 36)**; own header bar with correct edge-to-edge spacing on Android 8–16.
- Burn-in fixed: uses the phone's hardware H.264 encoder, falling back to OpenH264 (this FFmpeg build has no x264).
- Removed Play-restricted permissions (battery-optimisation request, exact alarms).
- **Report this line** for AI-made subtitles (Play's AI-content rule) and an **About, privacy & licences** screen.
- Handles Android 15+'s background time limit cleanly. GitHub build now also produces the **AAB** for Google Play.
- Privacy policy: [PRIVACY.md](PRIVACY.md). FFmpeg here is LGPL v3 (no GPL parts).

### New in v1.5.0
- **"About the video" box:** title, character/place names and spoken language are sent with every request, so names are
  spelled right and ambiguous words get the right meaning.
- **Sound clean-up before sending** (on by default, Settings): cuts rumble and hiss and lifts quiet speech, on the phone.
- **More free requests per day:** Google counts the free limit per model, so the default backup list now includes more free
  models (gemini-3.6-flash, gemini-3.5-flash-lite, gemini-3.1-flash-lite). Missing or used-up models are asked only once per job.

### New in v1.4.0
- **Connect to Google in 3 taps, no typing.** A setup card opens Google's key page; after "Create API key" → "Copy",
  SubForge finds the copied key by itself, tests it, and clears it from the clipboard. "Paste" and "Get a free key" also in Settings.

### New in v1.3.0
- **Waits can't get stuck any more.** Retries use the real clock plus a system wake-up alarm, so phones that pause
  background apps (Oppo/Realme/Xiaomi) still retry on time. The app and notification show "Next try at …" and a **Try now** button.
- **Respects Google's limits.** Uses Google's own "wait N seconds" hint for the per-minute limit, and stops waiting at once
  when the best model's **daily** free limit is used up ("re-check tomorrow").
- **Cancel is instant**, even in the middle of a request.
- **Storage:** saved audio is deleted automatically when a job is fully done. **History & storage** screen: past subtitles
  (reopen, edit, export), space used, Clear saved audio, Clear history.
- Clearer double-check option, and a reminder when the phone is limiting background work.

### New in v1.2.0
- **Runs in the background** with a progress notification and a Cancel button. Lock the phone or use other apps.
- **Never gives up because Google is busy.** The best model is tried first; if it stays busy, a backup model
  (Settings → backup models) makes that part so the job keeps going.
- **Automatic re-check.** Parts made by a backup model are redone by the best model as soon as it is free
  (it keeps trying for up to 45 minutes; "Re-check now" in the editor tries again later).
- **Double-check (optional switch).** The best model listens to every part again next to the finished subtitles
  and fixes misheard words, names, missing lines, timing and translation. Uses about twice the requests.
- **Notifications:** "Subtitles ready" when you can start editing, and "All done ✓" when re-checks finish.
- **Continue after interruptions.** Every finished part is saved; "Continue" picks up where it stopped.
- **Your edits are safe.** Background re-checks never overwrite a part you edited by hand.
- Editor marks: ✓ corrected by the best model · ⚠ made by a backup model · ✎ edited by you.

## Not in this version (yet)

- Reading subtitles that are burned into the picture (OCR)
- Picture-based DVD/Blu-ray subtitle tracks

## Setup on the phone

1. Open SubForge and tap **Open Google's key page** in the "Connect to Google" box.
2. On Google's page tap **Create API key**, then **Copy**, and come back. SubForge picks up the key by itself.
3. Default models: best `gemini-flash-latest`, backups `gemini-flash-latest, gemini-flash-lite-latest`.
   "-latest" names follow Google's newest model, so they do not get retired. One key works for all models.
4. When asked, allow notifications and "run in background without restrictions" so long jobs are not stopped.

## Build the APK

### Easiest: GitHub (free, nothing to install)
1. Create a GitHub repository and upload this whole folder (the `.github` folder must be at the top level).
2. Open the **Actions** tab → **Build APK** → **Run workflow**.
3. After ~5 minutes, download **SubForge-APK** from the finished run and install it on your phone.

Without signing secrets the APK is signed with a debug key. That's fine for your own phone. To publish
updates that install over each other, add the 4 secrets listed in `.github/workflows/build-apk.yml`.

### On a computer
```bash
cd android-app
./gradlew :app:assembleRelease      # needs JDK 17 and Android SDK 34 (ANDROID_HOME set)
# APK: app/build/outputs/apk/release/
```

## Project layout

```
android-app/app/src/main/java/ai/subforge/universal/
  MainActivity.kt      choose video, mode, language, settings; runs the job
  EditorActivity.kt    edit lines, save .srt, export video
  core/Media.kt        all FFmpeg work (audio clips, subtitle track, mux, burn-in)
  core/Gemini.kt       Gemini calls (transcribe audio clip, translate lines), retries + free-tier pacing
  JobService.kt        background service: notifications, cancel, wake lock
  core/Worker.kt       the job: parts, best/backup models, re-check, double-check, continue
  core/Project.kt      the saved project (parts + lines), History, and the shared ProjectStore
  HistoryActivity.kt   past subtitles and storage clean-up
  core/Cue.kt          subtitle line + SRT read/write
  core/Prefs.kt        settings + language list
logic-tests/           runs the job logic against a fake Gemini server (bash logic-tests/run.sh)
backend/               optional Python server from the first plan; the app does NOT need it
```

Only one library: `dev.ffmpegkit-maintained:ffmpeg-kit-full:8.1.7` (LGPL). No Compose, Media3 or Retrofit,
which keeps the build small and fast.
