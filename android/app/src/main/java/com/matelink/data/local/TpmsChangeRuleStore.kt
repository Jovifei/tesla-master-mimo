package com.matelink.data.local
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.preferencesDataStore
import androidx.datastore.preferences.core.*
import dagger.hilt.android.qualifiers.ApplicationContext
import com.matelink.domain.analytics.*
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton
import com.matelink.data.api.models.TpmsDetails
import com.matelink.data.local.entity.TpmsPressureSample
import com.matelink.data.local.entity.TPMS_VERIFIED_COLLECTOR
import java.time.Instant
import java.util.UUID
internal data class TpmsAdmissionIntent(val sample: TpmsPressureSample, val changes: List<TpmsPressureChange>, val rule: TpmsChangeRule)
internal data class TpmsChangeClaim(val change: TpmsPressureChange, val token: String)
private val Context.changeRules by preferencesDataStore("tpms_change_rules")
@Singleton
class TpmsChangeRuleStore internal constructor(private val dataStore: DataStore<Preferences>) {
    @Inject constructor(@ApplicationContext context: Context) : this(context.changeRules)
    fun observe(carId: Int) = dataStore.data.map { p -> TpmsChangeRule(
        p[booleanPreferencesKey("enabled_$carId")] ?: true,
        p[doublePreferencesKey("delta_$carId")] ?: 0.3,
        p[intPreferencesKey("hours_$carId")] ?: 24) }
    suspend fun save(carId: Int, rule: TpmsChangeRule) { require(rule.valid)
         dataStore.edit {
        it[booleanPreferencesKey("enabled_$carId")]=rule.enabled
        it[doublePreferencesKey("delta_$carId")]=rule.deltaBar
        it[intPreferencesKey("hours_$carId")]=rule.windowHours
        TirePosition.entries.forEach { wheel -> it.remove(stringPreferencesKey("pending_${carId}_$wheel")) }
        it.asMap().keys.filter { k -> k.name.startsWith("change_event_${carId}_") || k.name.startsWith("change_claim_${carId}_") }.forEach { k -> it.remove(k) }
    } }
    suspend fun pending(carId: Int): List<TpmsPressureChange> {
        val p=dataStore.data.first()
        return p.asMap().filterKeys { it.name.startsWith("pending_${carId}_") || it.name.startsWith("change_event_${carId}_") }
            .mapNotNull { (key,value) -> decodeChange(value as? String, key.name.substringAfterLast('_')) }
            .distinct().sortedBy { it.observedAt }
    }
    suspend fun record(carId: Int, changes: List<TpmsPressureChange>) { dataStore.edit { p -> changes.forEach { c ->
        p[stringPreferencesKey("pending_${carId}_${c.wheel}")]="${c.before},${c.after},${c.observedAt}"
    } } }
    suspend fun acknowledge(carId: Int, change: TpmsPressureChange) { dataStore.edit { p ->
        if (p[stringPreferencesKey(claimPrefix(carId, change)+"token")] == null) removeEvent(p, carId, change)
    } }
    /** Durable BEFORE Room admission. Production producers serialize admission. */
    internal suspend fun beginIntent(intent: TpmsAdmissionIntent): TpmsAdmissionIntent? {
        require(validSample(intent.sample))
        var result: TpmsAdmissionIntent? = null
        dataStore.edit { p ->
            val s=intent.sample
            val existing = decodeIntent(p[intentKey(s)],s.carId)
            if (existing != null) { result=existing
                 return@edit }
            val sample=s.copy(
                pressureFl=s.pressureFl.takeIf { s.observedAt>(p[watermarkKey(s.carId,TirePosition.FL)]?:0L) },
                pressureFr=s.pressureFr.takeIf { s.observedAt>(p[watermarkKey(s.carId,TirePosition.FR)]?:0L) },
                pressureRl=s.pressureRl.takeIf { s.observedAt>(p[watermarkKey(s.carId,TirePosition.RL)]?:0L) },
                pressureRr=s.pressureRr.takeIf { s.observedAt>(p[watermarkKey(s.carId,TirePosition.RR)]?:0L) })
            if (!hasPressure(sample)) return@edit
            val next=intent.copy(sample=sample,changes=intent.changes.filter { pressure(sample,it.wheel)!=null })
            val key=intentKey(sample)
            result=decodeIntent(p[key],sample.carId) ?: next.also { p[key]=encodeIntent(it) }
        }
        return result
    }
    internal suspend fun recoveryIntents(carId:Int): List<TpmsAdmissionIntent> = dataStore.data.first().asMap()
        .filterKeys { it.name.startsWith("recovery_${carId}_") }.mapNotNull { decodeIntent(it.value as? String,carId) }
        .sortedBy { it.sample.observedAt }
    /** Valid unacknowledged evidence is never evicted for size. Beyond the same
     * 30-day validity gate it cannot notify, and only these owned v2 keys expire.
     * Legacy unknown-origin pending preferences and Room history remain intact.
     */
    internal suspend fun pruneExpiredRecovery(carId:Int,executionNow:Long) {
        val cutoff=executionNow-30L*24*60*60*1000
        dataStore.edit { p ->
            p.asMap().toMap().forEach { (key,value) ->
                val at=when {
                    key.name.startsWith("recovery_${carId}_") -> decodeIntent(value as? String,carId)?.sample?.observedAt
                    key.name.startsWith("change_event_${carId}_") -> decodeChange(value as? String,key.name.substringAfterLast('_'))?.observedAt
                    key.name.startsWith("receipt_${carId}_") || key.name.startsWith("warning_receipt_${carId}_") -> (value as? String)?.substringBefore(',')?.toLongOrNull()
                    key.name.startsWith("change_claim_${carId}_") -> key.name.removePrefix("change_claim_${carId}_").substringBefore('_').toLongOrNull()
                    else -> null
                }
                if(at!=null && at>0 && at<cutoff) p.remove(key)
            }
        }
    }
    /** Invoked only after exact qualified Room receipt match. Queue + completion share one edit. */
    internal suspend fun promoteMatchedIntent(intent:TpmsAdmissionIntent) {
        dataStore.edit { p ->
            val s=intent.sample
             val key=intentKey(s)
            if(p[key]!=encodeIntent(intent)) return@edit
            val applicable=readRule(p,s.carId)==intent.rule && intent.rule.enabled
            TirePosition.entries.forEach { w ->
                val value=pressure(s,w) ?: return@forEach
                val watermark=watermarkKey(s.carId,w)
                if(applicable && p[watermark]!=s.observedAt) {
                    intent.changes.filter { it.wheel==w }.forEach { p[eventKey(s.carId,it)]=encodeChange(it) }
                }
                p[watermark]=maxOf(p[watermark]?:0L,s.observedAt)
                val latest=stringPreferencesKey("receipt_${s.carId}_$w")
                val previous=decodeReceipt(p[latest])
                if(previous==null || s.observedAt>previous.first) p[latest]="${s.observedAt},$value"
            }
            p.remove(key)
        }
    }
    internal suspend fun verifiedPressureReceipts(carId:Int,now:Long):List<TpmsPressureSample> {
        val p=dataStore.data.first()
         val grouped=linkedMapOf<Long,TpmsPressureSample>()
        TirePosition.entries.forEach { w ->
            val (at,value)=decodeReceipt(p[stringPreferencesKey("receipt_${carId}_$w")]) ?: return@forEach
            if(!validReceiptTime(at,now)) return@forEach
            val s=grouped[at] ?: TpmsPressureSample(carId,at,provenance=TPMS_VERIFIED_COLLECTOR)
            grouped[at]=when(w) { TirePosition.FL->s.copy(pressureFl=value)
                 TirePosition.FR->s.copy(pressureFr=value)
                 TirePosition.RL->s.copy(pressureRl=value)
                 TirePosition.RR->s.copy(pressureRr=value) }
        }
        return grouped.values.sortedBy { it.observedAt }
    }
    internal suspend fun rememberVerifiedWarning(carId:Int,details:TpmsDetails,observedAt:Long) {
        require(details.warningSource=="telemetry_mqtt" && details.warningTimeBasis=="collector_received_at")
        val warnings=listOf(details.warningFl,details.warningFr,details.warningRl,details.warningRr)
        val times=listOf(details.warningObservedAtFl,details.warningObservedAtFr,details.warningObservedAtRl,details.warningObservedAtRr)
        dataStore.edit { p -> TirePosition.entries.forEachIndexed { i,w ->
            val v=warnings[i] ?: return@forEachIndexed
            val at=times[i]?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: return@forEachIndexed
            if(at!=observedAt || at<=0) return@forEachIndexed
            val key=stringPreferencesKey("warning_receipt_${carId}_$w")
            val previous=p[key]?.substringBefore(',')?.toLongOrNull()
            if(previous==null || at>previous) p[key]="$at,$v"
        } }
    }
    internal suspend fun verifiedWarningSnapshot(carId:Int,now:Long):TpmsDetails? {
        val p=dataStore.data.first()
         var any=false
        var d=TpmsDetails(warningSource="telemetry_mqtt",warningTimeBasis="collector_received_at")
        TirePosition.entries.forEach { w ->
            val raw=p[stringPreferencesKey("warning_receipt_${carId}_$w")]?.split(',') ?: return@forEach
            val at=raw.getOrNull(0)?.toLongOrNull() ?: return@forEach
            val v=raw.getOrNull(1)?.toBooleanStrictOrNull() ?: return@forEach
            if(!validReceiptTime(at,now)) return@forEach
            val text=Instant.ofEpochMilli(at).toString()
            d=when(w) { TirePosition.FL->d.copy(warningFl=v,warningObservedAtFl=text)
                 TirePosition.FR->d.copy(warningFr=v,warningObservedAtFr=text)
                 TirePosition.RL->d.copy(warningRl=v,warningObservedAtRl=text)
                 TirePosition.RR->d.copy(warningRr=v,warningObservedAtRr=text) }
            any=true
        }
        return d.takeIf { any }
    }
    internal suspend fun claimPending(carId:Int,change:TpmsPressureChange,executionNow:Long):TpmsChangeClaim? {
        var result:TpmsChangeClaim?=null
        dataStore.edit { p ->
            if(p[eventKey(carId,change)]!=encodeChange(change) || !validReceiptTime(change.observedAt,executionNow)) return@edit
            val prefix=claimPrefix(carId,change)
             val started=p[longPreferencesKey(prefix+"started")] ?: 0L
            if(!p[stringPreferencesKey(prefix+"token")].isNullOrBlank() && liveLease(started,executionNow)) return@edit
            val token=UUID.randomUUID().toString()
            p[stringPreferencesKey(prefix+"token")]=token
             p[longPreferencesKey(prefix+"started")]=executionNow
            result=TpmsChangeClaim(change,token)
        }
        return result
    }
    internal suspend fun acknowledgeClaim(carId:Int,claim:TpmsChangeClaim) { dataStore.edit { p ->
        if(p[stringPreferencesKey(claimPrefix(carId,claim.change)+"token")]==claim.token) { removeEvent(p,carId,claim.change)
             clearClaim(p,carId,claim.change) }
    } }
    internal suspend fun releaseClaim(carId:Int,claim:TpmsChangeClaim) { dataStore.edit { p ->
        if(p[stringPreferencesKey(claimPrefix(carId,claim.change)+"token")]==claim.token) clearClaim(p,carId,claim.change)
    } }
    internal suspend fun isClaimCurrent(carId:Int,claim:TpmsChangeClaim):Boolean {
        val p=dataStore.data.first()
        return p[eventKey(carId,claim.change)]==encodeChange(claim.change) &&
            p[stringPreferencesKey(claimPrefix(carId,claim.change)+"token")]==claim.token
    }
    private fun clearClaim(p: MutablePreferences, carId: Int, change: TpmsPressureChange) {
        val prefix = claimPrefix(carId, change)
        p.remove(stringPreferencesKey(prefix + "token"))
        p.remove(longPreferencesKey(prefix + "started"))
    }

