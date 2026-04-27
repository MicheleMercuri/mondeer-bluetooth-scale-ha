# Default ProGuard rules. Most rules already provided by AGP and Kotlin.
# Lasciamo HiveMQ MQTT client al di fuori dell'obfuscation.
-keep class com.hivemq.client.** { *; }
-dontwarn com.hivemq.**
# Health Connect
-keep class androidx.health.connect.** { *; }
