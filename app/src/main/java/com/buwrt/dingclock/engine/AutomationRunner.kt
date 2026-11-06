package com.buwrt.dingclock.engine

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.buwrt.dingclock.data.SettingsStore
import com.buwrt.dingclock.model.Backend
import com.buwrt.dingclock.model.RunStatus
import com.buwrt.dingclock.model.Settings
import com.buwrt.dingclock.model.TaskType
import kotlinx.coroutines.delay

/**
 * 打卡自动化：唤醒 -> 启动钉钉 -> 读取界面 -> 进入考勤页 -> 点打卡按钮 -> 校验结果。
 * 所有系统操作都通过 Root（优先）或 Shizuku 的 shell 权限完成。
 */
object AutomationRunner {
    private const val TAG = "DingClock"
    private const val DUMP_LOCAL = "/data/local/tmp/dingclock_ui.xml"
    private const val DUMP_SDCARD = "/sdcard/dingclock_ui.xml"
    private val packageRegex = Regex("""^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+$""")
    private val componentRegex = Regex("""^[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+$""")

    data class Result(
        val status: RunStatus,
        val message: String,
        val backend: String
    )

    suspend fun run(
        context: Context,
        task: TaskType,
        test: Boolean,
        log: (String) -> Unit
    ): Result {
        val store = SettingsStore(context)
        val settings = store.load()
        log("===== ${task.title}${if (test) "（测试）" else ""} 开始 =====")
        log("后端偏好：${settings.backend.label}")

        val shell = ShellFactory.obtain(settings.backend, context)
        if (shell == null) {
            val message = when (settings.backend) {
                Backend.SHIZUKU -> "Shizuku 未运行或未授权"
                Backend.ROOT -> "没有取得 Root 权限"
                Backend.AUTO -> "没有可用的 Root 或 Shizuku 权限"
            }
            log("✗ $message")
            return Result(RunStatus.FAILED, message, "无")
        }
        log("实际使用：${shell.name}")

        return try {
            runWithShell(shell, settings, task, log)
        } catch (t: Throwable) {
            Log.w(TAG, "automation failed", t)
            log("✗ 执行异常：${t.message}")
            Result(RunStatus.FAILED, t.message ?: "未知异常", shell.name)
        } finally {
            shell.close()
        }
    }

    private suspend fun runWithShell(
        shell: Shell,
        s: Settings,
        task: TaskType,
        log: (String) -> Unit
    ): Result {
        val pkg = s.dingTalkPackage
        if (!pkg.matches(packageRegex)) {
            log("✗ 包名格式不正确：$pkg")
            return Result(RunStatus.FAILED, "钉钉包名格式不正确", shell.name)
        }

        log("唤醒屏幕")
        shell.exec("input keyevent KEYCODE_WAKEUP", 8_000)
        delay(500)
        shell.exec("wm dismiss-keyguard", 8_000)
        delay(800)
        if (isLocked(shell)) {
            log("⚠ 检测到锁屏。若有密码/指纹锁，请先解锁，否则无法操作钉钉")
        }

        log("启动应用：$pkg")
        val launched = launchApp(shell, pkg, log)
        if (!launched) {
            log("⚠ 启动命令未成功，仍尝试读取界面")
        }
        delay(1000)

        var nodes = waitForUi(shell, pkg, s.launchWaitMs, log)
        if (nodes.isEmpty()) {
            log("✗ 读取不到界面（可能锁屏、应用未安装或被系统拦截）")
            return Result(RunStatus.FAILED, "读取不到界面结构，请确认已解锁且钉钉能正常打开", shell.name)
        }
        log("界面节点数：${nodes.size}，前台包名：${nodes.firstOrNull { it.packageName == pkg }?.packageName ?: "非目标应用"}")
        log("可见文本：${describeScreen(nodes)}")

        if (alreadyClocked(nodes)) {
            log("✓ 检测到今天已打卡，无需重复操作")
            return Result(RunStatus.SUCCESS, "检测到已打卡状态，无需重复操作", shell.name)
        }

        if (s.autoNavigate && findTarget(nodes, s, task) == null) {
            nodes = navigate(shell, s, nodes, task, log)
            log("导航后文本：${describeScreen(nodes)}")
        }

        var lastMessage = "未找到打卡按钮"
        val maxAttempts = s.retries.coerceIn(1, 8)
        for (attempt in 1..maxAttempts) {
            val target = findTarget(nodes, s, task)
            if (target == null) {
                lastMessage = "第 $attempt 次：当前页面没有找到匹配「${s.keywords(task).joinToString("/")}」的按钮"
                log(lastMessage)
                if (attempt < maxAttempts) {
                    delay(1200)
                    nodes = if (attempt == 2) navigate(shell, s, nodes, task, log) else dumpUi(shell)
                    log("第 $attempt 次重试后文本：${describeScreen(nodes)}")
                }
                continue
            }

            log("点击「${target.label}」坐标 (${target.centerX}, ${target.centerY})")
            val click = shell.exec("input tap ${target.centerX} ${target.centerY}", 8_000)
            if (!click.ok) {
                lastMessage = "点击命令执行失败：${click.output}"
                log("✗ $lastMessage")
                continue
            }
            delay(2600)
            nodes = dumpUi(shell)

            when (checkAfterTap(nodes, s, task, pkg)) {
                TapCheck.SUCCESS -> {
                    log("✓ 检测到打卡成功")
                    return Result(RunStatus.SUCCESS, "已点击「${target.label}」并确认成功", shell.name)
                }

                TapCheck.UNCERTAIN -> {
                    delay(1200)
                    nodes = dumpUi(shell)
                    if (alreadyClocked(nodes)) {
                        log("✓ 检测到打卡成功")
                        return Result(RunStatus.SUCCESS, "已点击「${target.label}」并确认成功", shell.name)
                    }
                    log("⚠ 已点击，但没有捕获到成功提示")
                    return Result(
                        RunStatus.UNCERTAIN,
                        "已点击「${target.label}」，但未检测到成功提示，请打开钉钉确认",
                        shell.name
                    )
                }

                TapCheck.NONE -> {
                    lastMessage = "第 $attempt 次：点击后未检测到成功提示"
                    log("⚠ $lastMessage")
                    delay(1000)
                    nodes = dumpUi(shell)
                }
            }
        }

        val coord = s.coord(task)
        if (coord != null) {
            log("按备用坐标点击 (${coord.first}, ${coord.second})")
            shell.exec("input tap ${coord.first} ${coord.second}", 8_000)
            delay(2600)
            nodes = dumpUi(shell)
            return if (alreadyClocked(nodes)) {
                Result(RunStatus.SUCCESS, "已通过备用坐标完成打卡", shell.name)
            } else {
                Result(RunStatus.UNCERTAIN, "已点击备用坐标，但未检测到成功提示，请人工确认", shell.name)
            }
        }

        log("✗ $lastMessage")
        return Result(RunStatus.FAILED, lastMessage, shell.name)
    }

