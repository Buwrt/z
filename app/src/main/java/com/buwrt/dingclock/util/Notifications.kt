package com.buwrt.dingclock.util

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.buwrt.dingclock.R
import com.buwrt.dingclock.model.RunStatus
import com.buwrt.dingclock.model.TaskType
import com.buwrt.dingclock.ui.MainActivity

object Notifications {
    const val CHANNEL_STATUS = "dingclock_status"
    const val CHANNEL_RESULT = "dingclock_result"
    const val PROGRESS_ID = 1001

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        val status = NotificationChannel(
            CHANNEL_STATUS,
            "自动打卡任务",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "显示正在执行的自动打卡任务"
            setShowBadge(false)
        }
        val result = NotificationChannel(
            CHANNEL_RESULT,
            "打卡结果",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "打卡成功或失败的通知"
        }
        manager.createNotificationChannel(status)
        manager.createNotificationChannel(result)
    }

    fun progress(context: Context, task: TaskType, text: String): Notification =
        NotificationCompat.Builder(context, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_stat_clock)
            .setContentTitle(task.title)
            .setContentText(text.take(80))
            .setStyle(NotificationCompat.BigTextStyle().bigText(text.take(400)))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(openApp(context))
            .build()

    fun showResult(context: Context, task: TaskType, status: RunStatus, message: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_RESULT)
            .setSmallIcon(R.drawable.ic_stat_clock)
            .setContentTitle("${task.title}：${status.label}")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setAutoCancel(true)
            .setContentIntent(openApp(context))
            .build()
        runCatching {
            NotificationManagerCompat.from(context)
                .notify(PROGRESS_ID + 1 + task.ordinal, notification)
        }
    }

    private fun openApp(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