    private fun removeEvent(p: MutablePreferences, carId: Int, change: TpmsPressureChange) {
        val keys = listOf(eventKey(carId, change), stringPreferencesKey("pending_${carId}_${change.wheel}"))
        keys.forEach { key -> if (p[key] == encodeChange(change)) p.remove(key) }
    }

    private fun readRule(p: Preferences, id: Int) = TpmsChangeRule(
        p[booleanPreferencesKey("enabled_$id")] ?: true,
        p[doublePreferencesKey("delta_$id")] ?: 0.3,
        p[intPreferencesKey("hours_$id")] ?: 24
    )

    private fun encodeChange(change: TpmsPressureChange) =
        "${change.before},${change.after},${change.observedAt}"

    private fun decodeChange(text: String?, wheel: String): TpmsPressureChange? = runCatching {
        val fields = requireNotNull(text).split(',')
        TpmsPressureChange(TirePosition.valueOf(wheel), fields[0].toDouble(), fields[1].toDouble(), fields[2].toLong())
            .takeIf { it.before.isFinite() && it.after.isFinite() && it.observedAt > 0 }
    }.getOrNull()

    private fun eventKey(id: Int, change: TpmsPressureChange) =
        stringPreferencesKey("change_event_${id}_${change.observedAt}_${change.wheel}")
    private fun claimPrefix(id: Int, change: TpmsPressureChange) =
        "change_claim_${id}_${change.observedAt}_${change.wheel}_"
    private fun watermarkKey(id: Int, wheel: TirePosition) =
        longPreferencesKey("admission_watermark_${id}_$wheel")
    private fun intentKey(sample: TpmsPressureSample) = stringPreferencesKey(
        "recovery_${sample.carId}_${sample.observedAt}_" +
            TirePosition.entries.filter { pressure(sample, it) != null }.joinToString("") { it.name }
    )

