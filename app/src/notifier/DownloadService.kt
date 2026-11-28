package moe.yumeyuka.yumehub.notifier

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.text.format.Formatter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import moe.yumeyuka.yumehub.App
import moe.yumeyuka.yumehub.R
import moe.yumeyuka.yumehub.data.download.DownloadStatus
import moe.yumeyuka.yumehub.data.download.DownloadStore
import moe.yumeyuka.yumehub.data.download.DownloadTask
import moe.yumeyuka.yumehub.data.storage.mmkv.TokenStore
import moe.yumeyuka.yumehub.locale.CurrentAppStrings
import org.koin.android.ext.android.inject
import org.koin.core.component.get

class DownloadService : Service() {
    private val store: DownloadStore by inject()
    private val tokens: TokenStore by inject()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val manager
        get() = getSystemService(NotificationManager::class.java)

    private var stoppedNormally = false
    private val workers = mutableSetOf<String>()
    private val posted = mutableMapOf<String, DownloadTask>()
    private val ids = mutableMapOf<String, Int>()
    private var nextId = 3000
    private var foreground: String? = null
    private var latestStartId = 0
    private var observing = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL,
                CurrentAppStrings.current.Downloads.Title,
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    private fun observeDownloads() {
        observing = true
        // 进程重建后无法继续上次的传输，只清理失效的进度通知，不动完成通知。
        val live = store.tasks.value.filter { it.active }.map { it.id }.toSet()
        manager.activeNotifications.forEach {
            val taskId = it.notification.extras.getString(TASK) ?: return@forEach
            if (taskId !in live) manager.cancel(it.id)
        }
        scope.launch { tokens.sessionGeneration.collect { store.cancelStaleSessions() } }
        scope.launch { store.tasks.collect { if (latestStartId != 0) render() } }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!(application as App).storageReady) {
            // A new process has no queued transfers, but a stale foreground start still needs ack.
            if (intent?.action == START || intent?.action == RESUME) {
                startForeground(ID, notification(null))
                stopForeground(STOP_FOREGROUND_REMOVE)
            }
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        if (!observing) observeDownloads()
        latestStartId = startId
        stoppedNormally = false
        val id = intent?.data?.lastPathSegment
        if (id != null)
            when (intent.action) {
                PAUSE -> store.setPaused(id, true)
                RESUME -> store.setPaused(id, false)
                START ->
                    if (workers.add(id))
                        scope.launch {
                            try {
                                store.download(id)?.let { completed ->
                                    render()
                                    notifyCompleted(completed)
                                }
                            } finally {
                                workers.remove(id)
                                render()
                                stopIfIdle()
                            }
                        }
            }
        render()
        if (foreground == null && intent?.action in listOf(START, RESUME)) {
            // 一个已失效的前台启动请求仍须及时回应系统。
            startForeground(ID, notification(null))
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
        stopIfIdle()
        return START_NOT_STICKY
    }

    private fun stopIfIdle() {
        if (workers.isEmpty() && store.tasks.value.none { it.active }) {
            stoppedNormally = stopSelfResult(latestStartId)
        }
    }

    private fun render() {
        val live =
            store.tasks.value.filter {
                it.active && it.account.equals(tokens.accountLogin.value, true)
            }
        val running = live.filter { it.status != DownloadStatus.PAUSED }
        val owner = running.find { it.id == foreground } ?: running.firstOrNull()
        val changedOwner = foreground != owner?.id
        if (changedOwner && foreground != null) {
            val keepPaused = live.any { it.id == foreground && it.status == DownloadStatus.PAUSED }
            stopForeground(if (keepPaused) STOP_FOREGROUND_DETACH else STOP_FOREGROUND_REMOVE)
        }
        foreground = owner?.id
        live.forEach { task ->
            val number = ids.getOrPut(task.id) { nextId++ }
            if (task.id == foreground && changedOwner) startForeground(number, notification(task))
            else if (posted[task.id] != task) manager.notify(number, notification(task))
            posted[task.id] = task
        }
        for (id in posted.keys - live.map { it.id }.toSet()) {
            ids.remove(id)?.let { manager.cancel(it) }
            posted.remove(id)
        }
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        if (observing) store.interruptActive()
        stopSelf()
    }

    override fun onDestroy() {
        if (observing && !stoppedNormally) store.interruptActive()
        scope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        ids.values.forEach { manager.cancel(it) }
        super.onDestroy()
    }

    private fun notification(task: DownloadTask?): Notification {
        val strings = CurrentAppStrings.current.Downloads
        val action =
            when (task?.status) {
                DownloadStatus.DOWNLOADING -> PAUSE
                DownloadStatus.PAUSED -> RESUME
                else -> null
            }
        val target = task?.let { taskUri(it.id) }
        val control = Intent(this, DownloadService::class.java).setAction(action).setData(target)
        val pending =
            when (action) {
                PAUSE -> PendingIntent.getService(this, 0, control, PendingIntent.FLAG_IMMUTABLE)
                RESUME ->
                    PendingIntent.getForegroundService(
                        this,
                        0,
                        control,
                        PendingIntent.FLAG_IMMUTABLE,
                    )
                else -> null
            }
        val total = task?.total
        val percent =
            if (total != null && total > 0) {
                (100.0 * task.bytes / total).toInt().coerceIn(0, 100)
            } else null
        val title = task?.name ?: strings.Title
        val details = task?.let {
            val size = Formatter.formatShortFileSize(this, it.bytes)
            val progress =
                if (total != null && percent != null)
                    strings.Progress(size, Formatter.formatShortFileSize(this, total), percent)
                else size
            when (it.status) {
                DownloadStatus.DOWNLOADING ->
                    strings.TransferRate(progress, Formatter.formatShortFileSize(this, it.speed))
                DownloadStatus.PAUSED -> progress
                DownloadStatus.VERIFYING -> strings.Verifying
                else -> strings.Queued
            }
        }
        val hint =
            when (action) {
                PAUSE -> strings.Downloading
                RESUME -> strings.Paused
                else -> null
            }
        val indeterminate = percent == null && action != RESUME
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.github_mark)
            .setContentTitle(title)
            .setContentText(details)
            .setSubText(hint)
            .setProgress(100, percent ?: 0, indeterminate)
            .setOnlyAlertOnce(true)
            .setOngoing(action != RESUME)
            .addExtras(Bundle().apply { putString(TASK, task?.id) })
            .setDeleteIntent(
                target?.let {
                    PendingIntent.getBroadcast(
                        this,
                        0,
                        Intent(this, DownloadDismissReceiver::class.java).setData(it),
                        PendingIntent.FLAG_IMMUTABLE,
                    )
                },
            )
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(pending)
            .build()
    }

