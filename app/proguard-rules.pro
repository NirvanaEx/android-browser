# GeckoView keeps a lot of reflective code; Mozilla's consumer rules are bundled
# in the AAR but we add a safety net here.
-keep class org.mozilla.geckoview.** { *; }
-keep class org.mozilla.gecko.** { *; }
-keep class mozilla.components.** { *; }

# Kotlin coroutines internals.
-keepclassmembers class kotlinx.coroutines.** { *; }
