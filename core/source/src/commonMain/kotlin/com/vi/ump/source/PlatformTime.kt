package com.vi.ump.source

/** 当前毫秒时间戳（a_bogus 内嵌时间，签名有效期只有几分钟，必须实时） */
internal expect fun nowMillis(): Long
