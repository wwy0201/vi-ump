package com.vi.ump.tv

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView

/** 密度换算放在包级：屏幕里很多地方直接 `ctx.dp(…)`，不必每次都包在 `with(TvUi)` 里。 */
fun android.content.Context.dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

/** TV 端的统一视觉与控件工厂。全部用 framework View，避免把 AndroidX UI 带进 API 19。 */
object TvUi {
    val BG = Color.rgb(12, 12, 15)
    val SURFACE = Color.rgb(26, 26, 32)
    val SURFACE_FOCUS = Color.rgb(44, 44, 54)
    val TEXT = Color.rgb(238, 238, 244)
    val TEXT_DIM = Color.rgb(158, 158, 170)
    val ACCENT = Color.rgb(229, 160, 13)
    val ACCENT_DIM = Color.rgb(120, 86, 20)
    val ERROR = Color.rgb(239, 108, 108)

    fun Context.tvText(text: String, sizeSp: Float = 18f, color: Int = TEXT): TextView = TextView(this).apply {
        this.text = text
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        includeFontPadding = false
        gravity = Gravity.CENTER_VERTICAL
    }

    fun Context.tvTitle(text: String, sizeSp: Float = 24f): TextView =
        tvText(text, sizeSp, TEXT).apply { setTypeface(typeface, android.graphics.Typeface.BOLD) }

    fun Context.tvButton(text: String, accent: Boolean = false, onClick: () -> Unit): TextView =
        TextView(this).apply {
            this.text = text
            setTextColor(if (accent) Color.WHITE else TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            gravity = Gravity.CENTER
            isFocusable = true
            isFocusableInTouchMode = true
            isClickable = true
            minHeight = dp(46)
            setPadding(dp(18), dp(10), dp(18), dp(10))
            background = focusBackground(
                normal = if (accent) ACCENT_DIM else SURFACE,
                focused = if (accent) ACCENT else SURFACE_FOCUS,
            )
            setOnClickListener { onClick() }
            // 4.4 盒子对 OK 键转 click 的支持不一致，显式兜底
            setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_UP &&
                    (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)
                ) {
                    performClick()
                    true
                } else {
                    false
                }
            }
        }

    fun Context.buttonBar(vararg views: View): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        views.forEachIndexed { index, v ->
            addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (index > 0) marginStart = dp(10)
            })
        }
    }

    fun Context.focusBackground(normal: Int, focused: Int, radius: Int = 8): StateListDrawable {
        val n = GradientDrawable().apply {
            setColor(normal)
            cornerRadius = dp(radius).toFloat()
        }
        val f = GradientDrawable().apply {
            setColor(focused)
            cornerRadius = dp(radius).toFloat()
            setStroke(dp(2), ACCENT)
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), f)
            addState(intArrayOf(android.R.attr.state_pressed), f)
            addState(intArrayOf(), n)
        }
    }

    fun Context.tvListView(): ListView = ListView(this).apply {
        setBackgroundColor(BG)
        divider = GradientDrawable().apply { setColor(Color.rgb(36, 36, 42)) }
        dividerHeight = dp(1)
        isFocusable = true
        isFocusableInTouchMode = true
        setSelector(focusBackground(Color.TRANSPARENT, SURFACE_FOCUS, 4))
        setPadding(0, dp(4), 0, dp(4))
        clipToPadding = false
    }

    fun Context.focusRow(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    fun Context.column(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(BG)
    }

    fun Context.matchWrap(top: Int = 0, bottom: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(top)
            bottomMargin = dp(bottom)
        }

    fun Context.weight(top: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            topMargin = dp(top)
        }

    fun Context.fill(top: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply {
            topMargin = dp(top)
        }

    fun Context.rowParams(startMargin: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginStart = dp(startMargin)
        }

    /** 统一的状态文字：不刷屏，只给当前页一句可读反馈 */
    fun Context.statusText(): TextView = tvText("", 15f, TEXT_DIM).apply {
        setPadding(0, dp(8), 0, dp(8))
    }

    /**
     * 水平行里撑开剩余空间的占位 View。
     *
     * 高度必须写死 0：裸 `View` 在 AT_MOST 高度下会按 getDefaultSize 撑满整行，
     * 把这一行拉成整屏高（详情/历史/设置/搜索都踩过，表现为只有一行按钮、其余全空）。
     */
    fun Context.hSpacer(): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(0, 0, 1f)
    }
}
