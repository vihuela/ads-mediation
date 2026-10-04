package com.cashcraft.ads.mediation

/** Compatibility handle for the existing format-specific entry points. New scenes return [AdTask]. */
class AdDisplayOpportunity internal constructor(cancelAction: (() -> Unit)?) : AdTask(cancelAction)
