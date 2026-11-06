package com.buwrt.dingclock.service

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import com.buwrt.dingclock.data.RunRecord
import com.buwrt.dingclock.data.SettingsStore
import com.buwrt.dingclock.engine.AutomationRunner
import com.buwrt.dingclock.model.RunStatus
import com.buwrt.dingclock.model.TaskType
import com.buwrt.dingclock.schedule.AlarmScheduler
import com.buwrt.dingclock.util.Notifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicBoolean

class ClockService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        Notifications.ensureChannel(this)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val task = intent?.getStringExtra(EXTRA_TASK)
            ?.let { name -> TaskType.entries.firstOrNull { it.name == name } }
            ?: TaskType.CLOCK_IN
        val test = intent?.getBooleanExtra(EXTRA_TEST, false) ?: false
        val store = SettingsStore(this)

        startForeground(Notifications.PROGRESS_ID, Notifications.progress(this, task, "正在准备…"))

        if (!running.compareAndSet(false, true)) {
            store.appendLog("已有任务正在执行，忽略本次 ${task.title}")
            finish()
            return START_NOT_STICKY
        }

        if (!test && store.hasSuccessfulRunToday(task, LocalDate.now())) {
            store.appendLog("今天已完成${task.title}，跳过本次")
            finish()
            return START_NOT_STICKY
        }

        scope.launch {
            try {
                val result = AutomationRunner.run(this@ClockService, task, test) { line ->
                    store.appendLog(line)
                    val manager = getSystemService(NotificationManager::class.java)
                    runCatching {
                        manager?.notify(
                            Notifications.PROGRESS_ID,
                            Notifications.progress(this@ClockService, task, line)
                        )
                    }
                }

                if (!test) {
                    store.saveLastRun(
                        task,
                        RunRecord(
                            date = LocalDate.now().toString(),
                            time = System.currentTimeMillis(),
                            status = result.status,
                            message = result.message
                        )
                    )
                }
                store.appendLog("结果：${result.status.label} - ${result.message}")
                Notifications.showResult(this@ClockService, task, result.status, result.message)
                AlarmScheduler.reschedule(this@ClockService, store)
            } catch (t: Throwable) {
                Log.w(TAG, "run failed", t)
                store.appendLog("执行异常：${t.message}")
                Notifications.showResult(
                    this@ClockService,
                    task,
                    RunStatus.FAILED,
                    t.message ?: "未知异常"
                )
            } finally {
                finish()
            }
        }
        return START_NOT_STICKY
    }

    private fun finish() {
        running.set(false)
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        running.set(false)
        super.onDestroy()
    }

    companion object {
        private const val TAG = "ClockService"
        const val EXTRA_TASK = "extra_task"
        const val EXTRA_TEST = "extra_test"

        val running = AtomicBoolean(false)

        fun intent(context: Context, task: TaskType, test: Boolean): Intent =
            Intent(context, ClockService::class.java).apply {
                putExtra(EXTRA_TASK, task.name)
                putExtra(EXTRA_TEST, test)
            }

        fun start(context: Context, task: TaskType, test: Boolean) {
            ContextCompat.startForegroundService(context, intent(context, task, test))
        }
    }
}
