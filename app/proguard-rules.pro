# kotlinx.serialization ships its own consumer rules; nothing extra needed for now.

# ML Kit finds its components by class name and creates them through the empty constructor.
# firebase-components keeps only the classes, and R8's full mode then drops the constructors,
# so Gemini Nano looked missing on every phone.
-keep class * implements com.google.firebase.components.ComponentRegistrar { <init>(); }
