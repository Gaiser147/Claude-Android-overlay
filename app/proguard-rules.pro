# Nothing reflective in our own code: JSON is handled as JsonElement trees, no @Serializable classes.

# Shizuku starts the stylus service by class name, in its own process.
-keep class io.github.gaiser147.claudeoverlay.stylus.StylusService { <init>(); *; }
