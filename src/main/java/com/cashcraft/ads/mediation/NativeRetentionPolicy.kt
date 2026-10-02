package com.cashcraft.ads.mediation

/** 保留仅对已验证能安全暂停、恢复的来源生效；其他来源释放降级。 */
enum class NativeRetentionPolicy {
    DESTROY_ON_HIDE,
    RETAIN_WHILE_PAGE_ALIVE,
}
