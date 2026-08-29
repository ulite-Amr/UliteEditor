# The UniFFI-generated Kotlin bindings (editor/build/generated/ffi/kotlin)
# are loaded dynamically by JNA (interface proxies + reflection), so R8
# must not shrink, rename, or optimize them away.
-keep class uniffi.ulite_editor_core.** { *; }

# JNA itself loads native symbols and interfaces reflectively.
-keep class net.java.dev.jna.** { *; }
-dontwarn net.java.dev.jna.**
-keepattributes Signature, InnerClasses, *Annotation*