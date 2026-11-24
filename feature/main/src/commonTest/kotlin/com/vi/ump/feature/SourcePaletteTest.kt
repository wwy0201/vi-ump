package com.vi.ump.feature

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 源标识色分配的回归测试。
 *
 * 这些断言守的是**用户能看见的效果**：
 *  - 没配色的源不能撞色（两个一样的橙色 = 等于没配色）；
 *  - 配了色的源不能被自动分配覆盖（作者说了算）；
 *  - 两个源显式写同一个颜色 → 就用同一个颜色（用户 2026-11-16 明确定过）。
 */
class SourcePaletteTest {

    private fun entry(id: String, color: String? = null) =
        SourceEntry(id = id, label = id, color = color, json = "{}")

    /** 与 SourcePalette 内部同口径的色相距离（0..180） */
    private fun hueDistance(a: Float, b: Float): Float {
        val d = abs(a - b).rem(360f)
        return if (d > 180f) 360f - d else d
    }

    @Test
    fun autoColorsAreDistinctAndFarApartInHue() {
        val entries = listOf("e-hentai", "huangguo-drama", "itunes-rock", "qq-video", "youtube", "demo-site")
            .map { entry(it) }
        val colors = SourcePalette.assign(entries)

        assertEquals(entries.size, colors.size, "每个源都要分到颜色")
        val hexes = colors.values.map { it.toHex() }
        assertEquals(hexes.size, hexes.toSet().size, "自动分配的颜色必须互不相同：$hexes")

        // 色相两两至少差 40°：同一屏里两个源的颜色不能"看起来一样"
        val hues = colors.values.map { color ->
            // 用 HSL 反解不划算，直接借 parse→hue 的公开路径：这里通过 hue 范围检查
            hueOf(color)
        }
        hues.indices.forEach { i ->
            (i + 1 until hues.size).forEach { j ->
                assertTrue(
                    hueDistance(hues[i], hues[j]) >= 40f,
                    "源 ${entries[i].id} 与 ${entries[j].id} 的色相太近：${hues[i]} vs ${hues[j]}",
                )
            }
        }
    }

    @Test
    fun explicitColorIsUsedAsIs() {
        val colors = SourcePalette.assign(
            listOf(
                entry("a", "#3DDC97"),
                entry("b", "#FF6B35"),
                entry("c"), // 没配 → 自动，且不能撞上面两个
            ),
        )
        assertEquals("#3DDC97", colors.getValue("a").toHex())
        assertEquals("#FF6B35", colors.getValue("b").toHex())

        val autoHue = hueOf(colors.getValue("c"))
        val usedHues = listOf("#3DDC97", "#FF6B35").map { hueOf(SourcePalette.parseHex(it)!!) }
        usedHues.forEach {
            assertTrue(hueDistance(it, autoHue) >= 40f, "自动色不该贴着显式色")
        }
    }

    @Test
    fun sameExplicitColorIsAllowed() {
        val colors = SourcePalette.assign(
            listOf(entry("a", "#3DDC97"), entry("b", "#3DDC97")),
        )
        // 用户口径：两个源配了同一个颜色，就显示同一个颜色（只有自动分配才去重）
        assertEquals(colors.getValue("a").toHex(), colors.getValue("b").toHex())
        assertEquals("#3DDC97", colors.getValue("a").toHex())
    }

    @Test
    fun invalidHexFallsBackToAuto() {
        val colors = SourcePalette.assign(listOf(entry("a", "#GGGGGG"), entry("b", "red")))
        // 写错颜色不许崩，也不许把非法串当颜色：照常给一个自动色
        assertEquals(2, colors.size)
        colors.values.forEach { assertTrue(it.toHex().length == 7, "都要是 #RRGGBB：${it.toHex()}") }
    }

    @Test
    fun hexRoundTrip() {
        assertEquals("#3DDC97", SourcePalette.parseHex("#3DDC97")!!.toHex())
        assertEquals("#FF6B35", SourcePalette.parseHex("FF6B35")!!.toHex(), "前缀 # 可省")
        assertEquals(null, SourcePalette.parseHex("#12345"), "长度不对 → null")
        assertEquals(null, SourcePalette.parseHex(null))
    }

    /** 测试侧复刻一份 RGB→色相（生产代码里是 private，不为了测试把它放开） */
    private fun hueOf(color: androidx.compose.ui.graphics.Color): Float {
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
}
