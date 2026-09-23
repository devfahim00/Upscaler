# Upscaler ProGuard / R8 rules

# --- ncnn / JNI bridge -------------------------------------------------
# Keep every native* method: they are reached from C++ via JNI name lookup.
-keepclasseswithmembernames class com.devfahim.upscaler.data.engine.EngineBridge {
    native <methods>;
}

# --- Kotlin coroutines / Compose / Hilt are covered by their own -------
# --- consumer rules shipped inside the AARs. ---------------------------

# Room entities are reflected over by Room.
-keep class com.devfahim.upscaler.data.db.** { *; }

# Bengali/English string lookups happen via resources; nothing to keep.