    private fun notifyCompleted(task: DownloadTask) {
        if (!task.account.equals(tokens.accountLogin.value, ignoreCase = true)) return
        val uri = task.uri?.let(Uri::parse) ?: return
        val open =
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, task.mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        val pending = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE)
        val notification =
            Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.github_mark)
                .setContentTitle(task.name)
                .setContentText(CurrentAppStrings.current.Downloads.CompletedOpen)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setContentIntent(pending)
                .setAutoCancel(true)
                .build()
        manager.notify(task.id, COMPLETED_ID, notification)
    }

    companion object {
        private const val CHANNEL = "file_downloads"
        private const val ID = 2002
        private const val COMPLETED_ID = 2003
        private const val PAUSE = "moe.yumeyuka.yumehub.download.PAUSE"
        private const val RESUME = "moe.yumeyuka.yumehub.download.RESUME"
        private const val START = "moe.yumeyuka.yumehub.download.START"
        private const val TASK = "moe.yumeyuka.yumehub.download.TASK"

        private fun taskUri(id: String): Uri =
            Uri.Builder().scheme("yumehub-download").authority("task").appendPath(id).build()

        fun start(context: Context, id: String) {
            context.startForegroundService(
                Intent(context, DownloadService::class.java).setAction(START).setData(taskUri(id)),
            )
        }
    }
}

class DownloadDismissReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.data?.lastPathSegment ?: return
        val app = context.applicationContext as App
        if (app.storageReady) app.get<DownloadStore>().dismiss(id)
    }
}
