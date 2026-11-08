# 鲸落 R8 规则（第一版：保守 keep，先验证稳定性）
-dontobfuscate
-dontwarn **
-keepclasseswithmembernames class * { native <methods>; }
-keep class com.pilipala.** { *; }
-keep class com.whalefall.movie.** { *; }
-keep class org.json.** { *; }
-keep class kotlin.** { *; }
-keep class kotlinx.** { *; }
-keep class com.frostwire.** { *; }
-keep class org.gudy.** { *; }
-keep class androidx.media3.** { *; }
-keepclassmembers class **.R$* { public static <fields>; }

# CSP 插件通过 DexClassLoader 动态加载，父接口/宿主 API 必须原样保留，
# 否则 plugin instanceof Spider 会失败（表现为「插件父接口与当前宿主不兼容」）。
-keep class com.github.catvod.** { *; }
-keep interface com.github.catvod.** { *; }
-dontwarn com.github.catvod.**

# CSP 插件运行时会直接调用宿主的 okhttp / gson / jsoup 类，
# 这些类在宿主里没被业务代码全部引用，必须整体保留。
-keep class okhttp3.** { *; }
-keep class okio.** { *; }
-keep class com.google.gson.** { *; }
-keep class org.jsoup.** { *; }
-dontwarn okhttp3.**,okio.**,com.google.gson.**,org.jsoup.**
