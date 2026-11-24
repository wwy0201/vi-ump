package com.vi.ump.tv

import android.app.AlertDialog
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.vi.ump.storage.SettingsKeys

/** 设置：图片预加载、连续播放、历史清理、源管理。所有开关即时落盘。 */
class SettingsScreen(
    private val host: TvHost,
    private val session: TvSession,
) : TvScreen {

    private val ctx = host as android.content.Context
    private val root: LinearLayout
    private val preloadValue: TextView
    private val preloadMinus: TextView
    private val autoNextButton: TextView

    init {
        with(TvUi) {
            root = ctx.column().apply { setPadding(ctx.dp(40), ctx.dp(20), ctx.dp(40), ctx.dp(16)) }
            val top = ctx.focusRow()
            top.addView(ctx.tvButton("← 返回") { host.pop() }, ctx.rowParams())
            top.addView(ctx.tvTitle("设置", 22f), ctx.rowParams(14))
            top.addView(ctx.hSpacer())
            top.addView(ctx.tvButton("源管理") { host.backToHome() }, ctx.rowParams())
            root.addView(top, ctx.matchWrap())

            root.addView(
                ctx.tvText("数据源：${session.source.label}（${session.source.id}）", 14f, TEXT_DIM),
                ctx.matchWrap(top = 10),
            )

            val preloadRow = ctx.focusRow()
            preloadRow.addView(ctx.tvText("图片预加载", 18f, TEXT), ctx.weight())
            preloadMinus = ctx.tvButton("−") { changePreload(-1) }
            preloadRow.addView(preloadMinus, ctx.rowParams(8))
            preloadValue = ctx.tvText("", 20f, ACCENT).apply {
                gravity = android.view.Gravity.CENTER
                minWidth = ctx.dp(46)
            }
            preloadRow.addView(preloadValue, ctx.rowParams(8))
            preloadRow.addView(ctx.tvButton("+") { changePreload(1) }, ctx.rowParams(8))
            root.addView(preloadRow, ctx.matchWrap(top = 12))

            root.addView(
                ctx.tvText(
                    "0–${SettingsKeys.IMAGE_PRELOAD_MAX} 张。只向后预加载：设 3 → 看第 1 张时加载 1、预加载 2/3/4；看第 15 张时加载 15、预加载 16/17/18。",
                    13f, TEXT_DIM,
                ),
                ctx.matchWrap(top = 4),
            )

            val autoRow = ctx.focusRow()
            autoRow.addView(ctx.tvText("视频连续播放", 18f, TEXT), ctx.weight())
            autoNextButton = ctx.tvButton("") { toggleAutoNext() }
            autoRow.addView(autoNextButton, ctx.rowParams(8))
            root.addView(autoRow, ctx.matchWrap(top = 14))

            root.addView(
                ctx.tvText(
                    "TV 版音频不后台、不发通知：切到后台直接暂停。手机版仍保留后台音频与通知栏。",
                    13f, TEXT_DIM,
                ),
                ctx.matchWrap(top = 4),
            )

            val historyRow = ctx.focusRow()
            historyRow.addView(
                ctx.tvButton("清空本源搜索历史") { clearSearch() },
                ctx.rowParams(),
            )
            historyRow.addView(
                ctx.tvButton("清空本源观看历史") { clearWatch() },
                ctx.rowParams(10),
            )
            root.addView(historyRow, ctx.matchWrap(top = 18))

            root.addView(
                ctx.tvText(
                    "提示：图片查看器里用 ←/→ 翻页，OK 显示/隐藏信息；播放页用 ←/→ 快退快进 10 秒，OK 切换播放/暂停。",
                    13f, TEXT_DIM,
                ),
                ctx.matchWrap(top = 12),
            )
        }
        refresh()
    }

    override val view: View get() = root

    override fun onShow() {
        refresh()
    }

    override fun requestInitialFocus() {
        preloadMinus.requestFocus()
    }

    private fun refresh() {
        preloadValue.text = session.imagePreload().toString()
        val auto = session.settings.getString(SettingsKeys.PLAYER_AUTO_NEXT) == "1"
        autoNextButton.text = if (auto) "已开启" else "已关闭"
    }

    private fun changePreload(delta: Int) {
        val next = (session.imagePreload() + delta)
            .coerceIn(0, SettingsKeys.IMAGE_PRELOAD_MAX)
        session.setImagePreload(next)
        refresh()
    }

    private fun toggleAutoNext() {
        val next = session.settings.getString(SettingsKeys.PLAYER_AUTO_NEXT) != "1"
        session.settings.putString(SettingsKeys.PLAYER_AUTO_NEXT, if (next) "1" else "0")
        refresh()
    }

    private fun clearSearch() {
        AlertDialog.Builder(ctx)
            .setTitle("清空搜索历史")
            .setMessage("只清空「${session.source.label}」的搜索词。")
            .setNegativeButton("取消", null)
            .setPositiveButton("清空") { _, _ ->
                TvHistoryStore.clearSearch(session.settings, session.source.id)
                host.toast("已清空搜索历史")
            }
            .show()
    }

    private fun clearWatch() {
        AlertDialog.Builder(ctx)
            .setTitle("清空观看历史")
            .setMessage("只清空「${session.source.label}」的观看记录，不影响图片/播放进度。")
            .setNegativeButton("取消", null)
            .setPositiveButton("清空") { _, _ ->
                TvHistoryStore.clear(session.settings, session.source.id)
                host.toast("已清空观看历史")
            }
            .show()
    }
}
