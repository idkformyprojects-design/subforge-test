package ai.subforge.universal

import ai.subforge.universal.core.GeminiCancelledException
import ai.subforge.universal.core.Prefs
import ai.subforge.universal.core.Project
import ai.subforge.universal.core.ProjectStore
import ai.subforge.universal.core.Worker
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import kotlin.concurrent.thread

/**
 * Runs the subtitle job in the background with a notification, so the phone can be locked
 * or other apps used. Progress is saved after every part, so an interrupted job can be continued.
 */
class JobService : Service() {

    private var worker: Worker? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastNotify = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannels(this)
        if (intent?.action == ACTION_CANCEL) {
            worker?.cancel()
            ProjectStore.status("Stopping…", ProjectStore.fraction)
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_TRY_NOW) {
            Worker.tryNow()
            return START_NOT_STICKY
        }
        // Must show the notification within a few seconds of being started.
        goForeground("Starting…", -1.0)
        if (ProjectStore.running) return START_NOT_STICKY

        val newJob = intent?.action == ACTION_START
        val video = intent?.getStringExtra(EXTRA_VIDEO)?.let { Uri.parse(it) }
        val name = intent?.getStringExtra(EXTRA_NAME) ?: ""
        val langIdx = intent?.getIntExtra(EXTRA_LANG, 1) ?: 1
        val fromSpeech = intent?.getBooleanExtra(EXTRA_SPEECH, true) ?: true

        val prefs = Prefs(this)
        val w = Worker(applicationContext, prefs.model, prefs.backupList(), prefs.apiKey, events)
        worker = w
        ProjectStore.running = true
        acquireWakeLock()

        thread(name = "subforge-job") {
            try {
                if (newJob && video != null) {
                    val p = w.prepare(video, name, langIdx, fromSpeech, prefs.doubleCheck)
                    // The previous subtitles go into History (prepare already replaced the saved audio).
                    Project.archiveCurrent(applicationContext, deleteAudio = false)
                    ProjectStore.replace(applicationContext, p)
                }
                val finalState = w.run()
                val p = ProjectStore.get(applicationContext)
                ProjectStore.status(p?.message ?: "Done", 1.0)
                notifyFinished(
                    if (finalState == Project.STATE_DONE) "All done ✓" else "Subtitles saved — some parts need another try",
                    p?.message ?: "",
                )
            } catch (e: GeminiCancelledException) {
                stopped("Stopped. Tap Continue to carry on where it left off.")
            } catch (e: InterruptedException) {
                stopped("Stopped. Tap Continue to carry on where it left off.")
            } catch (e: Exception) {
                val msg = "Problem: ${e.message ?: e.toString()}"
                stopped(msg)
                notifyFinished("SubForge stopped", msg)
            } finally {
                cancelWakeAlarm()
                ProjectStore.waitUntil = 0L
                ProjectStore.running = false
                worker = null
                releaseWakeLock()
                ProjectStore.status(ProjectStore.statusText, ProjectStore.fraction)
                stopForegroundCompat()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun stopped(msg: String) {
        ProjectStore.edit(applicationContext) { p ->
            if (p.state == Project.STATE_WORKING || p.state == Project.STATE_IMPROVING) p.state = Project.STATE_STOPPED
            p.message = msg
        }
        ProjectStore.status(msg, ProjectStore.fraction)
    }

    private val events = object : Worker.Events {
        override fun progress(text: String, fraction: Double) {
            ProjectStore.status(text, fraction)
            // Updating the notification too often is wasteful; twice a second is plenty.
            val now = System.currentTimeMillis()
            if (now - lastNotify > 500) {
                lastNotify = now
                goForeground(text, fraction)
            }
        }

        override fun waiting(untilWallMs: Long) {
            ProjectStore.waitUntil = untilWallMs
            if (untilWallMs > 0) setWakeAlarm(untilWallMs - System.currentTimeMillis()) else cancelWakeAlarm()
            goForeground(ProjectStore.statusText, ProjectStore.fraction)
            ProjectStore.status(ProjectStore.statusText, ProjectStore.fraction)
        }

        override fun firstPassDone(lines: Int, stillImproving: Boolean) {
            if (stillImproving) {
                notifyFinished(
                    "Subtitles ready ✓ ($lines lines)",
                    "You can start editing. Some parts are being re-checked by the best model in the background.",
                )
            }
        }
    }

    // ------------------------------------------------------------------ notifications

    private fun goForeground(text: String, fraction: Double) {
        val cancel = PendingIntent.getService(
            this, 1, Intent(this, JobService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val b = Notification.Builder(this, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_subtitle)
            .setContentTitle("SubForge is working")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp(MainActivity::class.java, 2))
            .addAction(Notification.Action.Builder(null, "Cancel", cancel).build())
        if (ProjectStore.waitUntil > 0) {
            val tryNow = PendingIntent.getService(
                this, 4, Intent(this, JobService::class.java).setAction(ACTION_TRY_NOW),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            b.addAction(Notification.Action.Builder(null, "Try now", tryNow).build())
        }
        if (fraction >= 0) b.setProgress(1000, (fraction.coerceIn(0.0, 1.0) * 1000).toInt(), false)
        else b.setProgress(0, 0, true)
        val n = b.build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFY_PROGRESS, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFY_PROGRESS, n)
        }
    }

    private fun notifyFinished(title: String, text: String) {
        val n = Notification.Builder(this, CHANNEL_DONE)
            .setSmallIcon(R.drawable.ic_subtitle)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(openApp(EditorActivity::class.java, 3))
            .build()
        try {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFY_DONE, n)
        } catch (e: SecurityException) {
            // notification permission not granted: the app still shows the result when opened
        }
    }

    private fun openApp(cls: Class<*>, code: Int): PendingIntent = PendingIntent.getActivity(
        this, code, Intent(this, cls).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun stopForegroundCompat() {
        stopForeground(Service.STOP_FOREGROUND_REMOVE)
    }

    // ------------------------------------------------------------------ wake-up alarm for waits

    /**
     * Some phones (e.g. Oppo/Realme/Xiaomi) pause background apps even with a notification, so a plain
     * timer might never finish. A system alarm wakes the phone and the app at the retry time.
     */
    private fun setWakeAlarm(inMs: Long) {
        val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val at = SystemClock.elapsedRealtime() + inMs.coerceAtLeast(1000L)
        val pi = wakeIntent(this)
        try {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
                am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
            }
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
        }
    }

    private fun cancelWakeAlarm() {
        (getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(wakeIntent(this))
    }

    /** Receives the wake-up alarm and ends the wait. */
    class WakeReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            Worker.tryNow()
        }
    }

