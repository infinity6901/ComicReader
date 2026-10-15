# Keep junrar / commons-compress reflection-free but generic
-dontwarn org.apache.commons.compress.**
-dontwarn org.tukaani.xz.**
-keep class com.github.junrar.** { *; }
-keep class org.apache.commons.compress.** { *; }
