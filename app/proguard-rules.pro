# The UniFFI-generated Kotlin bindings (editor/build/generated/ffi/kotlin)
# are loaded dynamically by JNA (interface proxies + reflection), so R8
# must not shrink, rename, or optimize them away.
-keep class uniffi.ulite_editor_core.** { *; }

# JNA loads native symbols and interface classes reflectively at runtime.
# NOTE: "net.java.dev.jna" is the Maven group coordinate; the runtime Java
# package is com.sun.jna. Keeping the group coordinate matches nothing and
# lets R8 shrink JNA, which crashes the first native call (NoClassDefFound).
-keep class com.sun.jna.** { *; }
-dontwarn com.sun.jna.**
-keepattributes Signature, InnerClasses, *Annotation*