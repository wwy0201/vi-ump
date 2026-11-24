package com.vi.ump.feature

import com.vi.ump.storage.InMemoryAppSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 历史/搜索记录的编码回归（2026-11-18）。
 *
 * 这两份数据是**分隔符拼字符串**存的，一旦分隔符与内容撞上，坏的是整页历史。
 * 所以这里只测三件真会出问题的事：
 * 1. 按源分开存（A 源的记录不能出现在 B 源）；
 * 2. 同一条目重复进入只留一条并置顶（否则看十次就有十条）；
 * 3. 标题里带换行/分隔符时不能把一条记录劈成两条。
 */
class HistoryStoreTest {

    private val settings = InMemoryAppSettings()

    private fun item(
        sourceId: String = "eh",
        itemId: String = "1",
        title: String = "标题",
        kind: String = "image",
        episode: Int = 0,
        trackIndex: Int = 0,
    ) = HistoryStore.WatchItem(
        sourceId = sourceId,
        itemId = itemId,
        title = title,
        poster = null,
        kind = kind,
        groupName = null,
        episode = episode,
        trackIndex = trackIndex,
    )

    @Test
    fun searchHistoryKeepsNewestFirstAndDedupes() {
        HistoryStore.addSearch(settings, "eh", "漫画")
        HistoryStore.addSearch(settings, "eh", "真人")
        HistoryStore.addSearch(settings, "eh", "漫画")

        assertEquals(listOf("漫画", "真人"), HistoryStore.searchHistory(settings, "eh"))
        // 另一个源是空的：按源存的意义就在这里
        assertEquals(emptyList(), HistoryStore.searchHistory(settings, "other"))
    }

    @Test
    fun watchRecordUpsertsSameItemAndKeepsSourcesApart() {
        HistoryStore.record(settings, item(itemId = "1", trackIndex = 5))
        HistoryStore.record(settings, item(itemId = "2", trackIndex = 13))
        HistoryStore.record(settings, item(itemId = "1", trackIndex = 21))

        val eh = HistoryStore.watch(settings, "eh")
        assertEquals(2, eh.size, "同一条目只应留一条")
        assertEquals("1", eh.first().itemId, "刚进入的那条要在最前")
        assertEquals(21, eh.first().trackIndex)
        assertEquals(listOf("eh"), HistoryStore.sourcesWithHistory(settings))
        assertEquals(emptyList(), HistoryStore.watch(settings, "other"))
    }

    @Test
    fun titleWithSeparatorsDoesNotSplitRecord() {
        val tricky = "第一行\n第二行\u001e第三行\u001f收尾"
        HistoryStore.record(settings, item(itemId = "x", title = tricky))

        val list = HistoryStore.watch(settings, "eh")
        assertEquals(1, list.size, "换行/分隔符不能把一条记录劈成多条")
        assertTrue(list.first().title.contains("第一行"))
        assertTrue(list.first().title.contains("收尾"))
    }

    @Test
    fun clearDropsSourceIndexToo() {
        HistoryStore.record(settings, item(itemId = "1"))
        assertEquals(listOf("eh"), HistoryStore.sourcesWithHistory(settings))

        HistoryStore.clear(settings, "eh")
        assertTrue(HistoryStore.watch(settings, "eh").isEmpty())
        assertTrue(HistoryStore.sourcesWithHistory(settings).isEmpty(), "清空后索引里不能再留着这个源")
    }
}
