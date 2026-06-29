package com.matelink.domain.model

/**
 * Helper for determining Tesla battery chemistry and related specifications.
 *
 * Tesla uses different battery chemistries depending on model variant:
 * - LFP (Lithium Iron Phosphate): Used in Standard Range variants (trim_badging "50")
 *   - Lower max DC charging rate (~170 kW)
 *   - Can be charged to 100% daily without degradation
 * - NMC (Nickel Manganese Cobalt): Used in Long Range/Performance variants
 *   - Higher max DC charging rate (~250 kW for Model 3/Y)
 *   - Recommended to keep below 90% for daily use
 */
object BatteryTypeHelper {

    enum class BatteryChemistry {
        LFP,  // Lithium Iron Phosphate (Standard Range)
        NMC   // Nickel Manganese Cobalt (Long Range / Performance)
    }

    /**
     * Determine battery chemistry based on trim_badging.
     *
     * @param trimBadging The trim badging from TeslamateAPI (e.g., "50", "74", "74D", "P74D")
     * @return The battery chemistry type
     */
    fun getBatteryChemistry(trimBadging: String?): BatteryChemistry {
        val normalized = trimBadging?.uppercase()?.trimStart('P') ?: return BatteryChemistry.NMC
        return when (normalized) {
            "50" -> BatteryChemistry.LFP   // Standard Range uses LFP
            else -> BatteryChemistry.NMC   // 74, 74D, etc. use NMC
        }
    }

    /**
     * Get the maximum DC charging power for the battery type.
     *
     * @param trimBadging The trim badging from TeslamateAPI
     * @return Maximum DC power in kW
     */
    fun getMaxDcPowerKw(trimBadging: String?): Int {
        return when (getBatteryChemistry(trimBadging)) {
            BatteryChemistry.LFP -> 170
            BatteryChemistry.NMC -> 250
        }
    }

    /**
     * Check if the battery is LFP chemistry.
     */
    fun isLfp(trimBadging: String?): Boolean {
        return getBatteryChemistry(trimBadging) == BatteryChemistry.LFP
    }

    /**
     * Check if the battery is NMC chemistry.
     */
    fun isNmc(trimBadging: String?): Boolean {
        return getBatteryChemistry(trimBadging) == BatteryChemistry.NMC
    }
}
