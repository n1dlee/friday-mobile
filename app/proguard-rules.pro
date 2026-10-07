# R8 rules for the release build.
#
# R8 renames and removes code nothing *seems* to use. That breaks code found
# by name at run time: classes looked up from native code (JNI) and names we
# store. Libraries that ship their own rules (OkHttp, Retrofit, Room,
# WorkManager, Play services, kotlinx.serialization) need nothing here.

-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod

# ── Our own data ─────────────────────────────────────────────────────────────
# Wire formats and Room entities: field names are the format.
-keep class com.friday.ai.data.remote.dto.** { *; }
-keep class com.friday.ai.data.local.entity.** { *; }

# The command's class name is stored as the interaction's type (and synced to
# Lazuri); obfuscated it would read "a", "b"… in the history.
-keepnames class com.friday.ai.domain.model.CommandResult
-keepnames class com.friday.ai.domain.model.CommandResult$*

# ── ONNX Runtime (speaker verification) ──────────────────────────────────────
# libonnxruntime4j_jni.so creates OrtException, TensorInfo, OnnxTensor… with
# FindClass("ai/onnxruntime/…"). Renamed, the first session crashes with
# NoClassDefFoundError — R8 renamed 12 of its 19 classes before this rule.
-keep class ai.onnxruntime.** { *; }

# ── JNA + Vosk (offline wake word) ───────────────────────────────────────────
# JNA's native dispatcher reaches into Pointer, Structure, Memory, callbacks
# and the generated Library proxies by name and by field; Vosk binds its
# native functions through it. R8 renamed 51 of 57 JNA classes before this.
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-keep class org.vosk.** { *; }
# JNA's desktop-only helpers reference AWT, which Android doesn't have.
-dontwarn java.awt.**

# ── libphonenumber ───────────────────────────────────────────────────────────
# Region metadata is loaded as resources next to its classes; kept whole so a
# renamed package can't turn every number into "unknown country".
-keep class com.google.i18n.phonenumbers.** { *; }

-dontwarn okhttp3.**
-dontwarn retrofit2.**
