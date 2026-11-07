package ai.subforge.universal

import ai.subforge.universal.core.Cue
import ai.subforge.universal.core.Srt
import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.VideoView

/**
 * Plays the video inside the editor and draws the current subtitle line on top of it, so timing and wording
 * can be checked without exporting. Uses Android's built-in player (no extra libraries).
 *
 * [cues] must return the lines sorted by start time. [onLine] is told which line is showing (-1 = none).
 */
class VideoPanel(
    private val activity: Activity,
    uri: Uri,
    private val cues: () -> List<Cue>,
    private val onLine: (Int) -> Unit,
) {
    val view: LinearLayout
    private val video: VideoView
    private val overlay: TextView
    private val message: TextView
    private val playBtn: TextView
    private val seek: SeekBar
    private val time: TextView
    private val handler = Handler(Looper.getMainLooper())
    private var prepared = false
    private var failed = false
    private var durationMs = 0
    private var userSeeking = false
    private var shownIndex = -2

    private val tick = object : Runnable {
        override fun run() {
            update()
            handler.postDelayed(this, 120)
        }
    }

    init {
        val dm = activity.resources.displayMetrics
        val dp = dm.density
        val height = minOf(dm.widthPixels * 9 / 16, (260 * dp).toInt())

        val stage = FrameLayout(activity).apply { setBackgroundColor(Color.BLACK) }
        video = VideoView(activity)
        stage.addView(video, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER,
        ))
        overlay = TextView(activity).apply {
            setTextColor(Color.WHITE)
            textSize = 17f
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            setShadowLayer(4f, 0f, 1f, Color.BLACK)
            setBackgroundColor(Color.argb(150, 0, 0, 0))
            setPadding((10 * dp).toInt(), (4 * dp).toInt(), (10 * dp).toInt(), (5 * dp).toInt())
            visibility = View.GONE
        }
        stage.addView(overlay, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
        ).apply { bottomMargin = (12 * dp).toInt(); leftMargin = (12 * dp).toInt(); rightMargin = (12 * dp).toInt() })
        message = TextView(activity).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding((24 * dp).toInt(), 0, (24 * dp).toInt(), 0)
            text = "Loading video…"
        }
        stage.addView(message, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER,
        ))
        stage.setOnClickListener { togglePlay() }

        playBtn = TextView(activity).apply {
            text = "▶"
            textSize = 22f
            setTextColor(Screen.PRIMARY)
            setPadding((14 * dp).toInt(), (4 * dp).toInt(), (10 * dp).toInt(), (4 * dp).toInt())
            setOnClickListener { togglePlay() }
        }
        time = TextView(activity).apply {
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setPadding(0, 0, (12 * dp).toInt(), 0)
            text = "0:00:00"
        }
        seek = SeekBar(activity).apply {
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                    if (fromUser) time.text = clock(progress)
                }
                override fun onStartTrackingTouch(bar: SeekBar) { userSeeking = true }
                override fun onStopTrackingTouch(bar: SeekBar) {
                    userSeeking = false
                    if (prepared) video.seekTo(bar.progress)
                }
            })
        }
        val controls = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(playBtn)
            addView(seek, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(time)
        }

        view = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(stage, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height))
            addView(controls, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        video.setOnPreparedListener { _ ->
            prepared = true
            durationMs = video.duration.coerceAtLeast(0)
            seek.max = durationMs
            message.visibility = View.GONE
            video.seekTo(1) // show the first picture instead of a black box
        }
        video.setOnCompletionListener { playBtn.text = "▶" }
        video.setOnErrorListener { _, _, _ ->
            failed = true
            prepared = false
            message.visibility = View.VISIBLE
            message.text = "This video can't be previewed on this phone (its format isn't supported by the built-in " +
                "player). Your subtitles and exports still work."
            true
        }
        try {
            video.setVideoURI(uri)
        } catch (e: Exception) {
            failed = true
            message.text = "The video can't be opened any more. Subtitles still work."
        }
    }

    /** True when the video loaded and can be played. */
    val ready: Boolean get() = prepared && !failed

    /** Jumps to [sec] (a little before, so the line is heard from its start) and optionally plays. */
    fun seekTo(sec: Double, play: Boolean) {
        if (!prepared) return
        video.seekTo(((sec - 0.3).coerceAtLeast(0.0) * 1000).toInt())
        if (play) { video.start(); playBtn.text = "❚❚" }
        update()
    }

    fun start() {
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    fun pause() {
        handler.removeCallbacks(tick)
        if (prepared && video.isPlaying) video.pause()
        playBtn.text = "▶"
    }

    fun release() {
        handler.removeCallbacks(tick)
        try { video.stopPlayback() } catch (e: Exception) { }
    }

    /** Re-draws the overlay now (after an edit), even while paused. */
    fun refreshLine() {
        shownIndex = -2
        update()
    }

    private fun togglePlay() {
        if (!prepared || failed) return
        if (video.isPlaying) {
            video.pause(); playBtn.text = "▶"
        } else {
            video.start(); playBtn.text = "❚❚"
        }
    }

    private fun update() {
        if (!prepared) return
        val pos = video.currentPosition
        if (!userSeeking) {
            seek.progress = pos
            time.text = clock(pos)
        }
        val list = cues()
        val i = indexAt(list, pos / 1000.0)
        if (i != shownIndex) {
            shownIndex = i
            if (i >= 0) {
                overlay.text = list[i].text
                overlay.visibility = View.VISIBLE
            } else {
                overlay.visibility = View.GONE
            }
            onLine(i)
        }
    }

    private fun clock(ms: Int): String = Srt.ts(ms / 1000.0).substringBefore(',').removePrefix("0")

    companion object {
        /** The line showing at [t] seconds: the last line that started at or before [t] and has not ended. */
        fun indexAt(list: List<Cue>, t: Double): Int {
            var lo = 0
            var hi = list.size - 1
            var best = -1
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                if (list[mid].start <= t) { best = mid; lo = mid + 1 } else hi = mid - 1
            }
            return if (best >= 0 && t < list[best].end) best else -1
        }
    }
}
