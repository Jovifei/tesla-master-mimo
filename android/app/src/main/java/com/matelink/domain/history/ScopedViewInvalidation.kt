package com.matelink.domain.history

import com.matelink.data.local.HistoryReadScope

/** Only a real published proof can authorize retaining account-scoped UI.
 * Unlike drop(1), this also handles a first Flow emission after an account swap.
 */
internal fun shouldInvalidateScopedView(
    visibleScope: HistoryReadScope?, emittedScope: HistoryReadScope?
): Boolean = visibleScope != null && visibleScope != emittedScope
