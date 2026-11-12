# BAC BO AI ANALYZER — proguard rules
# Minification is disabled for the release build (isMinifyEnabled=false);
# these rules only document intent and keep future minified builds safe.

# Keep model classes serialized by Gson (round history persistence).
-keep class com.mybotai.bacboanalyzer.models.** { *; }
-keep class com.mybotai.bacboanalyzer.update.UpdateChecker$LatestRelease { *; }

# ML Kit brings its own keep rules; nothing extra required here.
