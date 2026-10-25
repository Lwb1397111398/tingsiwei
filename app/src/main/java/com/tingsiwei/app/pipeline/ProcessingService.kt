package com.tingsiwei.app.pipeline

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.tingsiwei.app.MainActivity
import com.tingsiwei.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 笔记处理前台服务：只负责保活与常驻通知，转写/生成/改写的实际工作都在 NoteProcessor。
 * 有任务时挂 dataSync 前台通知，锁屏/退后台系统不回收进程；NoteProcessor.stages 清空即自杀。
 * 通知内容随 stages 流实时更新（多任务并行时显示第一条文案）。
 */
class ProcessingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observing = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "正在处理笔记", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
                description = "转写或生成导图时在通知栏显示，锁屏也会继续"
            }
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification(emptyMap())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, notification)
        }
        // 幂等：服务存活期间多次 start 只挂一个观察者；服务重建后是新实例，重新挂
        if (!observing) {
            observing = true
            scope.launch {
                NoteProcessor.stages.collect { stages ->
                    // 停止条件必须是「无进度且无活跃任务」：进度流的第一帧可能先于任务的
                    // 首个阶段文案到达，单看空表会在任务刚起步时误停（竞态已实测踩过）
                    if (stages.isEmpty() && !NoteProcessor.anyBusy()) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    } else {
                        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                        nm.notify(NOTIF_ID, buildNotification(stages))
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun buildNotification(stages: Map<Long, String>): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("正在处理笔记")
            .setContentText(stages.values.firstOrNull() ?: "转写或生成进行中…")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openIntent)
            .build()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "processing_channel"
        private const val NOTIF_ID = 1002

        /** 有任务开跑时拉起（幂等：服务已在跑时只是再走一次 onStartCommand，刷新通知） */
        fun ensureRunning(context: Context) {
            context.startForegroundService(Intent(context, ProcessingService::class.java))
        }
    }
}
