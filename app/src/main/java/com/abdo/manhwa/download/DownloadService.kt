package com.abdo.manhwa.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * خدمة أمامية تُبقي التحميل شغالاً عند تصغير التطبيق أو إطفاء الشاشة،
 * وتعرض إشعاراً بنسبة التقدم الإجمالية مع زر «إيقاف الكل».
 * تبدأ تلقائياً عند أول تحميل وتتوقف بنفسها عند انتهاء الطابور.
 */
class DownloadService : Service() {

    companion object {
        @Volatile
        var running: Boolean = false

        private const val CHANNEL_ID = "abdo_downloads"
        private const val NOTIFICATION_ID = 4101
        private const val RESULT_ID = 4102
        const val ACTION_PAUSE_ALL = "com.abdo.manhwa.action.PAUSE_ALL"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observer: Job? = null
    private var lastNotifyAt = 0L

    override fun onCreate() {
        super.onCreate()
        running = true
        createChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_PAUSE_ALL) {
            DownloadCenter.pauseAll()
            return START_NOT_STICKY
        }

        // يجب استدعاء startForeground فور بدء الخدمة
        val initial = buildProgress(emptyList(), null)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, initial, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, initial)
        }

        if (observer == null) {
            observer = scope.launch {
                combine(DownloadCenter.tasks, DownloadCenter.collecting) { tasks, collecting -> tasks to collecting }
                    .collect { (tasks, collecting) -> render(tasks, collecting) }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        scope.cancel()
        super.onDestroy()
    }

    private fun manager(): NotificationManager =
        getSystemService(NOTIFICATION_SERVICE) as NotificationManager

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(CHANNEL_ID, "تحميل الفصول", NotificationManager.IMPORTANCE_LOW)
            channel.description = "تقدّم تحميل فصول المانهوا"
            channel.setShowBadge(false)
            manager().createNotificationChannel(channel)
        }
    }

    private fun openAppIntent(): PendingIntent {
        val launch = packageManager.getLaunchIntentForPackage(packageName) ?: Intent()
        return PendingIntent.getActivity(this, 0, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun render(tasks: List<DlTask>, collecting: String?) {
        val active = tasks.filter { it.status == DlStatus.QUEUED || it.status == DlStatus.EXTRACTING || it.status == DlStatus.DOWNLOADING }
        if (active.isEmpty() && collecting == null) {
            finishWork(tasks)
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastNotifyAt < 700) return
        lastNotifyAt = now
        manager().notify(NOTIFICATION_ID, buildProgress(tasks, collecting))
    }

    private fun buildProgress(tasks: List<DlTask>, collecting: String?): Notification {
        val active = tasks.filter { it.status == DlStatus.QUEUED || it.status == DlStatus.EXTRACTING || it.status == DlStatus.DOWNLOADING }
        val current = active.firstOrNull { it.status == DlStatus.DOWNLOADING }
            ?: active.firstOrNull { it.status == DlStatus.EXTRACTING }
            ?: active.firstOrNull()

        val total = tasks.size
        val doneCount = tasks.count { it.status == DlStatus.DONE }
        var fraction = 0.0
        for (t in tasks) {
            fraction += when (t.status) {
                DlStatus.DONE -> 1.0
                DlStatus.DOWNLOADING, DlStatus.PAUSED -> t.percent.toDouble()
                else -> 0.0
            }
        }
        val percent = if (total > 0) ((fraction / total) * 100).toInt().coerceIn(0, 100) else 0

        val title: String
        val text: String
        var indeterminate = false
        if (current == null) {
            title = "جارٍ جمع الفصول"
            text = collecting ?: "تجهيز التحميل…"
            indeterminate = true
        } else {
            title = "تحميل ${current.mangaTitle}"
            text = when (current.status) {
                DlStatus.DOWNLOADING -> "${current.chapterTitle} • ${current.done}/${current.total} صورة"
                DlStatus.EXTRACTING -> "${current.chapterTitle} • جارٍ تجهيز الفصل…"
                else -> "${current.chapterTitle} • في الانتظار"
            }
            indeterminate = current.status == DlStatus.EXTRACTING
        }

        val pauseIntent = PendingIntent.getService(
            this, 1,
            Intent(this, DownloadService::class.java).setAction(ACTION_PAUSE_ALL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(text)
            .setSubText(if (total > 0) "$doneCount من $total فصل" else null)
            .setProgress(100, percent, indeterminate)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent())
            .addAction(android.R.drawable.ic_media_pause, "إيقاف الكل", pauseIntent)
            .build()
    }

    /** انتهى الطابور: أزل الإشعار الدائم، وأظهر ملخصاً ثم أوقف الخدمة */
    private fun finishWork(tasks: List<DlTask>) {
        stopForeground(STOP_FOREGROUND_REMOVE)
        val done = tasks.count { it.status == DlStatus.DONE }
        val paused = tasks.count { it.status == DlStatus.PAUSED }
        val failed = tasks.count { it.status == DlStatus.FAILED }
        if (done > 0 || paused > 0 || failed > 0) {
            val parts = mutableListOf<String>()
            if (done > 0) parts.add("اكتمل $done فصل")
            if (paused > 0) parts.add("$paused متوقف")
            if (failed > 0) parts.add("$failed فشل")
            val summary = NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(if (failed == 0 && paused == 0) "اكتمل التحميل" else "انتهى التحميل")
                .setContentText(parts.joinToString(" • "))
                .setAutoCancel(true)
                .setContentIntent(openAppIntent())
                .build()
            manager().notify(RESULT_ID, summary)
        }
        stopSelf()
    }
}
