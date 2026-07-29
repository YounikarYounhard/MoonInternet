# libbox is called through JNI/reflection — keep it whole
-keep class io.nekohasekai.** { *; }
-keep class go.** { *; }
-keepclassmembers class * implements io.nekohasekai.libbox.** { *; }
