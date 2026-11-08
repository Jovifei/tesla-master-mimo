package com.matelink.ui.components

import android.app.Application
import android.content.res.Configuration
import com.matelink.R
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class ExternalReviewReadPresentationTest {
    private fun chineseContext() = RuntimeEnvironment.getApplication().let { context ->
        context.createConfigurationContext(Configuration(context.resources.configuration).apply {
            setLocale(Locale.SIMPLIFIED_CHINESE)
        })
    }

    @Test fun unknownUnavailableAndEmptyRemainDifferentForChineseReaders() {
        val context = chineseContext()
        val labels = listOf(R.string.status_unknown, R.string.not_available, R.string.no_data)
            .map(context::getString)
        assertEquals("Unknown, unavailable and an empty dataset are different facts", 3, labels.distinct().size)
    }

    @Test fun extrapolatedRangeDescriptionsIdentifySocAndRatedRangeAsTheirInputs() {
        val context = chineseContext()
        for (key in listOf(R.string.estimated_range_description, R.string.estimated_total_capacity_message)) {
            val label = context.getString(key)
            assertTrue("A linear estimate must name its actual SOC input", label.contains("SOC"))
            assertTrue("A linear estimate must name its rated-range input", label.contains("额定续航"))
        }
    }
}
