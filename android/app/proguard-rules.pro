# LiveKit / WebRTC use JNI and reflection
-keep class org.webrtc.** { *; }
-keep class livekit.org.webrtc.** { *; }
-keep class io.livekit.android.** { *; }
-keep class livekit.** { *; }
-dontwarn org.webrtc.**
-dontwarn livekit.org.webrtc.**
-dontwarn org.slf4j.**
