# Constructed and invoked from JNI by name; R8 cannot see these uses.
-keep class dev.taracore.engine.NativeLoadResult { <init>(...); }
-keep class dev.taracore.engine.GenStats { <init>(...); }
-keep interface dev.taracore.engine.TokenListener { *; }
-keepclasseswithmembernames class dev.taracore.engine.LlamaEngine {
    native <methods>;
}
