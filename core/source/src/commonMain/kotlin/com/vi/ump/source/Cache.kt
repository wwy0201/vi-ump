package com.vi.ump.source

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 最近最少使用缓存。`accessOrder = true` 的 LinkedHashMap 天然是 LRU，
 * 但 common 源集没有 `synchronized`，所以读写都用 [Mutex] 串行化。
 */
class LruCache<K, V>(private val capacity: Int) {

    init {
        require(capacity > 0) { "capacity 必须大于 0" }
    }

    private val mutex = Mutex()
    private val map = LinkedHashMap<K, V>(capacity.coerceAtLeast(4), 0.75f, true)

    suspend fun get(key: K): V? = mutex.withLock { map[key] }

    suspend fun put(key: K, value: V) {
        mutex.withLock {
            map[key] = value
            val iterator = map.keys.iterator()
            while (map.size > capacity && iterator.hasNext()) {
                iterator.next()
                iterator.remove()
            }
        }
    }

    suspend fun getOrPut(key: K, factory: suspend () -> V): V {
        mutex.withLock { map[key] }?.let { return it }
        val fresh = factory()
        put(key, fresh)
        return fresh
    }

    suspend fun clear() = mutex.withLock { map.clear() }

    suspend fun size(): Int = mutex.withLock { map.size }
}

/**
 * 同一 key 的并发请求只发一次，其余等待同一个结果。
 *
 * 典型场景：首页 8 个卡片同时触发"详情预热"、或用户连点两次搜索按钮。
 */
class InFlightGate<K, V> {

    private val mutex = Mutex()
    private val jobs = mutableMapOf<K, CompletableDeferred<V>>()

    suspend fun run(key: K, block: suspend () -> V): V {
        val (job, isOwner) = mutex.withLock {
            val existing = jobs[key]
            if (existing != null) {
                existing to false
            } else {
                val fresh = CompletableDeferred<V>()
                jobs[key] = fresh
                fresh to true
            }
        }

        if (!isOwner) return job.await()

        return try {
            val value = block()
            job.complete(value)
            value
        } catch (e: Throwable) {
            job.completeExceptionally(e)
            throw e
        } finally {
            mutex.withLock { jobs.remove(key) }
        }
    }

    suspend fun activeCount(): Int = mutex.withLock { jobs.size }
}