    // ------------------------------------------------------------------ keep the CPU awake while working

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SubForge:job").apply {
            setReferenceCounted(false)
            acquire(4 * 60 * 60 * 1000L) // safety limit: 4 hours
        }
    }

    private fun releaseWakeLock() {
        try { wakeLock?.release() } catch (e: Exception) { }
        wakeLock = null
    }

    companion object {
        const val ACTION_START = "ai.subforge.START"
        const val ACTION_CONTINUE = "ai.subforge.CONTINUE"
        const val ACTION_CANCEL = "ai.subforge.CANCEL"
        const val ACTION_TRY_NOW = "ai.subforge.TRY_NOW"
        const val EXTRA_VIDEO = "video"
        const val EXTRA_NAME = "name"
        const val EXTRA_LANG = "lang"
        const val EXTRA_SPEECH = "speech"

        private const val CHANNEL_PROGRESS = "progress"
        private const val CHANNEL_DONE = "done"
        private const val NOTIFY_PROGRESS = 1
        private const val NOTIFY_DONE = 2

        fun createChannels(context: Context) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_PROGRESS, "Work in progress", NotificationManager.IMPORTANCE_LOW)
            )
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_DONE, "Finished", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }

        fun start(context: Context, video: Uri, name: String, langIdx: Int, fromSpeech: Boolean) {
            val i = Intent(context, JobService::class.java).setAction(ACTION_START)
                .putExtra(EXTRA_VIDEO, video.toString())
                .putExtra(EXTRA_NAME, name)
                .putExtra(EXTRA_LANG, langIdx)
                .putExtra(EXTRA_SPEECH, fromSpeech)
            context.startForegroundService(i)
        }

        /** Continues an unfinished job, or re-checks parts that still need the best model. */
        fun resume(context: Context) {
            context.startForegroundService(Intent(context, JobService::class.java).setAction(ACTION_CONTINUE))
        }

        private fun wakeIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
            context, 5, Intent(context, WakeReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        /** Ends a wait for the best model right away. */
        fun tryNow(context: Context) {
            context.startService(Intent(context, JobService::class.java).setAction(ACTION_TRY_NOW))
        }

        fun cancel(context: Context) {
            context.startService(Intent(context, JobService::class.java).setAction(ACTION_CANCEL))
        }
    }
}
