# 鲸落 R8 保守规则：先只做 shrink、不混淆，保留反射/JNI/第三方运行时相关类
-dontobfuscate
-dontwarn **
-keepclasseswithmembernames class * { native <methods>; }
-keep class com.pilipala.** { *; }
-keep class com.whalefall.movie.** { *; }
-keep class androidx.media3.** { *; }
-keep class org.json.** { *; }
-keep class kotlin.** { *; }
-keep class kotlinx.** { *; }
-keep class androidx.core.** { *; }
-keepclassmembers class **.R$* { public static <fields>; }

# 1.9.50: RecyclerView 等 androidx 组件由 media3 布局 inflate，构造器不能被裁剪
-keep class androidx.** { *; }

# 1.9.51: csp 插件用 DexClassLoader 加载，宿主 com.github.catvod 必须保留，否则 instanceof 失败
-keep class com.github.catvod.** { *; }
-keep class com.github.tvbox.** { *; }
