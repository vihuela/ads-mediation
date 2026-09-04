# GMA Next-Gen 1.2.1 preload price reflection. SDK rules keep the obfuscated class names; these
# rules keep the exact members traversed by google_next_gen_preload_reflection_paths.json.

# WorkManager creates its generated Room database implementation through
# reflection. Keep the constructor as a compatibility guard for hosts using
# full-mode R8 or another dependency that downgrades Room's consumer rules.
-keepclassmembers class * extends androidx.room.RoomDatabase {
    <init>();
}

-keepclassmembers class ads_mobile_sdk.gt0 {
    static ads_mobile_sdk.ht0 a();
}
-keepclassmembers class ads_mobile_sdk.fb0 {
    ads_mobile_sdk.vi2 P0;
}
-keepclassmembers interface ads_mobile_sdk.ui2 {
    java.lang.Object get();
}
-keepclassmembers class ads_mobile_sdk.qg2 {
    java.util.LinkedHashMap y;
    java.util.LinkedHashMap z;
    java.util.LinkedHashMap A;
    java.util.LinkedHashMap B;
    java.util.LinkedHashMap C;
}
-keepclassmembers class ads_mobile_sdk.d5 {
    int o;
    java.util.Queue B;
}
-keepclassmembers class ads_mobile_sdk.vg2 {
    ads_mobile_sdk.ug2 a;
}
-keepclassmembers class ads_mobile_sdk.ug2 {
    ads_mobile_sdk.h91 a;
}
-keepclassmembers interface ads_mobile_sdk.h91 {
    ads_mobile_sdk.a2 b();
    com.google.android.libraries.ads.mobile.sdk.common.ResponseInfo getResponseInfo();
}
-keepclassmembers class ads_mobile_sdk.a2 {
    ads_mobile_sdk.i9 m;
}
-keepclassmembers class ads_mobile_sdk.i9 {
    long b;
    com.google.android.libraries.ads.mobile.sdk.common.PrecisionType c;
    java.lang.String d;
}
