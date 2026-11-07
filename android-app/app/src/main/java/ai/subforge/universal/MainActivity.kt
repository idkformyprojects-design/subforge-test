package ai.subforge.universal

import ai.subforge.universal.core.Gemini
import ai.subforge.universal.core.KeyHelper
import ai.subforge.universal.core.Languages
import ai.subforge.universal.core.Media
import ai.subforge.universal.core.Prefs
import ai.subforge.universal.core.Project
import ai.subforge.universal.core.ProjectStore
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import kotlin.concurrent.thread

class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var videoLabel: TextView
    private lateinit var modeGroup: RadioGroup
    private lateinit var langSpinner: Spinner
    private lateinit var startBtn: Button
    private lateinit var cancelBtn: Button
    private lateinit var continueBtn: Button
    private lateinit var openLastBtn: Button
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView
    private lateinit var tryNowBtn: Button
    private lateinit var setupCard: LinearLayout
    private lateinit var aboutField: EditText
    private lateinit var setupStatus: TextView
    private lateinit var batteryNote: TextView

    private var videoUri: Uri? = null
    /** Time Start/Continue was tapped; the screen treats the job as running until the service takes over. */
    private var startingAt = 0L
    private val onChange = Runnable { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        videoUri = savedInstanceState?.getString("video")?.let { Uri.parse(it) }
        buildUi()
        videoUri?.let { videoLabel.text = Media.displayName(this, it) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        videoUri?.let { outState.putString("video", it.toString()) }
    }

    override fun onResume() {
        super.onResume()
        ProjectStore.addListener(onChange)
        refresh()
    }

    /** Coming back from Google's page: look for the copied key (only right after the user started the setup). */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && System.currentTimeMillis() < prefs.awaitingKeyUntil) checkClipboard(auto = true)
    }

    override fun onPause() {
        super.onPause()
        ProjectStore.removeListener(onChange)
    }

    /** Shows the live state of the background job. */
    private fun refresh() {
        if (ProjectStore.running) startingAt = 0L
        val starting = startingAt > 0 && System.currentTimeMillis() - startingAt < 8000
        val running = ProjectStore.running || starting
        val p = if (Project.exists(this)) ProjectStore.get(this) else null
        startBtn.isEnabled = !running
        cancelBtn.visibility = if (running) View.VISIBLE else View.GONE
        progress.visibility = if (running) View.VISIBLE else View.GONE
        if (starting && !ProjectStore.running) {
            progress.isIndeterminate = true
            status.text = "Starting…"
        } else if (running) {
            val f = ProjectStore.fraction
            progress.isIndeterminate = f < 0
            if (f >= 0) progress.progress = (f * 1000).toInt()
            status.text = ProjectStore.statusText + "\n\nYou can lock the phone or use other apps. You'll get a notification when it's done."
        } else if (p != null && p.message.isNotBlank()) {
            status.text = p.message
        }
        openLastBtn.visibility = if (p != null && p.cues.isNotEmpty()) View.VISIBLE else View.GONE
        val canContinue = !running && p != null &&
            (p.state == Project.STATE_STOPPED || p.state == Project.STATE_PARTIAL ||
                p.state == Project.STATE_WORKING || p.state == Project.STATE_IMPROVING)
        continueBtn.visibility = if (canContinue) View.VISIBLE else View.GONE
        setupCard.visibility = if (prefs.apiKey.isBlank()) View.VISIBLE else View.GONE
        tryNowBtn.visibility = if (ProjectStore.running && ProjectStore.waitUntil > 0) View.VISIBLE else View.GONE
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        batteryNote.visibility = if (pm.isIgnoringBatteryOptimizations(packageName)) View.GONE else View.VISIBLE
        if (p != null) {
            continueBtn.text = if (p.todoCount() > 0) "Continue unfinished job (${p.videoName})"
            else "Re-check with the best model (${p.videoName})"
        }
    }

    private fun buildUi() {
        val pad = dp(16)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        setupCard = buildSetupCard()
        col.addView(setupCard)

        col.addView(heading("1. Choose a video"))
        col.addView(button("Choose video") { pickVideo() })
        videoLabel = TextView(this).apply { text = "No video chosen"; setPadding(0, dp(6), 0, dp(12)) }
        col.addView(videoLabel)

        col.addView(heading("2. Make subtitles from"))
        modeGroup = RadioGroup(this).apply {
            addView(RadioButton(this@MainActivity).apply { id = MODE_SPEECH; text = "Speech in the video" })
            addView(RadioButton(this@MainActivity).apply { id = MODE_TRACK; text = "Existing subtitle track in the video (translate it)" })
            check(MODE_SPEECH)
            setPadding(0, 0, 0, dp(12))
        }
        col.addView(modeGroup)

        col.addView(heading("3. Subtitle language"))
        langSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity, android.R.layout.simple_spinner_dropdown_item, Languages.ALL.map { it.name },
            )
            setSelection(prefs.languageIndex.coerceIn(0, Languages.ALL.size - 1))
        }
        col.addView(langSpinner)

        col.addView(heading("4. About the video (optional, improves accuracy)"))
        aboutField = EditText(this).apply {
            hint = "e.g. Bengali family drama \u201cAparajito\u201d. Characters: Apu, Durga, Sarbajaya. Spoken language: Bengali."
            setText(prefs.about)
            minLines = 2
            textSize = 15f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        col.addView(aboutField)
        col.addView(TextView(this).apply {
            textSize = 13f
            text = "Title, character or place names and the language spoken help the AI spell names right and pick the right meaning."
        })

        col.addView(CheckBox(this).apply {
            text = "Double-check with a second listen (most accurate)"
            isChecked = prefs.doubleCheck
            setPadding(0, dp(12), 0, 0)
            setOnCheckedChangeListener { _, on -> prefs.doubleCheck = on }
        })
        col.addView(TextView(this).apply {
            textSize = 13f
            setPadding(dp(32), 0, 0, 0)
            text = "Always on: parts made by a backup model (when the best one is busy) are redone by the best model.\n" +
                "With this ticked: the best model also listens to EVERY part a second time and fixes its own mistakes. " +
                "Uses about twice as many free requests."
        })

        startBtn = button("Start") { start() }
        col.addView(space(16))
        col.addView(startBtn)

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            visibility = View.GONE
        }
        col.addView(progress)
        status = TextView(this).apply { setPadding(0, dp(8), 0, dp(8)) }
        col.addView(status)
        cancelBtn = button("Cancel") { JobService.cancel(this); status.text = "Cancelling…" }.apply { visibility = View.GONE }
        col.addView(cancelBtn)
        tryNowBtn = button("Try now") { JobService.tryNow(this) }.apply { visibility = View.GONE }
        col.addView(tryNowBtn)
        continueBtn = button("Continue") { resumeJob() }.apply { visibility = View.GONE }
        col.addView(continueBtn)

        col.addView(space(24))
        openLastBtn = button("Open subtitles") { openEditor() }
        col.addView(openLastBtn)
        col.addView(button("History & storage") { startActivity(Intent(this, HistoryActivity::class.java)) })
        col.addView(button("Settings (Gemini API key, models)") { showSettings() })
        col.addView(button("About, privacy & licences") { startActivity(Intent(this, AboutActivity::class.java)) })
        batteryNote = TextView(this).apply {
            setPadding(0, dp(16), 0, 0)
            textSize = 14f
            setTextColor(android.graphics.Color.rgb(217, 119, 6))
            text = "⚠ Your phone may pause SubForge in the background, so long jobs can stop or wait forever. " +
                "Tap here and allow it to run in the background (on Oppo/Realme also: App info → Battery → Allow background activity)."
            setOnClickListener { openBatterySettings() }
            visibility = View.GONE
        }
        col.addView(batteryNote)

        col.addView(TextView(this).apply {
            setPadding(0, dp(20), 0, 0)
            textSize = 13f
            text = "Your video stays on your phone. Only the sound (about 1 MB per 5 minutes) is sent to Google Gemini, " +
                "using your own free API key. Work continues in the background; a 2-hour movie takes roughly 5–20 minutes."
        })

        Screen.show(this, "SubForge", ScrollView(this).apply { addView(col) })
    }

    // ---------------------------------------------------------------- actions

    private fun pickVideo() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("video/*", "application/x-matroska", "application/octet-stream"))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(i, REQ_PICK)
    }

    @Deprecated("Uses the platform API on purpose so the app needs no extra libraries")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PICK || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        try {
            // Keep access after restarts, so the background job and the editor can still read this video.
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: SecurityException) {
            // some file pickers do not offer it; access still works while the app is running
        }
        videoUri = uri
        videoLabel.text = Media.displayName(this, uri)
    }

    private fun start() {
        val uri = videoUri ?: return toast("Choose a video first")
        if (prefs.apiKey.isBlank()) {
            toast("First connect to Google (free) using the box at the top")
            return
        }
        val p = if (Project.exists(this)) ProjectStore.get(this) else null
        if (p != null && p.cues.isNotEmpty() && p.state != Project.STATE_DONE) {
            AlertDialog.Builder(this)
                .setMessage("\"${p.videoName}\" is not fully finished. Starting a new video moves its subtitles to History, " +
                    "and its remaining parts can no longer be re-checked. Continue?")
                .setPositiveButton("Start new") { _, _ -> startNow(uri) }
                .setNegativeButton("Keep", null)
                .show()
        } else {
            startNow(uri)
        }
    }

    private fun startNow(uri: Uri) {
        askPermissionsOnce()
        val langIdx = langSpinner.selectedItemPosition
        prefs.languageIndex = langIdx
        val fromSpeech = modeGroup.checkedRadioButtonId == MODE_SPEECH
        val about = aboutField.text.toString().trim()
        prefs.about = about
        startingAt = System.currentTimeMillis()
        JobService.start(this, uri, Media.displayName(this, uri), langIdx, fromSpeech, about)
        refresh()
    }

    private fun resumeJob() {
        if (prefs.apiKey.isBlank()) return showSettings()
        askPermissionsOnce()
        startingAt = System.currentTimeMillis()
        JobService.resume(this)
        refresh()
    }

    /** Notifications (Android 13+) and "run in background without limits" so long jobs are not stopped. */
    /**
     * Opens this app's settings page, where the user can set Battery to "Unrestricted" / allow background
     * activity. (Asking Android directly needs a permission Google Play only allows for a few kinds of apps.)
     */
    private fun openBatterySettings() {
        toast("Tap \u201cBattery\u201d (or \u201cApp battery usage\u201d) and choose \u201cUnrestricted\u201d / allow background activity")
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (e2: Exception) {
                toast("Open Settings \u2192 Apps \u2192 SubForge \u2192 Battery and allow background activity")
            }
        }
    }

    /** Notifications (Android 13+). Background activity is suggested by the orange note, not asked automatically. */
    private fun askPermissionsOnce() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFY)
        }
    }

    private fun openEditor() {
        if (!Project.exists(this)) return toast("No subtitles yet")
        startActivity(Intent(this, EditorActivity::class.java))
    }

    // ---------------------------------------------------------------- easy key setup

    private fun buildSetupCard(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(12), dp(14), dp(12))
        setBackgroundColor(android.graphics.Color.argb(28, 99, 102, 241))
        addView(TextView(this@MainActivity).apply {
            text = "Connect to Google (free, about 1 minute)"
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
        })
        addView(TextView(this@MainActivity).apply {
            setPadding(0, dp(6), 0, dp(6))
            text = "SubForge uses Google's free Gemini AI with your own free key. No typing needed:\n\n" +
                "1.  Tap the button below. Google's page opens (sign in with your Google account if asked).\n" +
                "2.  Tap \u201cCreate API key\u201d (accept Google's terms if asked), then tap \u201cCopy\u201d.\n" +
                "3.  Come back to SubForge. It finds the key by itself."
        })
        addView(button("Open Google's key page") { openKeyPage() })
        addView(button("I've copied my key \u2014 use it") { checkClipboard(auto = false) })
        setupStatus = TextView(this@MainActivity).apply { setPadding(0, dp(6), 0, 0) }
        addView(setupStatus)
    }

    private fun openKeyPage() {
        prefs.awaitingKeyUntil = System.currentTimeMillis() + 15 * 60_000L
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(KeyHelper.SETUP_URL)))
        } catch (e: Exception) {
            toast("Open this in your browser: ${KeyHelper.SETUP_URL}")
        }
    }

    private fun clipboardText(): CharSequence? = try {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)
    } catch (e: Exception) {
        null
    }

    /** Finds a copied key. [auto] = called by itself on return from Google's page (stays quiet if nothing found). */
    private fun checkClipboard(auto: Boolean) {
        val key = KeyHelper.find(clipboardText())
        if (key == null) {
            if (!auto) {
                AlertDialog.Builder(this)
                    .setTitle("No key copied yet")
                    .setMessage("On Google's page, tap \u201cCreate API key\u201d, then the \u201cCopy\u201d button next to the new key, and come back here.")
                    .setPositiveButton("Open Google's page") { _, _ -> openKeyPage() }
                    .setNegativeButton("Close", null)
                    .show()
            }
            return
        }
        prefs.awaitingKeyUntil = 0L
        if (key == prefs.apiKey) return
        AlertDialog.Builder(this)
            .setTitle("Found your key")
            .setMessage("${KeyHelper.mask(key)}\n\nUse this key for SubForge?")
            .setPositiveButton("Use it") { _, _ -> useKey(key) }
            .setNegativeButton("No", null)
            .show()
    }

    private fun useKey(key: String) {
        prefs.apiKey = key
        // The key is private; do not leave it lying in the clipboard for other apps.
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            if (Build.VERSION.SDK_INT >= 28) cm.clearPrimaryClip()
        } catch (e: Exception) { }
        refresh()
        status.text = "Checking your key with Google…"
        val g = Gemini(key)
        val model = prefs.model
        thread {
            val err = g.test(model)
            runOnUiThread {
                status.text = when {
                    err == null -> "\u2713 Connected to Google. You're ready: choose a video and tap Start."
                    err.contains("busy", ignoreCase = true) ->
                        "\u2713 Key saved. (Google is busy right now, which is normal; it will work when you start.)"
                    else -> "Your key was saved, but Google said: $err\nOpen Settings to check it."
                }
            }
        }
    }

    private fun showSettings() {
        val pad = dp(20)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, dp(8), pad, 0) }
        box.addView(TextView(this).apply {
            text = "Get a free key at aistudio.google.com/apikey (sign in with Google → Create API key), then paste it here. " +
                "One key works for every model."
        })
        val keyField = EditText(this).apply {
            hint = "Gemini API key"
            setText(prefs.apiKey)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            isSingleLine = true
        }
        box.addView(keyField)
        val keyRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        keyRow.addView(button("Paste") {
            val k = KeyHelper.find(clipboardText())
            if (k != null) keyField.setText(k) else toast("No key in the clipboard. Copy it on Google's page first.")
        }.apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) })
        keyRow.addView(button("Get a free key") { openKeyPage() }.apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        box.addView(keyRow)
        box.addView(TextView(this).apply { text = "Best model (used first)"; setPadding(0, dp(12), 0, 0) })
        val modelField = EditText(this).apply { setText(prefs.model); isSingleLine = true }
        box.addView(modelField)
        box.addView(TextView(this).apply { text = "Backup models when it is busy (comma separated)"; setPadding(0, dp(12), 0, 0) })
        val backupField = EditText(this).apply { setText(prefs.backupModels); isSingleLine = true }
        box.addView(backupField)
        val cleanBox = CheckBox(this).apply {
            text = "Clean up the sound first (filters rumble and hiss, lifts quiet speech). Recommended."
            isChecked = prefs.cleanSound
        }
        box.addView(cleanBox)
        box.addView(button("Reset models to default") {
            modelField.setText(Prefs.DEFAULT_MODEL)
            backupField.setText(Prefs.DEFAULT_BACKUPS)
        })

        fun save() {
            prefs.apiKey = keyField.text.toString()
            prefs.model = modelField.text.toString()
            prefs.backupModels = backupField.text.toString()
            prefs.cleanSound = cleanBox.isChecked
        }

        AlertDialog.Builder(this)
            .setTitle("Settings")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Save") { _, _ -> save(); toast("Saved") }
            .setNeutralButton("Save & test") { _, _ ->
                save()
                status.text = "Testing your key with ${prefs.model}…"
                val g = Gemini(prefs.apiKey)
                val model = prefs.model
                thread {
                    val err = g.test(model)
                    runOnUiThread {
                        status.text = if (err == null) "Key works with $model ✓"
                        else "Test failed: $err\n(If it says busy, the key is fine; Google is just overloaded right now.)"
                    }
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    // ---------------------------------------------------------------- small helpers

    private fun heading(t: String) = TextView(this).apply {
        text = t
        textSize = 17f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(8), 0, dp(4))
    }

    private fun button(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun space(h: Int) = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(h))
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()

    companion object {
        private const val REQ_PICK = 10
        private const val REQ_NOTIFY = 11
        private const val MODE_SPEECH = 101
        private const val MODE_TRACK = 102
    }
}
