package com.vi.ump.tv

import android.graphics.Color
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import java.io.File

/** 源管理 / 启动页。没有源时给出可操作的导入路径，而不是一屏空白。 */
class HomeScreen(private val host: TvHost) : TvScreen {

    private val ctx = host as android.content.Context
    private val root: LinearLayout
    private val status: TextView
    private val list: ListView
    private val importButton: TextView
    private val sources = mutableListOf<TvSource>()

    init {
        with(TvUi) {
            root = ctx.column().apply { setPadding(ctx.dp(42), ctx.dp(24), ctx.dp(42), ctx.dp(18)) }

            root.addView(ctx.tvTitle("ViUmp TV"), ctx.matchWrap())
            root.addView(
                ctx.tvText("遥控器：方向键移动焦点，OK 确认，返回键回上一级", 14f, TEXT_DIM),
                ctx.matchWrap(top = 4),
            )

            status = ctx.statusText()
            root.addView(status, ctx.matchWrap(top = 4))

            val import = ctx.tvButton("从 Download 导入", accent = true) { importDownload() }
            importButton = import
            val actions = ctx.buttonBar(
                ctx.tvButton("刷新源列表") { refresh() },
                import,
                ctx.tvButton("设置") { host.openSettings() },
                ctx.tvButton("历史") { host.openHistory() },
            )
            root.addView(actions, ctx.matchWrap(top = 8))

            root.addView(
                ctx.tvText(
                    "adb push 源配置到 /sdcard/Download/ 后点「从 Download 导入」；也可以直接选择已导入的源。",
                    13f, TEXT_DIM,
                ),
                ctx.matchWrap(top = 10),
            )

            list = ctx.tvListView().apply {
                setOnItemClickListener { _, _, position, _ -> open(position) }
                setOnKeyListener { _, keyCode, event ->
                    if (event.action == KeyEvent.ACTION_UP &&
                        (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)
                    ) {
                        open(selectedItemPosition)
                        true
                    } else false
                }
            }
            root.addView(list, ctx.fill(top = 8))
        }
    }

    override val view: View get() = root

    override fun onShow() {
        refresh()
    }

    override fun requestInitialFocus() {
        if (sources.isNotEmpty()) list.requestFocus() else importButton.requestFocus()
    }

    private fun refresh() {
        sources.clear()
        sources += TvSourceStore.list(ctx)
        list.adapter = SourceAdapter()
        status.text = if (sources.isEmpty()) {
            "还没有数据源。把 JSON 推到 /sdcard/Download/，再点「从 Download 导入」。"
        } else {
            "共 ${sources.size} 个数据源。选择一项进入首页。"
        }
        if (sources.isNotEmpty()) list.setSelection(0)
    }

    private fun open(position: Int) {
        val s = sources.getOrNull(position) ?: return
        host.openSource(s)
    }

    private fun importDownload() {
        status.text = "正在扫描 Download 目录…"
        (host as? TvMainActivity)?.runIo {
            val dirs = listOf(
                File("/sdcard/Download"),
                File("/storage/emulated/0/Download"),
            ).distinct()
            val files = dirs.flatMap { d ->
                d.listFiles { f -> f.isFile && f.name.endsWith(".json", ignoreCase = true) }?.toList().orEmpty()
            }.distinctBy { it.absolutePath }
            if (files.isEmpty()) {
                (host as? TvMainActivity)?.ui {
                    status.text = "Download 目录里没有 .json 配置。"
                }
                return@runIo
            }
            var ok = 0
            val errors = mutableListOf<String>()
            files.sortedBy { it.name }.forEach { file ->
                runCatching { TvSourceStore.import(ctx, file.readText()) }
                    .onSuccess { ok++ }
                    .onFailure { e -> errors.add("${file.name}: ${e.message}") }
            }
            (host as? TvMainActivity)?.ui {
                refresh()
                status.text = if (errors.isEmpty()) {
                    "已导入 $ok 个源。"
                } else {
                    "导入 $ok 个；失败 ${errors.size} 个：${errors.take(2).joinToString("；")}"
                }
            }
        }
    }

    private inner class SourceAdapter : BaseAdapter() {
        override fun getCount(): Int = sources.size
        override fun getItem(position: Int): Any = sources[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val row = (convertView as? LinearLayout) ?: with(TvUi) {
                ctx.column().apply {
                    setPadding(ctx.dp(18), ctx.dp(12), ctx.dp(18), ctx.dp(12))
                    isFocusable = false
                }
            }
            row.removeAllViews()
            val s = sources[position]
            row.addView(
                with(TvUi) {
                    ctx.tvText(s.label + (s.version?.let { "  v$it" } ?: ""), 19f, TEXT)
                },
                with(TvUi) { ctx.matchWrap() },
            )
            row.addView(
                with(TvUi) { ctx.tvText("${s.id}  ·  ${s.file.name}", 13f, TEXT_DIM) },
                with(TvUi) { ctx.matchWrap(top = 3) },
            )
            return row
        }
    }
}
