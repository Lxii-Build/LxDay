# 林曦日记 ProGuard/R8 规则

# 数据模型（org.json 反射）
-keep class com.linxi.diary.data.** { *; }

# backdrop 液态玻璃库（AGSL shader 字符串 + GraphicsLayer 反射）
-keep class com.kyant.backdrop.** { *; }
-keep class com.kyant.shapes.** { *; }

# miuix 组件
-keep class top.yukonga.miuix.kmp.** { *; }

# material-kolor 动态取色
-keep class com.materialkolor.** { *; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
