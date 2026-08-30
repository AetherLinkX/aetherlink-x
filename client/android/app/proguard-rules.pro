# Hilt
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keepclasseswithmembernames class * { @dagger.hilt.* <methods>; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }

# Kotlin Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# palazikVPN profiles — keep data model for codec
-keep class com.palazik.vpn.data.model.** { *; }
-keep class com.palazik.vpn.data.codec.** { *; }

# Keep VpnService subclass
-keep class com.palazik.vpn.service.palazikVpnService { *; }
