# JNI looks up the engine's native methods by class and method name (Java_dev_abhay_assistant_engine_Needle_*).
-keep class dev.abhay.assistant.engine.Needle { native <methods>; }

# ML Kit finds its components through registrars named in the manifest (MlKitComponentDiscoveryService's
# meta-data) and creates each by reflection, with its no-argument constructor. firebase-components'
# own rule (-keep class * implements ComponentRegistrar) keeps no members, which in R8's full mode
# (AGP 9's default) drops the constructor: the registrar is skipped, MlKitContext.get(SharedPrefManager)
# is null, and every Gemini Nano status check threw (release 0.1, 2026-10-30). scripts/check-release.sh
# checks the built APK for this.
-keep class * implements com.google.firebase.components.ComponentRegistrar { public <init>(); }

# Gemini Nano's typed output (ContentRewrite, 0.2): ML Kit finds each @Generable class's schema
# through META-INF/services (GenerableProvider, written by KSP) and builds the class from the answer
# by reflection over its constructor and fields, which R8 can't see; keep both whole.
# The schema library itself is small: kept as it is, so the services file keeps its own name.
-keep class com.google.mlkit.genai.schema.** { *; }
-keep class * implements com.google.mlkit.genai.schema.guided.GenerableProvider { *; }
-keep @com.google.mlkit.genai.schema.annotations.Generable class * { *; }
