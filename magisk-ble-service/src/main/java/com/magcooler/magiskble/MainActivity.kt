package com.magcooler.magiskble

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var client: BleCoolerClient
    private lateinit var statusText: TextView
    private lateinit var modeText: TextView
    private lateinit var rpmText: TextView
    private lateinit var tempText: TextView
    private lateinit var powerText: TextView
    private lateinit var writeText: TextView

    private val stateListener: (CoolerTelemetryState) -> Unit = { state ->
        runOnUiThread { render(state) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestRuntimePermissions()
        MagcoolerBleService.start(this, MagcoolerBleService.ACTION_UI_OPEN)
        client = BleClientHolder.get(this)
        setContentView(buildContentView())
        CoolerStateStore.addListener(stateListener)
    }

    override fun onDestroy() {
        CoolerStateStore.removeListener(stateListener)
        super.onDestroy()
    }

    private fun buildContentView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(24))
        }
        root.addView(TextView(this).apply {
            text = "冰环散热控制"
            textSize = 24f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "支持手动档位、破坏神、智能温控和遥测显示。不要同时打开原 App 控制同一散热器。"
            textSize = 14f
            setPadding(0, dp(6), 0, dp(12))
        })

        statusText = metric("连接", "--").also { root.addView(it) }
        modeText = metric("模式", "--").also { root.addView(it) }
        rpmText = metric("转速", "--").also { root.addView(it) }
        tempText = metric("背夹温度", "--").also { root.addView(it) }
        powerText = metric("功率", "--").also { root.addView(it) }
        writeText = metric("最近写入", "--").also { root.addView(it) }

        root.addView(buttonRow(
            button("连接/刷新") { client.refreshTelemetry() },
            button("开启散热") { client.turnOn {} },
            button("关闭散热") { client.turnOff {} }
        ))
        root.addView(buttonRow(
            button("破坏神 ON") { client.setBoost(true) {} },
            button("破坏神 OFF") { client.setBoost(false) {} },
            button("智能温控") { client.setSmart(true) {} }
        ))
        root.addView(buttonRow(
            button("智能 OFF") { client.setSmart(false) {} },
            button("刷新遥测") { client.refreshTelemetry() },
            button("断开") { client.disconnect() }
        ))

        root.addView(TextView(this).apply {
            text = "手动档位"
            textSize = 18f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(14), 0, dp(8))
        })
        val grid = GridLayout(this).apply {
            columnCount = 5
            rowCount = 2
        }
        for (level in 1..10) {
            grid.addView(button("${level}档") { client.setManualLevel(level) {} }, GridLayout.LayoutParams().apply {
                width = 0
                height = dp(52)
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                setMargins(dp(3), dp(3), dp(3), dp(3))
            })
        }
        root.addView(grid)

        return ScrollView(this).apply { addView(root) }
    }

    private fun metric(label: String, value: String): TextView {
        return TextView(this).apply {
            text = "$label：$value"
            textSize = 16f
            setPadding(0, dp(5), 0, dp(5))
        }
    }

    private fun buttonRow(vararg buttons: Button): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(4), 0, dp(4))
            buttons.forEach { addView(it, LinearLayout.LayoutParams(0, dp(48), 1f).apply { setMargins(dp(3), 0, dp(3), 0) }) }
        }
    }

    private fun button(text: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            this.text = text
            textSize = 13f
            gravity = Gravity.CENTER
            setOnClickListener { onClick() }
        }
    }

    private fun render(state: CoolerTelemetryState) {
        statusText.text = "连接：${state.status}"
        modeText.text = "模式：${state.mode}${state.level?.let { " · ${it}档" } ?: ""}${if (state.manualWritePending) " · 等待确认" else if (state.levelConfirmed) " · 已确认" else ""}"
        rpmText.text = "转速：${state.rpmText()}"
        tempText.text = "背夹温度：${state.tempText()}"
        powerText.text = "功率：${state.powerText()}"
        writeText.text = "最近写入：${state.lastWriteResult}"
    }

    private fun requestRuntimePermissions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val permissions = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (permissions.isNotEmpty()) requestPermissions(permissions.toTypedArray(), 100)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()
}
