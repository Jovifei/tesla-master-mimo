package com.matelink.data.sync

import com.matelink.data.local.entity.DriveDetailAggregate
import com.matelink.data.local.entity.ChargeDetailAggregate
import com.matelink.data.local.entity.SchemaVersion

/** Old preferred-unit aggregates have no same-detail unit proof; keep all other data usable. */
internal fun DriveDetailAggregate.withQualifiedCanonicalTemperatures(): DriveDetailAggregate =
    if (schemaVersion >= SchemaVersion.TEMPERATURE_C) this else copy(
        maxInsideTemp = null, minInsideTemp = null, maxOutsideTemp = null, minOutsideTemp = null)

internal fun ChargeDetailAggregate.withQualifiedCanonicalTemperatures(): ChargeDetailAggregate =
    if (schemaVersion >= SchemaVersion.TEMPERATURE_C) this else copy(maxOutsideTemp = null, minOutsideTemp = null)
