# Bouncy Castle reaches for algorithm implementations by name in places, so shrinking its
# provider and crypto trees can turn a working cipher into a runtime failure. For a crypto
# app that failure mode is silent data loss, so keep the library and let R8 shrink the rest.
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# Bouncy Castle compiles against JDK APIs that Android does not ship.
-dontwarn javax.naming.**
-dontwarn java.beans.**

# Keep the crypto surface intact even though nothing reflects on it today; a broken rename
# here is not something a crash report would make obvious.
-keep class com.pocketgpg.crypto.** { *; }