    private fun encodeIntent(intent: TpmsAdmissionIntent) = listOf(
        intent.sample.observedAt.toString(),
        TirePosition.entries.joinToString(",") { pressure(intent.sample, it)?.toString() ?: "-" },
        "${intent.rule.enabled},${intent.rule.deltaBar},${intent.rule.windowHours}",
        intent.changes.joinToString(";") { "${it.wheel},${encodeChange(it)}" }
    ).joinToString("|")

    private fun decodeIntent(text: String?, id: Int): TpmsAdmissionIntent? = runCatching {
        val fields = requireNotNull(text).split('|')
        val values = fields[1].split(',').map { if (it == "-") null else it.toDouble() }
        val sample = TpmsPressureSample(id, fields[0].toLong(), values[0], values[1], values[2], values[3],
            provenance = TPMS_VERIFIED_COLLECTOR)
        val rule = fields[2].split(',').let {
            TpmsChangeRule(it[0].toBooleanStrict(), it[1].toDouble(), it[2].toInt())
        }
        val changes = fields[3].split(';').filter { it.isNotEmpty() }.mapNotNull {
            decodeChange(it.substringAfter(','), it.substringBefore(','))
        }
        TpmsAdmissionIntent(sample, changes, rule).takeIf { validSample(sample) && rule.valid }
    }.getOrNull()

