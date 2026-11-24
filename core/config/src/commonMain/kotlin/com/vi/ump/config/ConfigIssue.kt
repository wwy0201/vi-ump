package com.vi.ump.config

enum class Severity { ERROR, WARN }

/** 与 `docs/03 §13` 的错误码一一对应 */
enum class IssueCode(val code: String, val title: String) {
    JSON_SYNTAX("E001", "JSON 语法错误"),
    MISSING_REQUIRED("E002", "缺少必填项"),
    BAD_ENUM("E003", "枚举值非法"),
    BAD_PATH("E004", "路径或正则语法非法"),
    MISSING_REF("E005", "引用不存在"),
    CYCLE("E006", "循环引用"),
    TOO_BIG("E007", "体积或深度超限"),
    NO_MIGRATION("E008", "版本无迁移路径"),
    UNDEFINED_FIELD("E009", "未定义的域字段"),
    BAD_SCHEME("E010", "URL scheme 非法"),
    DELIMITER_MISS("E011", "分隔符未命中"),
    WARN_REQUIRED("W001", "required 缺失提示"),
    WARN_IMAGE_FIELD("W002", "card.image 指向未定义字段"),
    WARN_PLATFORM("W003", "运行期平台能力不足"),
}

data class ConfigIssue(
    val code: IssueCode,
    val path: String,
    val message: String,
    val severity: Severity = Severity.ERROR,
) {
    override fun toString(): String = "[${code.code}] $path: $message"
}

class ConfigException(
    val issue: ConfigIssue,
) : RuntimeException(issue.toString())

class IssueCollector {
    private val list = mutableListOf<ConfigIssue>()

    fun error(code: IssueCode, path: String, message: String) {
        list += ConfigIssue(code, path, message, Severity.ERROR)
    }

    fun warn(code: IssueCode, path: String, message: String) {
        list += ConfigIssue(code, path, message, Severity.WARN)
    }

    fun result(): List<ConfigIssue> = list.toList()

    val hasError: Boolean get() = list.any { it.severity == Severity.ERROR }
}
