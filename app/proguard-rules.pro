# Prototipo: minify desactivado. Estas reglas son preventivas.
-keep class com.george.antirrobo.** { *; }
-keepclassmembers class * extends android.app.admin.DeviceAdminReceiver { *; }
-keepclassmembers class * extends android.accessibilityservice.AccessibilityService { *; }