    private fun decodeReceipt(text: String?): Pair<Long, Double>? = runCatching {
        val fields = requireNotNull(text).split(',')
        fields[0].toLong() to fields[1].toDouble()
    }.getOrNull()?.takeIf { it.first > 0 && it.second.isFinite() && it.second in 0.0..15.0 }

    private fun validSample(sample: TpmsPressureSample) = sample.provenance == TPMS_VERIFIED_COLLECTOR &&
        sample.observedAt > 0 && hasPressure(sample) && TirePosition.entries.all {
            pressure(sample, it)?.let { value -> value.isFinite() && value in 0.0..15.0 } ?: true
        }
    private fun hasPressure(sample: TpmsPressureSample) = TirePosition.entries.any { pressure(sample, it) != null }
    private fun pressure(sample: TpmsPressureSample, wheel: TirePosition) = when (wheel) {
        TirePosition.FL -> sample.pressureFl
        TirePosition.FR -> sample.pressureFr
        TirePosition.RL -> sample.pressureRl
        TirePosition.RR -> sample.pressureRr
    }
}

internal fun liveLease(startedAt: Long, executionNow: Long) =
    executionNow >= startedAt && executionNow - startedAt < 300_000L
internal fun validReceiptTime(observedAt: Long, executionNow: Long) =
    observedAt > 0 && observedAt <= executionNow && executionNow - observedAt <= 30L * 24 * 60 * 60 * 1000