    private suspend fun launchApp(shell: Shell, pkg: String, log: (String) -> Unit): Boolean {
        val resolved = resolveLauncher(shell, pkg)
        val commands = ArrayList<String>(4)
        if (resolved != null) commands += "am start -W -n $resolved"
        commands += "monkey -p $pkg -c android.intent.category.LAUNCHER 1"
        commands += "am start -W -n $pkg/.biz.LaunchHomeActivity"
        commands += "am start -W -n $pkg/.MainActivity"

        for (command in commands) {
            val result = shell.exec(command, 20_000)
            val failed = commandFailed(result.output) || result.timedOut
            if (failed) {
                log("启动尝试失败：${command.substringBefore(" -W")}")
                continue
            }
            delay(800)
            if (isForeground(shell, pkg)) {
                log("已进入前台")
                return true
            }
            // 有些 ROM 禁止 dumpsys 查询前台，再给一次机会
            delay(800)
            if (isForeground(shell, pkg)) return true
            log("启动命令已发送")
            return true
        }
        return false
    }

    private suspend fun resolveLauncher(shell: Shell, pkg: String): String? {
        val result = shell.exec("cmd package resolve-activity --brief $pkg", 10_000)
        if (!result.ok) return null
        return result.stdout.lineSequence()
            .map { it.trim() }
            .lastOrNull { it.matches(componentRegex) }
    }

    private suspend fun isForeground(shell: Shell, pkg: String): Boolean {
        val result = shell.exec(
            "dumpsys window | grep -E 'mCurrentFocus|mFocusedApp' | head -4",
            10_000
        )
        return result.stdout.contains("$pkg/")
    }

    private suspend fun isLocked(shell: Shell): Boolean {
        val result = shell.exec(
            "dumpsys window | grep -E 'mDreamingLockscreen=true|mShowingLockscreen=true|mKeyguardShowing=true'",
            10_000
        )
        return result.stdout.isNotBlank()
    }

    private suspend fun waitForUi(
        shell: Shell,
        pkg: String,
        timeoutMs: Int,
        log: (String) -> Unit
    ): List<UiNode> {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs.coerceIn(2_000, 60_000)
        var last: List<UiNode> = emptyList()
        while (SystemClock.elapsedRealtime() < deadline) {
            last = dumpUi(shell)
            if (last.any { it.packageName == pkg }) return last
            delay(800)
        }
        return last
    }

