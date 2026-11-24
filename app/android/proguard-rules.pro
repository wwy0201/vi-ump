# vi-ump ProGuard / R8 规则
# 说明：release 构建开启 minify 后，kotlinx.serialization 需要保留序列化器；
#       具体规则在接入序列化后按官方建议补充。

-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

# 保留 @Serializable 类的生成序列化器
-keepclassmembers class com.vi.ump.model.** {
    *** Companion;
}
-keepclasseswithmembers class com.vi.ump.model.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Ktor / OkHttp
-dontwarn okhttp3.**
-dontwarn io.ktor.**
-keep class io.ktor.** { *; }
