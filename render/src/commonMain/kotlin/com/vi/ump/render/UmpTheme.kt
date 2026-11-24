package com.vi.ump.render

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import com.vi.ump.model.ThemeMode
import com.vi.ump.model.ThemeSpec

/**
 * 配置里的 [ThemeSpec] → Compose 主题。
 *
 * 只映射"语义色"，不让配置指定任意组件样式：
 * primary / background / surface / onSurface / accent 五个槽位，
 * 其余（字号、间距、动效）由代码统一控制，保证不同配置下观感一致。
 *
 * **两条硬约束**（都踩过）：
 * 1. `onBackground` 必须一并设置。只设 `onSurface` 时，`Surface(background)` 的内容色会落回
 *    Material3 的默认 `onBackground`——浅色方案里那是深色，于是"黑背景 + 深色字"= 黑底黑字。
 * 2. `mode = SYSTEM` 时按**配置里 background 的亮度**判断明暗，而不是系统深色模式开关。
 *    背景色是配置写死的，再跟随系统开关必然出现配色割裂。
 */
@Composable
fun UmpTheme(theme: ThemeSpec, content: @Composable () -> Unit) {
    val primaryRaw = parseHex(theme.primary, Color(0xFFFF6B35))
    val background = parseHex(theme.background, Color(0xFF0F0F13))
    // primary 同时当"文字色"用（TextButton 的默认内容色）：抖音这类品牌色是 #161823，
    // 直接铺在黑底上等于隐形，所以只在对比不够时把它提亮/压暗，正常配色原样保留。
    val primary = primaryRaw.readableOn(background)
    val surface = parseHex(theme.surface, Color(0xFF17171C))
    val onSurface = parseHex(theme.onSurface, Color(0xFFE6E6EA))
    val accent = parseHex(theme.accent, Color(0xFF3DDC97))

    val dark = when (theme.mode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> background.luminance() < 0.5f
    }

    val scheme = if (dark) {
        darkColorScheme(
            primary = primary,
            tertiary = accent,
            background = background,
            surface = surface,
            onSurface = onSurface,
            onBackground = onSurface,
            onPrimary = background,
            surfaceVariant = background,
            onSurfaceVariant = onSurface,
        )
    } else {
        lightColorScheme(
            primary = primary,
            tertiary = accent,
            background = background,
            surface = surface,
            onSurface = onSurface,
            onBackground = onSurface,
            onPrimary = background,
            surfaceVariant = surface,
            onSurfaceVariant = onSurface,
        )
    }

    MaterialTheme(colorScheme = scheme, content = content)
}

/** `#RRGGBB` / `#AARRGGBB`，解析失败回落默认色，绝不让一处配色错误拖垮整个页面 */
fun parseHex(value: String, fallback: Color): Color {
    val clean = value.trim().removePrefix("#")
    if (clean.length != 6 && clean.length != 8) return fallback
    val parsed = clean.toLongOrNull(16) ?: return fallback
    val alpha = if (clean.length == 8) ((parsed shr 24) and 0xFF) / 255f else 1f
    val red = ((parsed shr 16) and 0xFF) / 255f
    val green = ((parsed shr 8) and 0xFF) / 255f
    val blue = (parsed and 0xFF) / 255f
    return Color(red, green, blue, alpha)
}

/**
 * 把一个颜色**当文字色**放到 [background] 上时，保证它读得出来。
 *
 * 配置里的 `primary`（以及 `meta.color`）都是品牌色：抖音是 `#161823`，
 * 铺在自己的黑底上就是"黑底黑字"——历史页的标题、「清空」、「更多」一度全看不见。
 *
 * 只在对比不足时才动它（往白 / 往黑混到够亮或够暗），正常配色原样返回，
 * 不会把配置作者的观感抹平。
 */
fun Color.readableOn(background: Color): Color {
    val bgDark = background.luminance() < 0.5f
    val lum = luminance()
    val needsFix = if (bgDark) lum < 0.35f else lum > 0.65f
    if (!needsFix) return this
    val target = if (bgDark) Color.White else Color.Black
    var mix = 0.25f
    var out = lerp(this, target, mix)
    while (out.luminance().let { if (bgDark) it < 0.35f else it > 0.65f } && mix < 1f) {
        mix += 0.25f
        out = lerp(this, target, mix.coerceAtMost(1f))
    }
    return out
}
