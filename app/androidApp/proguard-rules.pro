# Keep rules for project-specific reflection/dynamic loading can be added here when needed.
# JNA dispatches libc socket options through JNI and a reflected interface.
-keep class com.sun.jna.** { *; }
-keep interface com.folderspan.service.webrtc.models.SocketOptions { *; }
# JNA 的 AWT 辅助方法仅用于桌面；Android 不提供、也不调用这些类型。
# https://github.com/java-native-access/jna/blob/master/www/FrequentlyAskedQuestions.md#jna-on-android
-dontwarn java.awt.Component
-dontwarn java.awt.GraphicsEnvironment
-dontwarn java.awt.HeadlessException
-dontwarn java.awt.Window
-dontwarn java.lang.management.ManagementFactory
-dontwarn java.lang.management.RuntimeMXBean
-dontwarn javax.el.BeanELResolver
-dontwarn javax.el.ELContext
-dontwarn javax.el.ELResolver
-dontwarn javax.el.ExpressionFactory
-dontwarn javax.el.FunctionMapper
-dontwarn javax.el.ValueExpression
-dontwarn javax.el.VariableMapper
-dontwarn javax.management.MBeanException
-dontwarn javax.management.ReflectionException
-dontwarn javax.security.auth.login.CredentialException
-dontwarn javax.security.auth.login.FailedLoginException
-dontwarn org.ietf.jgss.GSSContext
-dontwarn org.ietf.jgss.GSSCredential
-dontwarn org.ietf.jgss.GSSException
-dontwarn org.ietf.jgss.GSSManager
-dontwarn org.ietf.jgss.GSSName
-dontwarn org.ietf.jgss.Oid
