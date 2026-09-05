package com.matelink.ui.screens.map

import com.matelink.R
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AmapSetupWizardStepTest {
    @Test
    fun navigationMovesAcrossThreeStepsAndStopsAtBothEnds() {
        assertEquals(
            listOf(
                AmapSetupWizardStep.IDENTITY,
                AmapSetupWizardStep.PRIVACY,
                AmapSetupWizardStep.KEY
            ),
            AmapSetupWizardStep.entries.toList()
        )
        assertNull(AmapSetupWizardStep.IDENTITY.previous())
        assertEquals(AmapSetupWizardStep.PRIVACY, AmapSetupWizardStep.IDENTITY.next())
        assertEquals(AmapSetupWizardStep.KEY, AmapSetupWizardStep.PRIVACY.next())
        assertEquals(AmapSetupWizardStep.PRIVACY, AmapSetupWizardStep.KEY.previous())
        assertNull(AmapSetupWizardStep.KEY.next())
    }

    @Test
    fun eachStepCarriesLocalizedCopyAndOneBasedProgressMetadata() {
        assertEquals(
            listOf(
                R.string.amap_setup_step_1_title,
                R.string.amap_setup_step_2_title,
                R.string.amap_setup_step_3_title
            ),
            AmapSetupWizardStep.entries.map { it.titleRes }
        )
        assertEquals(
            listOf(
                R.string.amap_setup_step_1_body,
                R.string.amap_setup_step_2_body,
                R.string.amap_setup_step_3_body
            ),
            AmapSetupWizardStep.entries.map { it.bodyRes }
        )
        assertEquals(listOf(1, 2, 3), AmapSetupWizardStep.entries.map { it.stepNumber })
        assertEquals(1f / 3f, AmapSetupWizardStep.IDENTITY.progressFraction)
        assertEquals(2f / 3f, AmapSetupWizardStep.PRIVACY.progressFraction)
        assertEquals(1f, AmapSetupWizardStep.KEY.progressFraction)
    }

    @Test
    fun privacyConsentUsesAnAccessibleCheckboxRole() {
        val source = File("src/main/java/com/matelink/ui/screens/map/AmapSetupGuideScreen.kt").readText()

        assertTrue(source.contains(".toggleable("))
        assertTrue(source.contains("role = Role.Checkbox"))
        assertTrue(source.contains("Checkbox(checked = uiState.privacyAgreed, onCheckedChange = null)"))
    }
}
