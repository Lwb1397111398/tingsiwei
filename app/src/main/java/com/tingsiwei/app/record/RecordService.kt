package com.tingsiwei.app.record

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

/**
 * 录音前台服务：只负责保活与常驻通知（锁屏/切后台继续录音），录音逻辑都在 RecordSession。
 */
class RecordService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "正在录音", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
                description = "录音时在通知栏显示，锁屏也能继续录音"
            }
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
        if (intent?.action == ACTION_STOP) {
            RecordSession.stopAndSave()
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, RecordService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("正在录音")
            .setContentText("锁屏也会继续录，点「完成」结束并保存")
            .setUsesChronometer(true)
            .setWhen(System.currentTimeMillis())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openIntent)
            .addAction(0, "完成", stopIntent)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "record_channel"
        private const val NOTIF_ID = 1001
        private const val ACTION_STOP = "com.tingsiwei.app.record.STOP"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, RecordService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RecordService::class.java))
        }
    }
}
