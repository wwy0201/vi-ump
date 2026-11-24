package com.vi.ump.feature

import com.vi.ump.config.ConfigCompiler
import com.vi.ump.model.ThemeSpec
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText

/** `ThemeSpec.primary` 的默认值：等于它说明作者没显式配色（见下面 [importEntryOf]） */
private val DEFAULT_PRIMARY: String = ThemeSpec().primary

/**
 * 一个可以在首页切换的数据源。
 *
 * [json] 是**原始配置文本**——编译发生在选中之后，所以这里不持有
 * [com.vi.ump.config.CompiledSchema]：配置坏了也能选过去看到错误面板，
 * 而不是在选择器里就把 App 弄崩。
 */
data class SourceEntry(
    val id: String,
    val label: String,
    /** 配置里的 `meta.version`，下拉里显示在行末；没写就是 null */
    val version: String? = null,
    /**
     * 显式配置的源标识色（`#RRGGBB`）：`meta.color` 优先，没写就退到 `theme.primary`（若作者配过）。
     * 两者都没有时这里是 null，由 `SourcePalette` 自动分配一个与其它源差异明显的颜色。
     */
    val color: String? = null,
    /** **原始配置文本**。备份、导出、再导入都用它，不能存编译后的对象 */
    val json: String,
)

/**
 * 一次解析拿两样东西：显示名（`meta.name`，解析不了就用 [fallback]）与版本（`meta.version`，可空）。
 *
 * 合并成一个函数是为了**只解析一次**——下拉里每个源都要显示版本号，若分成 `labelOf` + `versionOf`
 * 两次调用，启动时会把这些配置各编译两遍。
 */
fun labelAndVersionOf(json: String, fallback: String): Pair<String, String?> =
    runCatching { ConfigCompiler.loadAndCompile(json).config.meta }.getOrNull().let { meta ->
        val name = meta?.name?.takeIf { it.isNotBlank() } ?: fallback
        val version = meta?.version?.takeIf { it.isNotBlank() }
        name to version
}

/** 取配置里的 `meta.name` 当显示名；解析不了就用 id 兜底，不抛异常 */
fun labelOf(json: String, fallback: String): String = labelAndVersionOf(json, fallback).first

/**
 * 导入前校验并构造"用户导入源"。
 *
 * 放在 feature 层而不是壳层：校验规则（`ConfigCompiler`）只有这里该碰，
 * 壳层只负责"拿到文件文本 → 调本函数 → 落盘 → 刷新列表"。
 * 校验不过就抛异常，消息里带前两条问题，壳层原样显示——不允许"导入了但列表没变化"这种静默失败。
 */
fun importEntryOf(json: String): SourceEntry {
    // 解析期异常（JSON 语法错 / 未知字段）也要变成一句人能看懂的话：
    // kotlinx 的原始消息带着 "Use 'ignoreUnknownKeys = true'..." 这类给开发者看的建议，
    // 整段透传会把对话框撑满，只取首行原因
    val compiled = runCatching { ConfigCompiler.loadAndCompile(json) }.getOrElse {
        val firstLine = it.message?.lineSequence()?.firstOrNull()?.take(160) ?: "JSON 无法解析"
        throw IllegalArgumentException("配置解析失败：$firstLine")
    }
    if (compiled.hasErrors) {
        val detail = compiled.issues.take(2).joinToString("；") { "[${it.code.code}] ${it.path} ${it.message}" }
        throw IllegalArgumentException("配置校验未通过：$detail")
    }
    val meta = compiled.config.meta
    require(meta.id.isNotBlank()) { "meta.id 为空，无法作为数据源标识" }
    return SourceEntry(
        id = meta.id,
        label = meta.name.ifBlank { meta.id },
        version = meta.version?.takeIf { it.isNotBlank() },
        // 显式色 = meta.color，没写就退到主题主色；主题主色还是默认值（说明作者没配）则留 null，
        // 交给 SourcePalette 自动分配——这样旧配置（只写过 theme 或什么都没写）也能各不相同。
        color = meta.color?.takeIf { it.isNotBlank() }
            ?: compiled.config.theme.primary.takeIf { it.equals(DEFAULT_PRIMARY, ignoreCase = true).not() },
        json = json,
    )
}

