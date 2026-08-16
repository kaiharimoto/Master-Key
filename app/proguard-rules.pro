# The JNI entry points are called by name from synth.cpp, so R8 has no way to
# see they are used. Stripping or renaming them breaks audio at runtime with a
# vague UnsatisfiedLinkError rather than a build error.
-keep class dev.kaiharimoto.masterkey.audio.NativeSynth {
    native <methods>;
    *;
}
-keepclasseswithmembernames class * {
    native <methods>;
}

# The score bridge is invoked from JavaScript by name.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# ktmidi reads MIDI structures reflectively in places.
-keep class dev.atsushieno.ktmidi.** { *; }

# Room generates implementations that reference entity fields by name.
-keep class dev.kaiharimoto.masterkey.data.** { *; }

# Keep line numbers so a crash report from the tablet is actually readable.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# OkHttp ships these for platforms we don't target.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
