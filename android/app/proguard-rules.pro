# 林曦日记 ProGuard/R8 规则

# 数据模型（org.json 反射）
-keep class com.linxi.diary.data.** { *; }

# miuix 组件
-keep class top.yukonga.miuix.kmp.** { *; }

# material-kolor 动态取色
-keep class com.materialkolor.** { *; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }

# Coil 3（相册图片加载）
# Coil 通过 ServiceLoader 与反射装配 fetcher/decoder，混淆后会静默降级为"图全都加载不出来"，
# 且 release 才复现、debug 正常，极难排查。
-keep class coil3.** { *; }
-dontwarn coil3.**
-keep class * implements coil3.util.DecoderServiceLoaderTarget { *; }
-keep class * implements coil3.util.FetcherServiceLoaderTarget { *; }

# AndroidX ExifInterface（读拍摄时间与方向）
-keep class androidx.exifinterface.media.** { *; }
-dontwarn androidx.exifinterface.media.**

# kotlinx.coroutines：StateFlow/Flow 的内部类被裁掉会导致状态同步静默失效
-dontwarn kotlinx.coroutines.**
-keep class kotlinx.coroutines.android.AndroidDispatcherFactory { *; }

# Optional official Cubism Java adapter.  The default build has no matching
# classes, so these rules are inert until CUBISM_CORE_AAR + Framework are
# explicitly supplied.  Keep the reflection factory and JNI-facing framework
# names stable in release builds.
-keep class com.linxi.diary.live2d.** { *; }
-keep class com.live2d.sdk.cubism.** { *; }
