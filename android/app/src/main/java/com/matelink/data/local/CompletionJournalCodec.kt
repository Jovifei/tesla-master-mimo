package com.matelink.data.local

import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import java.security.MessageDigest

/** The key includes origin even where legacy Room car IDs deliberately stay unchanged. */
internal fun completionScopeKey(scope: HistoryReadScope, carId: Int, remoteId: Int): String {
    val components = listOf(scope.source.name, scope.serverIdentity,
        scope.accountNamespace, scope.effectiveApiOrigin, carId.toString(), remoteId.toString())
    val encoded = components.joinToString("") { value ->
        if (value == null) "-1:" else "${value.toByteArray(Charsets.UTF_8).size}:$value"
    }
    return MessageDigest.getInstance("SHA-256").digest(encoded.toByteArray(Charsets.UTF_8))
        .joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
}

@JsonClass(generateAdapter = true)
internal data class CompletionJournalEnvelope(val version: Int, val state: CompletionState)

/** Used by the actual DataStore transaction and by compiled round-trip regressions. */
internal object CompletionJournalCodec {
    private val adapter = Moshi.Builder().build().adapter(CompletionJournalEnvelope::class.java)
    fun decode(raw: String?, nowMillis: Long): CompletionState {
        if (raw == null) return CompletionState(null, nowMillis, emptyList())
        val envelope = try { adapter.fromJson(raw) } catch (_: Exception) { null }
        require(envelope?.version == 2) { "completion_journal_invalid" }
        return requireNotNull(envelope).state
    }
    fun encode(state: CompletionState): String = adapter.toJson(CompletionJournalEnvelope(2, state))
    fun record(
        raw: String?, carId: Int, remoteId: Int, kind: String, scopeKey: String,
        receipts: List<CompletionReceipt>, nowMillis: Long
    ): String = encode(advanceQualifiedCompletionState(
        decode(raw, nowMillis), carId, remoteId, kind, scopeKey, receipts, nowMillis
    ))
    fun consume(raw: String, event: CompletedChargeEvent, system: Boolean, nowMillis: Long): String =
        encode(consumeQualifiedCompletionEvent(decode(raw, nowMillis), event, system))
}
