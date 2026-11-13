# Pleos SDK callbacks and parcelables cross the vehicle service boundary.
-keep class ai.pleos.playground.** { *; }
-keep interface ai.pleos.playground.** { *; }
-keep class ** implements android.os.Parcelable { public static final android.os.Parcelable$Creator *; }
-keep class com.sun.jna.** { *; }
-keep interface com.sun.jna.** { *; }
-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*
