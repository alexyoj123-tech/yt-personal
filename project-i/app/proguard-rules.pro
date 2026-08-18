# R8 esta desactivado en release (ver app/build.gradle.kts). Este archivo
# queda por si alguna vez se enciende: estas son las reglas minimas que
# harian falta para que dadb (handshake ADB por reflexion/crypto) y OkHttp
# sobrevivan a la ofuscacion.
-keep class dadb.** { *; }
-dontwarn dadb.**
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn org.graalvm.**
