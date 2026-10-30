# R8 no puede ver las llamadas JNI de org.webrtc: si renombra estas clases, el
# DataChannel falla en tiempo de ejecucion con UnsatisfiedLinkError.
-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**
