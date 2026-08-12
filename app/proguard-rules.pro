# Keep the JNI bridge into libtermux.so: these methods are resolved by name from
# native code, so shrinking or renaming them breaks every terminal session.
-keepclasseswithmembernames,includedescriptorclasses class com.termux.terminal.JNI {
    native <methods>;
}
-keep class com.termux.terminal.** { *; }

# Shizuku's provider is compileOnly and resolved reflectively at runtime.
-dontwarn rikka.shizuku.**

# JSch pulls in optional JCE/agent-proxy classes that are not present on Android.
-dontwarn com.jcraft.jsch.**
-dontwarn org.slf4j.**
