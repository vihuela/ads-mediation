# 仅 opt-in Release instrumentation 使用；SDK 及核心价格路径仍执行正常 R8 优化。
-keep,allowoptimization class com.cashcraft.ads.mediation.smoke.NativePreloadProbeAccess {
    public static *;
}
