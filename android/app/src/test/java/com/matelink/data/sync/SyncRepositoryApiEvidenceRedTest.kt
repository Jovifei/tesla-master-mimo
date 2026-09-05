package com.matelink.data.sync

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncRepositoryApiEvidenceRedTest {
    @Test
    fun normalSummaryWritesPersistExactApiEvidenceBeforeRoomUpsert() {
        val source = File("src/main/java/com/matelink/data/sync/SyncRepository.kt").readText()

        assertTrue(source.contains("apiEvidence = HistorySummaryEvidenceCodec.encode(this)"))
    }
}
