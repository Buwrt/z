package com.buwrt.mockding

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 假的钉钉界面，只用于验证打卡自动化流程，不会产生任何真实考勤数据。
 *
 * 界面结构：
 *   工作台 -> 考勤打卡 -> 上班打卡 / 下班打卡 -> 打卡成功
 */
class MainActivity : Activity() {

    private var clockedIn = false
    private var clockedOut = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showWorkbench()
    }

    private fun showWorkbench() {
        val root = container()
        root.addView(title("工作台"))
        root.addView(hint("这是模拟应用，不会同步到真实钉钉"))
        root.addView(button("考勤打卡") { showAttendance() })
        setContentView(root)
    }

    private fun showAttendance() {
        val root = container()
        root.addView(title("考勤打卡"))
        root.addView(hint("今日状态：${statusText()}"))

        root.addView(button("上班打卡") {
            clockedIn = true
            showResult("上班打卡成功")
        })
        root.addView(button("下班打卡") {
            clockedOut = true
            showResult("下班打卡成功")
        })
        root.addView(button("返回工作台") { showWorkbench() })
        setContentView(root)
    }

    private fun showResult(message: String) {
        val root = container()
        root.addView(title(message))
        root.addView(hint("模拟打卡完成，未提交到任何服务器"))
        root.addView(hint("今日状态：${statusText()}"))
        root.addView(button("返回考勤打卡") { showAttendance() })
        root.addView(button("返回工作台") { showWorkbench() })
        setContentView(root)
    }

    private fun statusText(): String {
        val inText = if (clockedIn) "上班已打卡" else "上班未打卡"
        val outText = if (clockedOut) "下班已打卡" else "下班未打卡"
        return "$inText，$outText"
    }

    private fun container(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(24), dp(48), dp(24), dp(24))
        setBackgroundColor(Color.WHITE)
    }

    private fun title(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 26f
        setTextColor(Color.BLACK)
        gravity = Gravity.CENTER
        setPadding(0, dp(8), 0, dp(16))
    }

    private fun hint(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 15f
        setTextColor(Color.DKGRAY)
        gravity = Gravity.CENTER
        setPadding(0, dp(4), 0, dp(12))
    }

    private fun button(text: String, onClick: () -> Unit): Button = Button(this).apply {
        this.text = text
        textSize = 18f
        isAllCaps = false
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(64)
        ).also { it.topMargin = dp(12) }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
