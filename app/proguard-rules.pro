# Keep the pure-Kotlin crypto/S3 core usable from plain JVM unit tests.
-dontwarn org.json.**
-keep class com.lambdapioneer.argon2kt.** { *; }
