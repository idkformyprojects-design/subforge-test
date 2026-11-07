package ai.subforge.universal

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** App details that the Play Store asks for: where data goes, privacy policy, support, open-source licences. */
object AppInfo {
    /** Support e-mail shown for reports. Empty = reports go to the project's GitHub issues page instead. */
    const val SUPPORT_EMAIL = ""
    const val ISSUES_URL = "https://github.com/idkformyprojects-design/subforge-test/issues/new"
    const val PRIVACY_URL = "https://github.com/idkformyprojects-design/subforge-test/blob/main/PRIVACY.md"
    const val SOURCE_URL = "https://github.com/idkformyprojects-design/subforge-test"

    @Suppress("DEPRECATION")
    fun version(context: Context): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
    } catch (e: Exception) {
        "?"
    }

    fun open(context: Context, url: String) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            Toast.makeText(context, url, Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Sends a report about AI-made subtitles (Google Play requires a way to report offensive AI output).
     * Nothing is sent automatically: the user's e-mail app or browser opens with the report filled in.
     */
    fun report(context: Context, reason: String, details: String) {
        val subject = "SubForge report: $reason"
        val body = "Reason: $reason\nApp version: ${version(context)}\n\n$details\n\n(Add anything else here.)"
        if (SUPPORT_EMAIL.isNotBlank()) {
            val i = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$SUPPORT_EMAIL"))
                .putExtra(Intent.EXTRA_SUBJECT, subject)
                .putExtra(Intent.EXTRA_TEXT, body)
            try { context.startActivity(i); return } catch (e: Exception) { }
        }
        open(context, ISSUES_URL + "?title=" + Uri.encode(subject) + "&body=" + Uri.encode(body))
    }
}

class AboutActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = dp(16)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }

        col.addView(heading("SubForge ${AppInfo.version(this)}"))
        col.addView(para(
            "Makes subtitles for your videos in any language with Google's Gemini AI, using your own free Google key."
        ))

        col.addView(heading("Your privacy"))
        col.addView(para(
            "• Your video never leaves your phone.\n" +
                "• Only the sound (in 5-minute parts), the optional “About the video” text and, when translating " +
                "or double-checking, the subtitle text are sent to Google Gemini with your own key.\n" +
                "• SubForge has no server, no account, no ads and no tracking. The developer receives nothing.\n" +
                "• Your key, settings and subtitles stay in the app's private storage on this phone.\n" +
                "• Google's free tier may use what is sent to improve its products; don't use it for private or confidential videos."
        ))
        col.addView(button("Privacy policy") { AppInfo.open(this, AppInfo.PRIVACY_URL) })
        col.addView(button("Report a problem") {
            AppInfo.report(this, "Problem or suggestion", "Describe what happened:")
        })

        col.addView(heading("Open-source licences"))
        col.addView(para(
            "SubForge uses FFmpeg through FFmpegKit, under the GNU LGPL version 3. These libraries are included as " +
                "separate shared libraries, so you can replace them with your own build. Their source code is available " +
                "from the links below."
        ))
        for ((name, licence, url) in LIBRARIES) {
            col.addView(TextView(this).apply {
                text = "$name — $licence"
                textSize = 14f
                setPadding(0, dp(6), 0, dp(6))
                setTextColor(Screen.PRIMARY)
                setOnClickListener { AppInfo.open(this@AboutActivity, url) }
            })
        }
        col.addView(button("Read the GNU LGPL v3") { AppInfo.open(this, "https://www.gnu.org/licenses/lgpl-3.0.html") })
        col.addView(button("App source code") { AppInfo.open(this, AppInfo.SOURCE_URL) })

        Screen.show(this, "About SubForge", ScrollView(this).apply { addView(col) })
    }

    private fun heading(t: String) = TextView(this).apply {
        text = t
        textSize = 17f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(14), 0, dp(4))
    }

    private fun para(t: String) = TextView(this).apply { text = t; textSize = 14f; setPadding(0, 0, 0, dp(6)) }

    private fun button(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        /** Name, licence, where to get the source. */
        val LIBRARIES = listOf(
            Triple("FFmpeg 8.1", "LGPL v3", "https://ffmpeg.org/download.html"),
            Triple("FFmpegKit (maintained fork)", "LGPL v3", "https://github.com/ffmpegkit-maintained/ffmpeg"),
            Triple("smart-exception", "BSD 3-Clause", "https://github.com/tanersener/smart-exception"),
            Triple("LAME (MP3)", "LGPL v2", "https://lame.sourceforge.io"),
            Triple("Opus", "BSD 3-Clause", "https://opus-codec.org"),
            Triple("OpenH264", "BSD 2-Clause", "https://github.com/cisco/openh264"),
            Triple("libass", "ISC", "https://github.com/libass/libass"),
            Triple("FreeType", "FreeType Licence", "https://freetype.org"),
            Triple("FriBidi", "LGPL v2.1", "https://github.com/fribidi/fribidi"),
            Triple("Fontconfig", "MIT-style", "https://www.freedesktop.org/wiki/Software/fontconfig/"),
            Triple("libvpx, libaom, dav1d", "BSD", "https://www.webmproject.org"),
            Triple("Vorbis, Theora, Speex", "BSD", "https://xiph.org"),
            Triple("soxr, twolame, shine", "LGPL", "https://sourceforge.net/projects/soxr/"),
            Triple("opencore-amr, vo-amrwbenc", "Apache 2.0", "https://sourceforge.net/projects/opencore-amr/"),
            Triple("kvazaar", "BSD 3-Clause", "https://github.com/ultravideo/kvazaar"),
            Triple("libxml2", "MIT", "https://gitlab.gnome.org/GNOME/libxml2"),
            Triple("zimg", "WTFPL", "https://github.com/sekrit-twc/zimg"),
            Triple("snappy", "BSD 3-Clause", "https://github.com/google/snappy"),
            Triple("SDL2", "zlib", "https://www.libsdl.org"),
            Triple("zlib", "zlib", "https://zlib.net"),
            Triple("Kotlin standard library", "Apache 2.0", "https://kotlinlang.org"),
        )
    }
}
