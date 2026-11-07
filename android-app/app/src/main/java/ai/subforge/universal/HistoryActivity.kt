package ai.subforge.universal

import ai.subforge.universal.core.Part
import ai.subforge.universal.core.Project
import ai.subforge.universal.core.ProjectStore
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/** Past subtitles (small files, no audio) plus storage use and clean-up buttons. */
class HistoryActivity : Activity() {

    private class Entry(val file: File, val name: String, val date: Long, val lines: Int, val language: String)

    private lateinit var storage: TextView
    private lateinit var clearAudioBtn: Button
    private lateinit var clearHistoryBtn: Button
    private lateinit var empty: TextView
    private val adapter = EntryAdapter()
    private var entries: List<Entry> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "History & storage"
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(8), dp(12), 0) }

        storage = TextView(this).apply { textSize = 14f; setPadding(dp(4), dp(4), dp(4), dp(8)) }
        root.addView(storage)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        clearAudioBtn = button("Clear saved audio") { confirmClearAudio() }
        clearHistoryBtn = button("Clear history") { confirmClearHistory() }
        row.addView(clearAudioBtn); row.addView(clearHistoryBtn)
        root.addView(row)

        root.addView(TextView(this).apply {
            text = "Past subtitles"
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(4), dp(16), 0, dp(4))
        })
        empty = TextView(this).apply {
            text = "Nothing here yet. When you start a new video, the previous subtitles are kept here."
            setPadding(dp(4), dp(8), dp(4), 0)
        }
        root.addView(empty)
        root.addView(ListView(this).apply {
            adapter = this@HistoryActivity.adapter
            setOnItemClickListener { _, _, pos, _ -> entries.getOrNull(pos)?.let { choose(it) } }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        })
        Screen.show(this, "History & storage", root)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        entries = Project.history(this).mapNotNull { f ->
            val p = Project.load(f) ?: return@mapNotNull null
            Entry(f, p.videoName.ifBlank { "Subtitles" }, f.name.removeSuffix(".json").toLongOrNull() ?: f.lastModified(), p.cues.size, p.language.name)
        }
        adapter.notifyDataSetChanged()
        empty.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE

        val audio = Project.sizeOf(Project.partsDir(this))
        val hist = Project.sizeOf(Project.historyDir(this))
        storage.text = "Saved audio: ${mb(audio)}  (needed only until re-checking is finished; deleted automatically when a job is fully done)\n" +
            "History: ${entries.size} item(s), ${mb(hist)}"
        clearAudioBtn.isEnabled = audio > 0 && !ProjectStore.running
        clearHistoryBtn.isEnabled = entries.isNotEmpty()
    }

    private fun choose(e: Entry) {
        AlertDialog.Builder(this)
            .setTitle(e.name)
            .setMessage("${e.lines} lines · ${e.language}\n${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(e.date))}")
            .setPositiveButton("Open") { _, _ -> open(e) }
            .setNeutralButton("Delete") { _, _ -> e.file.delete(); refresh() }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun open(e: Entry) {
        if (ProjectStore.running) return toast("Wait until the current job is finished, or cancel it first.")
        val p = Project.restore(this, e.file) ?: return toast("Could not open these subtitles")
        ProjectStore.replace(this, p)
        startActivity(Intent(this, EditorActivity::class.java))
    }

    private fun confirmClearAudio() {
        val p = ProjectStore.get(this)
        val unfinished = p != null && p.state != Project.STATE_DONE
        AlertDialog.Builder(this)
            .setTitle("Clear saved audio?")
            .setMessage(
                if (unfinished) "The current job is not fully finished. Without the audio, its remaining parts can't be re-checked or continued; your subtitles stay."
                else "Your subtitles stay. Only the temporary audio is deleted."
            )
            .setPositiveButton("Clear") { _, _ ->
                Project.partsDir(this).deleteRecursively()
                cacheDir.listFiles()?.forEach { it.deleteRecursively() }
                ProjectStore.edit(this) { pr ->
                    for (part in pr.parts) if (part.status == Part.DONE) { part.backup = false; part.reviewed = true }
                    if (pr.todoCount() > 0) {
                        pr.state = Project.STATE_STOPPED
                        pr.message = "The saved audio was cleared, so the unfinished parts can't continue. Start the video again to finish them."
                    } else {
                        pr.state = Project.STATE_DONE
                        pr.message = ""
                    }
                }
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmClearHistory() {
        AlertDialog.Builder(this)
            .setTitle("Clear history?")
            .setMessage("Deletes all ${entries.size} past subtitle(s). The current subtitles are not affected.")
            .setPositiveButton("Clear") { _, _ ->
                Project.historyDir(this).listFiles()?.forEach { it.delete() }
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private inner class EntryAdapter : BaseAdapter() {
        override fun getCount() = entries.size
        override fun getItem(position: Int): Any = entries[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val row = (convertView as? LinearLayout) ?: LinearLayout(this@HistoryActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(8), dp(10), dp(8), dp(10))
                addView(TextView(this@HistoryActivity).apply { textSize = 16f })
                addView(TextView(this@HistoryActivity).apply { textSize = 12f })
            }
            val e = entries[position]
            (row.getChildAt(0) as TextView).text = e.name
            (row.getChildAt(1) as TextView).text =
                "${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(e.date))} · ${e.lines} lines · ${e.language}"
            return row
        }
    }

    private fun mb(bytes: Long) = String.format(Locale.US, "%.1f MB", bytes / 1_048_576.0)

    private fun button(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t
        isAllCaps = false
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        setOnClickListener { onClick() }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()
}
