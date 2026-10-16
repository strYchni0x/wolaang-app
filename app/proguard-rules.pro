-keep class de.wolaang.app.** { *; }
-keepattributes *Annotation*

# BRouter (offline routing) — keep the engine intact, it is loaded as a plain jar.
-keep class btools.** { *; }
-dontwarn btools.**
