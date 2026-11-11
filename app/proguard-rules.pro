# OG Reader — R8 rules for the published (release) build.
#
# Shrinking only: unused code is removed, nothing is renamed. The source is public, so renaming
# hides nothing, and readable names keep error messages (e.javaClass.simpleName) and stack traces
# as they were in the debug builds published up to 1.5.
-dontobfuscate
-keepattributes SourceFile,LineNumberTable,Signature,InnerClasses,EnclosingMethod,*Annotation*

# ── 🗄️ 파일 서버 libraries ──────────────────────────────────────────────────────────────
# These find their classes by name at run time (JCA provider tables, service loaders, message
# bus handlers), which R8 cannot see: a missing class would only show as a failed connection on
# the phone. They are kept whole; the saving comes from everything else.
-keep class org.bouncycastle.** { *; }
-keep class net.schmizz.** { *; }
-keep class com.hierynomus.** { *; }
-keep class net.i2p.crypto.eddsa.** { *; }
-keep class net.engio.mbassy.** { *; }
-keep class org.apache.commons.net.** { *; }
-keep class org.slf4j.** { *; }
-keepclassmembers class * { @net.engio.mbassy.listener.Handler <methods>; }

# Optional parts of those libraries that Android does not have (never reached on a phone).
-dontwarn org.bouncycastle.**
-dontwarn net.schmizz.**
-dontwarn com.hierynomus.**
-dontwarn net.i2p.crypto.**
-dontwarn net.engio.mbassy.**
-dontwarn org.apache.commons.net.**
-dontwarn org.slf4j.**
-dontwarn javax.naming.**
-dontwarn javax.el.**
-dontwarn javax.security.sasl.**
-dontwarn javax.security.auth.**
-dontwarn org.ietf.jgss.**
-dontwarn sun.security.**
-dontwarn sun.misc.**
-dontwarn com.jcraft.jzlib.**
-dontwarn lombok.**

# ── the app itself ────────────────────────────────────────────────────────────────────────
# kotlinx.serialization, Room, WorkManager, Media3 and Compose ship their own rules. The app's
# own classes are kept too: settings, the download queues and backups are stored as JSON by
# class and field name, and the reader's WebView calls into @JavascriptInterface methods.
-keep class com.oasisreader.app.** { *; }
