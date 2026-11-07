package ai.subforge.universal

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Every screen = a coloured header bar + the content. Android 15/16 draw apps edge-to-edge (under the status
 * and navigation bars), so the header grows by the status-bar height and the content keeps clear of the
 * navigation bar and the keyboard. The same layout is used on every Android version (8 to 16).
 */
object Screen {
    const val PRIMARY = 0xFF4F46E5.toInt()

    class Action(val label: String, val onClick: () -> Unit)

    fun show(activity: Activity, title: String, content: View, actions: List<Action> = emptyList()) {
        goEdgeToEdge(activity)
        val dp = activity.resources.displayMetrics.density
        val root = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(PRIMARY)
            setPadding((16 * dp).toInt(), (14 * dp).toInt(), (8 * dp).toInt(), (14 * dp).toInt())
        }
        header.addView(TextView(activity).apply {
            text = title
            textSize = 20f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        for (a in actions) {
            header.addView(TextView(activity).apply {
                text = a.label
                textSize = 15f
                setTextColor(Color.WHITE)
                setPadding((10 * dp).toInt(), (6 * dp).toInt(), (10 * dp).toInt(), (6 * dp).toInt())
                setOnClickListener { a.onClick() }
            })
        }
        root.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        applyInsets(root, header)
        activity.setContentView(root)
    }

    /** Same edge-to-edge drawing on old Android versions as Android 15+ does by itself. */
    @Suppress("DEPRECATION")
    private fun goEdgeToEdge(activity: Activity) {
        val w = activity.window
        if (Build.VERSION.SDK_INT >= 30) {
            w.setDecorFitsSystemWindows(false)
        } else {
            w.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
        if (Build.VERSION.SDK_INT < 35) {
            w.statusBarColor = Color.TRANSPARENT
            w.navigationBarColor = Color.argb(140, 0, 0, 0)
        }
    }

    private fun applyInsets(root: View, header: View) {
        val headerTop = header.paddingTop
        root.setOnApplyWindowInsetsListener { v, insets ->
            val (left, top, right, bottom) = bars(insets)
            header.setPadding(header.paddingLeft, headerTop + top, header.paddingRight, header.paddingBottom)
            v.setPadding(left, 0, right, bottom)
            insets
        }
        root.requestApplyInsets()
    }

    private data class Four(val l: Int, val t: Int, val r: Int, val b: Int)

    @Suppress("DEPRECATION")
    private fun bars(i: WindowInsets): Four = if (Build.VERSION.SDK_INT >= 30) {
        val x = i.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime())
        Four(x.left, x.top, x.right, x.bottom)
    } else {
        Four(i.systemWindowInsetLeft, i.systemWindowInsetTop, i.systemWindowInsetRight, i.systemWindowInsetBottom)
    }
}
