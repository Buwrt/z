package com.buwrt.dingclock.model

enum class Backend(val label: String) {
    AUTO("自动（优先 Root）"),
    ROOT("仅 Root"),
    SHIZUKU("仅 Shizuku");

    companion object {
        fun fromName(name: String?): Backend = entries.firstOrNull { it.name == name } ?: AUTO
    }
}

enum class TaskType(val title: String, val action: String) {
    CLOCK_IN("上班打卡", "com.buwrt.dingclock.action.CLOCK_IN"),
    CLOCK_OUT("下班打卡", "com.buwrt.dingclock.action.CLOCK_OUT");

    companion object {
        fun fromAction(action: String?): TaskType? = entries.firstOrNull { it.action == action }
    }
}

enum class RunStatus(val label: String) {
    SUCCESS("成功"),
    UNCERTAIN("已执行（未确认结果）"),
    FAILED("失败"),
    SKIPPED("已跳过");

    val countedAsDone: Boolean
        get() = this == SUCCESS || this == UNCERTAIN
}

/**
 * 所有的可配置项。放在纯 Kotlin 层，便于 JVM 单元测试。
 */
data class Settings(
    val enabled: Boolean = false,
    val startEnabled: Boolean = true,
    val startHour: Int = 9,
    val startMinute: Int = 0,
    val endEnabled: Boolean = true,
    val endHour: Int = 18,
    val endMinute: Int = 0,
    /** bit0=周一 ... bit6=周日 */
    val weekdaysMask: Int = 0b0011111,
    val offsetSeconds: Int = 0,
    val randomDelaySeconds: Int = 0,
    val retries: Int = 3,
    val launchWaitMs: Int = 8000,
    val backend: Backend = Backend.AUTO,
    val catchUpEnabled: Boolean = true,
    val catchUpWindowMinutes: Int = 120,
    val dingTalkPackage: String = "com.alibaba.android.rimet",
    val startKeywords: String = "上班打卡,打卡",
    val endKeywords: String = "下班打卡,打卡",
    val excludeKeywords: String =
        "考勤打卡,打卡记录,打卡设置,打卡规则,打卡时间,打卡成功,已打卡,更新打卡,外勤打卡,补卡",
    val autoNavigate: Boolean = true,
    val startCoordX: Int = -1,
    val startCoordY: Int = -1,
    val endCoordX: Int = -1,
    val endCoordY: Int = -1
) {
    fun keywords(task: TaskType): List<String> =
        (if (task == TaskType.CLOCK_IN) startKeywords else endKeywords)
            .split(',', '，', '/', '|')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    fun excludes(): List<String> =
        excludeKeywords.split(',', '，', '/', '|')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    fun coord(task: TaskType): Pair<Int, Int>? {
        val x = if (task == TaskType.CLOCK_IN) startCoordX else endCoordX
        val y = if (task == TaskType.CLOCK_IN) startCoordY else endCoordY
        return if (x >= 0 && y >= 0) x to y else null
    }

    fun timeFor(task: TaskType): Pair<Int, Int> =
        if (task == TaskType.CLOCK_IN) startHour to startMinute else endHour to endMinute
}
