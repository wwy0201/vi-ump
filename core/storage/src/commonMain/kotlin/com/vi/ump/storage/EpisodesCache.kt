package com.vi.ump.storage

/**
 * 选集缓存：把一部剧的「集号 / 状态 / 播放地址」整块存下来，
 * 下次进详情页直接读，不再打那 40~50 次 `getinfo`（实测 1.5~2 秒）。
 *
 * 持久化位置与 `ProgressStore` 一致：
 *  - Android：SharedPreferences(`ump_episodes`)
 *  - 桌面端：java.util.prefs(`com/vi/ump/episodes`)
 *
 * **过期语义（M4.4 补）**：难度在于"缓存永不失效"会把站点改版后的脏数据一直钉在那里 ——
 * 播放地址里的 `vkey` 本身有时效（几小时），选集结构也可能变。所以设 **6 小时 TTL**：
 *  - 写入时记 `key#ts`（epoch 毫秒）；
 *  - 读取时 `now - ts > 6h` 或**没有 ts**（老版本写的）都视为过期，删掉重拉。
 *    缺 ts 一律算过期是刻意的：否则存量数据永远不过期，"TTL"等于没加。
 *  - 时间戳缺失/时钟回拨（`now - ts <= 0`）按"刚刚写入"处理不删，避免机器时间被改后集体失效。
 *
 * 为什么不复用 `UmpSession.feedCache`（那是会话级内存缓存，切源/退 App 即失效）：
 * 列表可以容忍"每次开 App 重拉"，选集不能 —— 它是进入某一集的必经路径。
 *
 * 存的是**已渲染完的 Track 列表**(JSON)，不是原始响应：
 * 原始响应还要再跑一遍变换链与拆组规则，缓存它等于只省了网络、没省计算。
 */
interface EpisodesCache {

    /** 没有缓存 / 已过期返回 null —— 和 `ProgressStore.position` 同一套"缺失即 null"的约定 */
    fun read(key: String, ttlMs: Long = EPISODES_CACHE_TTL_MS): String?

    /** 只写非空结果：取数失败不覆盖上一次的好数据 */
    fun write(key: String, value: String)

    fun clear()
}

/** 选集缓存有效期：6 小时。`vkey` 时效、站点选集结构变化都由它兜底 */
const val EPISODES_CACHE_TTL_MS: Long = 6L * 60L * 60L * 1000L

/** Android / JVM 两端共用：时间戳键后缀，与切片键 `key#N`、片数键 `key#n` 区分开 */
internal const val EPISODES_CACHE_TS_SUFFIX = "#ts"

/**
 * 平台注入失败时退回进程内存：宁可慢一点重拉，也不能因为存储不可用就让选集消失。
 *
 * 这里**不做 TTL**：它的生命周期就是这个进程（平台存储不可用时的兜底），
 * 进程内 6 小时几乎不会被撞上，加时间戳只是徒增复杂度。
 */
class InMemoryEpisodesCache : EpisodesCache {
    private val map = mutableMapOf<String, String>()
    override fun read(key: String, ttlMs: Long): String? = map[key]
    override fun write(key: String, value: String) {
        map[key] = value
    }

    override fun clear() {
        map.clear()
    }
}

/**
 * 缓存键 = 源 + 条目。
 *
 * 不带集号：缓存的本来就是"整部剧的全部集"，不是某一集。
 * 不带配置版本：站点配置换了之后选集结构可能变，但那种情况需要人工清缓存
 * （M5 的"刷新数据"按钮），自动按版本失效会变成"每次改配置用户都要重拉"。
 */
fun episodesCacheKey(sourceId: String, itemId: String): String {
    val src = sourceId.ifBlank { "?" }
    return "$src|$itemId"
}

/** 平台提供实现：Android 用 SharedPreferences，桌面端用 java.util.prefs */
expect fun createEpisodesCache(): EpisodesCache
