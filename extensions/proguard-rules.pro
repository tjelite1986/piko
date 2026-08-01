-dontobfuscate
-dontoptimize
-keepattributes *
-keep class app.morphe.** {
  *;
}
-keep class app.revanced.** {
  *;
}
-keep class com.google.** {
  *;
}
-keep class com.jcraft.jsch.** {
  *;
}
# JSch picks its cipher/KEX implementations by class name at runtime, so the whole
# package has to survive shrinking. Its optional integrations do not ship with the
# extension and are never reached on Android: Pageant needs JNA (Windows only), the
# log4j logger needs log4j, and the "bc" algorithm set needs BouncyCastle, while the
# JCE set the platform already provides is what gets configured.
-dontwarn com.sun.jna.**
-dontwarn org.apache.logging.log4j.**
-dontwarn org.bouncycastle.**
-dontwarn org.slf4j.**
-dontwarn org.ietf.jgss.**
-dontwarn org.newsclub.net.unix.**
