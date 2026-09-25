# sherpa-onnx: the native library creates these classes and reads their fields by name through JNI.
-keep class com.k2fsa.sherpa.onnx.** { *; }

# Enum names are saved to the script library and settings (paragraph kind, camera target, key actions).
-keepclassmembers enum com.olerast.suflyor.** { *; }
