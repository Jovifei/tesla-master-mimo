# Retrofit
-keepattributes Signature
-keepattributes *Annotation*
-keep class retrofit2.** { *; }
-keepclasseswithmembers class * {
    @retrofit2.http.* <methods>;
}

# Moshi
-keep class com.matelink.data.model.** { *; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**

# AMap native SDK loads mapcore classes by exact name from JNI/reflection.
-keep class com.amap.api.** { *; }
-keep class com.autonavi.** { *; }
-dontwarn com.amap.api.**
-dontwarn com.autonavi.**
-dontwarn com.amap.ams.gnss.GnssSoftLocator