/**
 * URL 拉取导入的"拿文本"这一步（M5），两端壳层共用，避免 Android / 桌面各写一套网络细节。
 *
 * 只负责拿文本：落盘与列表刷新仍归壳层。失败抛异常，消息是给用户看的短句。
 * 超时由调用方在 `HttpClient` 上配（壳层统一 20s），这里不再叠一层。
 */
suspend fun fetchConfigText(client: HttpClient, url: String): String {
    require(url.startsWith("http://") || url.startsWith("https://")) { "地址要以 http:// 或 https:// 开头" }
    val response = client.get(url)
    require(response.status.value in 200..299) { "服务器返回 HTTP ${response.status.value}" }
    val text = response.bodyAsText()
    require(text.isNotBlank()) { "地址返回了空内容" }
    return text
}

/** `settings` 里存的 id 列表（`\n` 分隔）→ List。null / 空串 → 空表 */
internal fun String?.idsOrEmpty(): List<String> =
    this?.split('\n')?.map { it.trim() }?.filter { it.isNotBlank() }.orEmpty()

/**
 * 设置页"数据源"栏需要的动作（M5 导入/删除/备份）。
 *
 * 由壳层注入：文件选择器（Android 走 SAF、桌面走 FileDialog/AWT）与落盘都不是 commonMain 能做的事。
 * 默认空实现意味着这一端还没接导入，而不是"点了没反应"——壳层必须给实现。
 */
data class SourceActions(
    /** 点"导入"：壳层打开文件选择器，读入文本、校验、落盘、刷新列表 */
    val onImport: () -> Unit = {},
    /** 确认删除之后：删文件 + 刷新列表（只有"未使用"的源会出现删除按钮） */
    val onDelete: (String) -> Unit = {},
    /**
     * 粘贴导入（M5 第二条通道）：参数是 JSON 文本。
     * 返回 `null` = 成功（壳层负责落盘与刷新列表）；非 null = 失败原因，**显示在粘贴对话框里**，
     * 这样用户改完能直接再点一次，不用关了对话框才知道为什么失败。
     */
    val onImportText: (String) -> String? = { "这一端还没接粘贴导入" },
    /**
     * URL 拉取导入（M5 第三条通道）：挂起函数，网络在壳层的 IO 线程做。
     * 返回 `null` = 成功；非 `null` = 失败原因（拉取失败 / 校验失败），留在对话框里。
     */
    val onImportUrl: suspend (String) -> String? = { "这一端还没接 URL 导入" },
    /**
     * 备份一份源（M5.9）：把**原始**配置（不脱敏，脱敏后就导不回来了）写到"卸载也不清"的位置。
     *
     * Android：目录由用户选一次（SAF `OpenDocumentTree` + 持久化授权），之后所有备份都写进去；
     * 桌面：写 `~/.vi-ump/backup/`。结果（成功/失败/要用户先选目录）由壳层写进 [message] 回显，
     * 所以这里是 `Unit`：备份不改列表，界面不需要接返回值。
     * 为 `null` 表示这一端没接备份，设置页就不显示「备份」按钮。
     */
    val onBackup: (suspend (SourceEntry) -> Unit)? = null,
    /**
     * 导出当前配置（M5.5）：参数是**已脱敏**的 JSON 文本与建议文件名。
     * 壳层负责"存到哪"（Android SAF 另存 / 桌面保存对话框）；null = 这一端还没接。
     */
    val onExport: ((String, String) -> Unit)? = null,
    /** 最近一次导入的结果文案；为 null 就不显示 */
    val message: String? = null,
    val messageIsError: Boolean = false,
)
