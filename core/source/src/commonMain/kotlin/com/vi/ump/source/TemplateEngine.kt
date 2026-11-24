package com.vi.ump.source

/**
 * `{{var}}` 插值。配置里的 URL、POST body、字段 prefix/suffix 都走这一套。
 *
 * 刻意不支持表达式：没有 `{{page+1}}`、没有函数调用。
 * 需要在运行时算出来的东西（页码、游标）由管线算好后放进变量表。
 */
class TemplateEngine {

    private val ref = Regex("""\{\{\s*([A-Za-z0-9_.]+)\s*\}\}""")

    fun render(template: String, vars: Map<String, String>): String =
        ref.replace(template) { match -> vars[match.groupValues[1]] ?: "" }

    /** 模板里出现的变量名，用于加载期警告"引用了不存在的变量" */
    fun variablesOf(template: String): List<String> =
        ref.findAll(template).map { it.groupValues[1] }.toList()
}
