# TV 4.4 release 混淆规则
#
# R8 在 API 19 的 release 构建里会报 conscrypt 的 "Missing class"：OkHttp / Conscrypt 里有
# 反射兜底实现，引用了只在部分 ROM 上存在的实现类（com.android.org.conscrypt.*、
# org.apache.harmony.xnet.provider.jsse.*）。它们只在运行期尝试加载，编译期不存在属正常，
# 用 -dontwarn 放行即可；真去 keep 反而会把不存在的类打进包里。
-dontwarn com.android.org.conscrypt.**
-dontwarn org.apache.harmony.xnet.provider.jsse.**
-dontwarn org.conscrypt.**

# OkHttp / Ktor（TV 端同样用 core 的 HttpFetcher）
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn io.ktor.**

# kotlinx.serialization：模型类的生成序列化器必须保留
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class com.vi.ump.model.** {
    *** Companion;
}
-keepclasseswithmembers class com.vi.ump.model.** {
    kotlinx.serialization.KSerializer serializer(...);
}
