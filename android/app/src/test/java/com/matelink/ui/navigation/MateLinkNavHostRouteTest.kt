package com.matelink.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

class MateLinkNavHostRouteTest {
    @Test
    fun dashboardJvmNestedRouteStillShowsDashboardTab() {
        val method = Class.forName("com.matelink.ui.navigation.MateLinkNavHostKt")
            .getDeclaredMethod("currentTopLevelDestination", String::class.java)
            .apply { isAccessible = true }

        val result = method.invoke(null, "com.matelink.ui.navigation.Screen\$Dashboard")

        assertEquals(TopLevelDestination.Dashboard, result)
    }

    @Test
    fun obfuscatedRoutePrefixStillShowsDashboardTab() {
        val method = Class.forName("com.matelink.ui.navigation.MateLinkNavHostKt")
            .getDeclaredMethod("currentTopLevelDestination", String::class.java)
            .apply { isAccessible = true }

        val result = method.invoke(null, "a.b.Screen.Dashboard")

        assertEquals(TopLevelDestination.Dashboard, result)
    }
}
