# JNA dispatches libc socket options through JNI and a reflected interface.
-keep class com.sun.jna.** { *; }
-keep interface com.folderspan.service.webrtc.models.SocketOptions { *; }