    private suspend fun navigate(
        shell: Shell,
        s: Settings,
        from: List<UiNode>,
        task: TaskType,
        log: (String) -> Unit
    ): List<UiNode> {
        var nodes = from

        val workbench = findLabel(nodes, listOf("工作台"))
        if (workbench != null) {
            log("点击「工作台」")
            tapNode(shell, workbench)
            delay(1600)
            nodes = dumpUi(shell)
        }

        var attendance = findLabel(nodes, listOf("考勤打卡"))
        if (attendance == null) {
            // 工作台可能不是第一屏，向上滑一次再找
            log("未看到「考勤打卡」，尝试滑动查找")
            shell.exec("input swipe 540 1800 540 900 300", 8_000)
            delay(1200)
            nodes = dumpUi(shell)
            attendance = findLabel(nodes, listOf("考勤打卡"))
        }

        if (attendance != null) {
            log("点击「考勤打卡」")
            tapNode(shell, attendance)
            delay(2800)
            nodes = dumpUi(shell)
        } else {
            log("⚠ 没有找到「考勤打卡」入口，将在当前页面继续查找按钮")
        }

        // 回到目标按钮所在的页面后，如果仍未找到，再给一次点击机会
        if (findTarget(nodes, s, task) == null) {
            val retry = findLabel(nodes, listOf("考勤打卡"))
            if (retry != null && retry !== attendance) {
                tapNode(shell, retry)
                delay(2400)
                nodes = dumpUi(shell)
            }
        }
        return nodes
    }

    private fun findTarget(nodes: List<UiNode>, s: Settings, task: TaskType): UiNode? {
        val keywords = s.keywords(task)
        if (keywords.isEmpty()) return null
        val excludes = s.excludes()

        return nodes.asSequence()
            .filter { it.label.isNotEmpty() && it.label.length <= 40 }
            .filter { node -> excludes.none { node.label.contains(it) } }
            .filter { node -> keywords.any { node.label.contains(it) } }
            .maxByOrNull { node ->
                var score = 0
                if (node.clickable) score += 100
                if (node.className.contains("Button", ignoreCase = true)) score += 30
                if (node.label == keywords.first()) score += 60
                if (node.label == "打卡") score += 20
                if (node.area in 2_000L..500_000L) score += 10
                score
            }
    }

    private fun findLabel(nodes: List<UiNode>, texts: List<String>): UiNode? =
        nodes.asSequence()
            .filter { node -> node.label.isNotEmpty() && texts.any { node.label.contains(it) } }
            .maxByOrNull { node ->
                var score = 0
                if (node.clickable) score += 100
                if (node.className.contains("Button", ignoreCase = true)) score += 20
                if (node.area in 1_000L..600_000L) score += 10
                score
            }

    private fun alreadyClocked(nodes: List<UiNode>): Boolean =
        nodes.any { node -> SUCCESS_WORDS.any { node.label.contains(it) } }

    private fun checkAfterTap(
        nodes: List<UiNode>,
        s: Settings,
        task: TaskType,
        pkg: String
    ): TapCheck {
        if (alreadyClocked(nodes)) return TapCheck.SUCCESS
        val inApp = nodes.any { it.packageName == pkg }
        if (nodes.isNotEmpty() && inApp && findTarget(nodes, s, task) == null) {
            return TapCheck.UNCERTAIN
        }
        return TapCheck.NONE
    }

    private suspend fun dumpUi(shell: Shell): List<UiNode> {
        for (path in listOf(DUMP_LOCAL, DUMP_SDCARD)) {
            val result = shell.exec(
                "rm -f $path; uiautomator dump $path >/dev/null 2>&1; cat $path 2>/dev/null",
                25_000
            )
            if (result.stdout.contains("<hierarchy")) {
                val nodes = UiParser.parse(result.stdout)
                if (nodes.isNotEmpty()) return nodes
            }
        }
        return emptyList()
    }

    private suspend fun tapNode(shell: Shell, node: UiNode): ShellResult =
        shell.exec("input tap ${node.centerX} ${node.centerY}", 8_000)

    private fun commandFailed(output: String): Boolean {
        val lower = output.lowercase()
        return lower.contains("error") ||
            lower.contains("no activities found") ||
            lower.contains("does not exist") ||
            lower.contains("unable to find") ||
            lower.contains("exception")
    }

    private fun describeScreen(nodes: List<UiNode>): String =
        nodes.asSequence()
            .map { it.label }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(12)
            .joinToString(" | ")
            .ifBlank { "（无文本）" }

    private enum class TapCheck { SUCCESS, UNCERTAIN, NONE }

    private val SUCCESS_WORDS = listOf("打卡成功", "已打卡", "更新打卡", "打卡完成")
}
