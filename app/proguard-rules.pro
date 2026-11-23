# Compose and AndroidX ship their own rules.

# Chaquopy: Python reaches Java/Kotlin through reflection (its AAR also ships
# consumer rules; these are a safe superset).
-keep class com.chaquo.python.** { *; }
-dontwarn com.chaquo.python.**

# The object Python calls back into (progress, stop flag, log lines).
-keep class com.vibecoder.vibedownloader.engine.PySink { *; }

# OkHttp 4 ships its own rules; these only silence optional TLS providers.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
