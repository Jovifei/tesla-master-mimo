package com.matelink.data.local

import android.app.Application
import android.content.Context
import java.io.ByteArrayInputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,application=Application::class,sdk=[28])
class VehiclePhotoStoreScopeTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val store get() = VehiclePhotoStore(app)
    private fun scope(account: String) = HistoryReadScope(
        HistoryConnectionSource.CLOUD,"cloud",account,"https://api.example")
    private fun vehicle(uid: String) = VehicleContext(
        7, cloudOriginVehicleStableIdentity("alice","https://api.example",uid),
        if(uid=="first") -71 else -72,HistoryConnectionSource.CLOUD,"cloud")

    @Before fun clearPointers() {
        app.getSharedPreferences("vehicle_custom_photos",Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun sameNumericIdAndOriginalUnscopedPhotoNeverCrossAccountsOrUids() {
        val photo = byteArrayOf(1,2,3,4)
        val scoped=store.saveScopedPhoto(scope("alice"), vehicle("first"),
            ByteArrayInputStream(photo))
        assertArrayEquals(photo,scoped.readBytes())
        assertEquals(scoped,store.getScopedPhoto(scope("alice"),vehicle("first")))
        assertNull(store.getScopedPhoto(scope("bob"),vehicle("first")))
        assertNull(store.getScopedPhoto(scope("alice"),vehicle("second")))
        store.clearScopedPhoto(scope("bob"),vehicle("first"))
        assertArrayEquals(photo,store.getScopedPhoto(scope("alice"),vehicle("first"))!!.readBytes())
    }
    @Test fun failedStagedReplacementRetainsPreviousScopedBytes() {
        val original=byteArrayOf(42,43)
        store.saveScopedPhoto(scope("alice"),vehicle("first"),ByteArrayInputStream(original))
        try {
            store.saveScopedPhoto(scope("alice"),vehicle("first"),
                ByteArrayInputStream(ByteArray(8*1024*1024+1)))
            throw AssertionError("oversize replacement must be rejected")
        } catch (_: IllegalStateException) {}
        assertArrayEquals(original,store.getScopedPhoto(scope("alice"),vehicle("first"))!!.readBytes())
    }
}
