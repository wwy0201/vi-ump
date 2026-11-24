package com.vi.ump.feature

import androidx.compose.ui.graphics.Color
import kotlin.math.abs

/**
 * 源标识色的分配。
 *
 * 规则（用户 2026-11-16 定稿）：
 * 1. 作者显式配色（`meta.color`，或显式写过 `theme.primary`）→ 原样用；
 * 2. 两个源显式写成同一个颜色 → **就用同一个颜色**（只有自动分配才去重）；
 * 3. 没配的 → 客户端自动生成，且**不能与已有色相同**，色相间隔至少 40°。
 *
 * 为什么要自动配色：多个源摆在下拉/设置列表里，只靠名字扫一眼分不出来；
 * 颜色是"这是哪个源"最快的线索。而色相挨太近（都是橙色系）等于没配，所以按
 * 黄金角 137.5° 递增来试探——相邻两次试探天然差 137°，冲突概率极低且永远不会
 * 落在同一个色相附近。
 */
object SourcePalette {

    /** 与已有色相至少差这么多度，才算"肉眼可分" */
    private const val MIN_HUE_DISTANCE = 40f

    /** 黄金角：连续试探的色相天然分散，比逐度递增更"跳" */
    private const val GOLDEN_ANGLE = 137.508f

    /** 自动色的饱和度/亮度：中高饱和、中亮，深色主题下不刺眼也看得清 */
    private const val AUTO_SATURATION = 0.62f
    private const val AUTO_LIGHTNESS = 0.58f

    /**
     * 全量分配：一次算完所有源，因为"不重复"是全局约束（后配的要知道前面的色）。
     * 返回 id → Color。
     */
    fun assign(entries: List<SourceEntry>): Map<String, Color> {
        val out = LinkedHashMap<String, Color>(entries.size)
        val usedHues = ArrayList<Float>(entries.size)

        // 显式色先登记：自动分配要避开它们（显式色之间允许重合）。
        // 灰度色（白/灰/黑）没有色相，不参与"色相冲突"判断，否则 -1 会把整圈都算成冲突
        entries.forEach { entry ->
            parseHex(entry.color)?.let { color ->
                out[entry.id] = color
                val hue = hueOf(color)
                if (hue >= 0f) usedHues += hue
            }
        }

        entries.forEach { entry ->
            if (out.containsKey(entry.id)) return@forEach
            val seed = (entry.id.hashCode().toLong() and 0xFFFF_FFFFL).rem(360L).toFloat()
            var step = 0
            var hue = seed
            // 最多试 24 次：源再多也不可能把 360° 的每个 40° 区间都占满
            while (step < 24) {
                hue = (seed + step * GOLDEN_ANGLE).rem(360f)
                if (usedHues.none { distance(it, hue) < MIN_HUE_DISTANCE }) break
                step++
            }
            usedHues += hue
            out[entry.id] = hsl(hue)
        }
        return out
    }

    /** `#RRGGBB` / `#AARRGGBB`；非法返回 null（配置写错不该让 App 崩，只是没颜色） */
    fun parseHex(hex: String?): Color? {
        val raw = hex?.trim()?.removePrefix("#") ?: return null
        return when (raw.length) {
            6 -> Color(0xFF000000L or (raw.toLongOrNull(16) ?: return null))
            8 -> Color(raw.toLongOrNull(16) ?: return null)
            else -> null
        }
    }

    private fun hex2(v: Int): String = v.toString(16).uppercase().padStart(2, '0')

    /** 色相距离（环形）：0..180 */
    private fun distance(a: Float, b: Float): Float {
        val d = abs(a - b).rem(360f)
        return if (d > 180f) 360f - d else d
    }

    /** RGB → 色相。饱和度为 0 的灰度色返回 -1（不与任何色相冲突） */
    private fun hueOf(color: Color): Float {
        val r = color.red
        val g = color.green
        val b = color.blue
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val delta = max - min
        if (delta < 1e-4f) return -1f
        val hue = when (max) {
            r -> 60f * (((g - b) / delta).rem(6f))
            g -> 60f * (((b - r) / delta) + 2f)
            else -> 60f * (((r - g) / delta) + 4f)
        }
        return if (hue < 0f) hue + 360f else hue
    }

    private fun hsl(hue: Float, saturation: Float = AUTO_SATURATION, lightness: Float = AUTO_LIGHTNESS): Color {
        val c = (1f - abs(2f * lightness - 1f)) * saturation
        val x = c * (1f - abs((hue / 60f).rem(2f) - 1f))
        val m = lightness - c / 2f
        val (r1, g1, b1) = when {
            hue < 60f -> Triple(c, x, 0f)
            hue < 120f -> Triple(x, c, 0f)
            hue < 180f -> Triple(0f, c, x)
            hue < 240f -> Triple(0f, x, c)
            hue < 300f -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        return Color(red = r1 + m, green = g1 + m, blue = b1 + m, alpha = 1f)
    }
}

/** Compose Color → `#RRGGBB`（或带 alpha 的 `#AARRGGBB`），回写给 `ThemeSpec.primary` 用 */
fun Color.toHex(): String {
    val a = (alpha * 255).toInt().coerceIn(0, 255)
    val r = (red * 255).toInt().coerceIn(0, 255)
    val g = (green * 255).toInt().coerceIn(0, 255)
    val b = (blue * 255).toInt().coerceIn(0, 255)
    fun h2(v: Int) = v.toString(16).uppercase().padStart(2, '0')
    return if (a == 255) "#" + h2(r) + h2(g) + h2(b) else "#" + h2(a) + h2(r) + h2(g) + h2(b)
}
