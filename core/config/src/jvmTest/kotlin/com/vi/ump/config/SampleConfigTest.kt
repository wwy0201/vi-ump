package com.vi.ump.config

import com.vi.ump.model.LayoutType
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 样本目录是**全项目唯一的测试数据来源**。
 *
 * 这里不内联任何 JSON：直接读 samples 目录下的 .json 文件，
 * 于是"文档里给你看的示例"和"CI 里跑的东西"永远是同一份文件。
 */
class SampleConfigTest {

    private fun repoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            if (File(dir, "samples").isDirectory) return dir
            dir = dir.parentFile
        }
        throw IllegalStateException("找不到 samples 目录，cwd=${System.getProperty("user.dir")}")
    }

    private fun sample(relative: String): File = File(repoRoot(), "samples/$relative")

    private fun sampleFiles(): List<File> =
        File(repoRoot(), "samples")
            .listFiles { f -> f.isFile && f.name.endsWith(".json") }
            .orEmpty()
            .sortedBy { it.name }
            .also { assertTrue(it.isNotEmpty(), "samples 下没有任何 .json 样本") }

    @Test
    fun everySampleCompilesWithoutError() {
        val failures = mutableListOf<String>()
        for (file in sampleFiles()) {
            val schema = ConfigCompiler.loadAndCompile(file.readText())
            val errors = schema.issues.filter { it.severity == Severity.ERROR }
            if (errors.isNotEmpty()) {
                failures += "${file.name}:\n" + errors.joinToString("\n") { "  $it" }
            }
        }
        assertTrue(failures.isEmpty(), "以下样本编译失败:\n" + failures.joinToString("\n"))
    }

    @Test
    fun everySampleDeclaresBothListAndDetail() {
        for (file in sampleFiles()) {
            val schema = ConfigCompiler.loadAndCompile(file.readText())
            assertTrue(schema.feeds.isNotEmpty(), "${file.name} 缺少 feeds")
            // 没有详情页的源（抖音：单条详情接口要 uifid，403）直接拿列表里的直链播放，
            // 这种源不该被要求声明一个永远用不上的 details
            val hasDetailPage = schema.pages.any { it.layout.type == LayoutType.DETAIL }
            if (hasDetailPage) {
                assertTrue(schema.details.isNotEmpty(), "${file.name} 缺少 details")
            }
        }
    }

    @Test
    fun youtubeSampleExtractsWebPageJsonFromHtml() {
        val schema = ConfigCompiler.loadAndCompile(sample("youtube.json").readText())
        val feed = assertNotNull(schema.feeds["channelFeed"], "youtube.json 缺少 channelFeed")
        val strip = assertNotNull(feed.strip, "strip 未编译成功：页面里那坨 ytInitialData 挖不出来")

        assertEquals("var ytInitialData = ", strip.prefix, "strip.prefix 不是 ytInitialData 锚点")
        assertEquals(";</script>", strip.suffix, "strip.suffix 不是脚本结束锚点")
        assertTrue(!feed.isTextSplit, "剥完就是合法 JSON，应走 JSONPath 而不是 listSplit")
        assertNotNull(feed.list, "list 路径未编译成功")

        // 网页版首页栏目条：全部 / 播客 / 音乐 / 直播 / 游戏 / 合辑 / 喜剧短片
        val home = assertNotNull(schema.pages.firstOrNull { it.id == "home" }, "缺少 home 页")
        val titles = home.channels.map { it.title }
        assertEquals(
            listOf("全部", "播客", "音乐", "直播", "游戏", "合辑", "喜剧短片"),
            titles,
            "栏目条应与网页版一致，实际：$titles",
        )

        // 详情只能走 oEmbed：YouTube 没有按 id 取单条视频的公开接口
        val detail = assertNotNull(schema.details["video"], "youtube.json 缺少 details.video")
        assertTrue(
            detail.request.urlTemplate.contains("/oembed"),
            "详情应走 oEmbed，实际模板：${detail.request.urlTemplate}",
        )

        // 直链要 nsig 签名 + PoToken，只能交给内嵌浏览器：配置必须开网页播放
        assertTrue(
            schema.config.playback.web,
            "youtube.json 必须开 playback.web，否则 playUrl 是 watch 页、ExoPlayer 必报 P3003",
        )
    }

    @Test
    fun qqSampleStripsTheNonStandardJsonEnvelope() {
        // qq-video.json 只在开发者本地（仓库不保留），没有这份样本就跳过
        val qqSample = sample("qq-video.json")
        org.junit.Assume.assumeTrue("本地样本不存在，跳过：samples/qq-video.json", qqSample.isFile)
        val schema = ConfigCompiler.loadAndCompile(qqSample.readText())
        val feed = assertNotNull(schema.feeds["episodeQuality"], "qq-video.json 缺少 episodeQuality")
        val strip = assertNotNull(feed.strip, "strip 未编译成功：QZOutputJson 外壳将无法解析")

        assertEquals("QZOutputJson=", strip.prefix)
        assertEquals(";", strip.suffix)
        assertNotNull(feed.root, "root 路径未编译成功")
        assertNotNull(feed.list, "list 路径未编译成功")
    }
}
