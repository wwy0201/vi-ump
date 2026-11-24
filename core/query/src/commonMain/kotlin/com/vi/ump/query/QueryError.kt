package com.vi.ump.query

/**
 * 抽取/编译阶段的错误。错误码与 `docs/03 §13` 对齐，方便与 Python 校验器互相印证。
 */
enum class QueryError(val code: String, val desc: String) {
    MISSING_REQUIRED("E002", "缺少必填项"),
    BAD_ENUM("E003", "枚举值非法"),
    BAD_PATH("E004", "路径或正则语法非法"),
    UNDEFINED_FIELD("E009", "未定义的域字段"),
    DELIMITER_MISS("E011", "分隔符未命中"),
}

class QueryException(
    val error: QueryError,
    message: String,
) : RuntimeException("[${error.code}] $message")

fun badPath(message: String): Nothing =
    throw QueryException(QueryError.BAD_PATH, message)

fun delimiterMiss(message: String): Nothing =
    throw QueryException(QueryError.DELIMITER_MISS, message)
