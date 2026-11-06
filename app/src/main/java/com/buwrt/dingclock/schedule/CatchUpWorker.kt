package com.buwrt.dingclock.schedule

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.buwrt.dingclock.data.RunRecord
import com.buwrt.dingclock.data.SettingsStore
import com.buwrt.dingclock.engine.AutomationRunner
import com.buwrt.dingclock.service.ClockService
import com.buwrt.dingclock.util.Notifications
import java.time.LocalDate
import java.time.ZonedDateTime

/**
 * 兜底补跑。WorkManager 每 30 分钟检查一次，只在该打卡点的时间窗口内且今天还没成功时执行。
 */
class CatchUpWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val store = SettingsStore(applicationContext)
        val settings = store.load()
        if (!settings.enabled) return Result.success()

        val pending = CatchUpPlanner.pendingTask(settings, store, ZonedDateTime.now())
            ?: return Result.success()
        if (!ClockService.running.compareAndSet(false, true)) return Result.success()

        return try {
            store.appendLog("补跑触发：${pending.title}")
            val result = AutomationRunner.run(applicationContext, pending, test = false) { line ->
                store.appendLog(line)
            }
            store.saveLastRun(
                pending,
                RunRecord(
                    date = LocalDate.now().toString(),
                    time = System.currentTimeMillis(),
                    status = result.status,
                    message = result.message
                )
            )
            store.appendLog("补跑结果：${result.status.label} - ${result.message}")
            Notifications.showResult(applicationContext, pending, result.status, result.message)
            Result.success()
        } catch (t: Throwable) {
            store.appendLog("补跑异常：${t.message}")
            Result.failure()
        } finally {
            ClockService.running.set(false)
        }
    }
}
