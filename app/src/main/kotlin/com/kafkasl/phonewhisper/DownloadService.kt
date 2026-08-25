package com.kafkasl.phonewhisper

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * Process-wide registry of in-flight downloads. The UI observes states here; the
 * actual work runs in [DownloadService] so it survives screen-off / backgrounding.
 */
object DownloadCenter {
    private val states = ConcurrentHashMap<String, DownloadState>()
    private val listeners = ConcurrentHashMap<String, (DownloadState) -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    /** Start a foreground download (no-op if one is already running for this model). */
    fun start(ctx: Context, model: Model) {
        if (isActive(model.archive)) return
        states[model.archive] = DownloadState.Downloading(0f)
        ContextCompat.startForegroundService(ctx.applicationContext, DownloadService.intent(ctx, model))
    }

    /** Register a listener and immediately deliver the current state, if any. */
    fun observe(archive: String, cb: (DownloadState) -> Unit) {
        listeners[archive] = cb
        states[archive]?.let { s -> main.post { cb(s) } }
    }

    fun isActive(archive: String): Boolean =
        states[archive].let { it is DownloadState.Downloading || it is DownloadState.Extracting }

    fun active(): Set<String> = states.keys.filter { isActive(it) }.toSet()

    /** Reported by the service (any thread). Terminal states clear the entry. */
    fun report(archive: String, state: DownloadState) {
        if (state is DownloadState.Done || state is DownloadState.Error) states.remove(archive)
        else states[archive] = state
        main.post { listeners[archive]?.invoke(state) }
    }
}

/**
 * Foreground service that downloads a model. Being a foreground service (with a
 * partial wake lock) keeps the download running when the screen sleeps or the app
 * is backgrounded, which a plain background thread does not.
 */
class DownloadService : Service() {

    private val active = Collections.synchronizedSet(HashSet<String>())
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastTitle = "Downloading model"

    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(CHANNEL, "Model downloads", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val model = modelFrom(intent)
        if (model == null) { stopIfIdle(); return START_NOT_STICKY }
        lastTitle = model.name

        ServiceCompat.startForeground(
            this, NOTIF_ID, notif(model.name, "Starting…", 0, true),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )

        if (!active.add(model.archive)) return START_NOT_STICKY // already downloading
        acquireWakeLock()

        ModelDownloader.download(this, model) { state ->
            DownloadCenter.report(model.archive, state)
            when (state) {
                is DownloadState.Downloading ->
                    update(model.name, "Downloading ${(state.progress * 100).toInt()}%",
                        (state.progress * 100).toInt(), false)
                is DownloadState.Extracting -> update(model.name, "Extracting…", 0, true)
                is DownloadState.Done, is DownloadState.Error -> finish(model.archive)
            }
        }
        return START_NOT_STICKY
    }

    private fun finish(archive: String) {
        active.remove(archive)
        stopIfIdle()
    }

    private fun stopIfIdle() {
        if (active.isEmpty()) {
            wakeLock?.let { if (it.isHeld) it.release() }
            wakeLock = null
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "phonewhisper:download").apply {
            setReferenceCounted(false)
            acquire(60 * 60 * 1000L) // safety timeout
        }
    }

    private fun update(title: String, text: String, progress: Int, indeterminate: Boolean) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notif(title, text, progress, indeterminate))
    }

    private fun notif(title: String, text: String, progress: Int, indeterminate: Boolean): Notification =
        NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, progress, indeterminate)
            .build()

    private fun modelFrom(i: Intent?): Model? {
        val archive = i?.getStringExtra("archive") ?: return null
        return Model(
            name = i.getStringExtra("name") ?: archive,
            archive = archive,
            sizeMb = i.getIntExtra("sizeMb", 0),
            quality = i.getStringExtra("quality") ?: "",
            downloadUrl = i.getStringExtra("downloadUrl"),
            hfRepo = i.getStringExtra("hfRepo"),
            arch = ModelArch.fromId(i.getStringExtra("arch")),
        )
    }

    companion object {
        private const val CHANNEL = "downloads"
        private const val NOTIF_ID = 4711

        fun intent(ctx: Context, m: Model): Intent =
            Intent(ctx, DownloadService::class.java).apply {
                putExtra("name", m.name)
                putExtra("archive", m.archive)
                putExtra("sizeMb", m.sizeMb)
                putExtra("quality", m.quality)
                putExtra("downloadUrl", m.downloadUrl)
                putExtra("hfRepo", m.hfRepo)
                putExtra("arch", m.arch.id)
            }
    }
}
