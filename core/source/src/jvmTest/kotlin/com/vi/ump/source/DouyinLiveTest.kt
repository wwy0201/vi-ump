package com.vi.ump.source

import com.vi.ump.config.ConfigCompiler
import java.io.File
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * 联网冒烟：douyin.json 的推荐流能否真的签过去。
 *
 * 默认跳过（依赖线上接口与外网），要跑时手动去掉 @Ignore：
 * `gradle :core:source:jvmTest --tests 'com.vi.ump.source.DouyinLiveTest'`
 */
class DouyinLiveTest {

    private fun repoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            if (File(dir, "samples").isDirectory) return dir
            dir = dir.parentFile
        }
        throw IllegalStateException("找不到 samples")
    }

    @Ignore
    @Test
    fun recommendFeedLoads() = runBlocking {
        // douyin.json 只在开发者本地（仓库不保留），没有这份样本就跳过
        val file = File(repoRoot(), "samples/douyin.json")
        org.junit.Assume.assumeTrue("本地样本不存在，跳过：samples/douyin.json", file.isFile)
        val text = file.readText()
        val schema = ConfigCompiler.loadAndCompile(text)
        val pipeline = SourcePipeline(schema, HttpFetcher(createHttpClient()))
        val page = pipeline.list("recommend", 1)
        println("LIVE items=${page.items.size} hasMore=${page.hasMore} skipped=${page.skipped}")
        page.items.take(5).forEach {
            println("LIVE item id=${it.id} title=${it.title.take(24).replace("\n", " ")} poster=${it.poster?.take(60)}")
        }
        assertTrue(page.items.isNotEmpty(), "推荐流没有返回任何条目")
        // 没有详情页，播放地址必须已经在列表里带出来
        assertTrue(page.items.all { !it.playUrl.isNullOrBlank() }, "列表条目缺少 playUrl")
    }
}
