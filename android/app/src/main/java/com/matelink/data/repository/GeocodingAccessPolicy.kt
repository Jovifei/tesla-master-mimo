package com.matelink.data.repository

import com.matelink.data.local.ConnectionMode

/**
 * External reverse geocoding is permitted for self-hosted installs or whenever
 * user-configured domestic Chinese geocoding (Amap) is active.
 * Cloud mode must not send vehicle coordinates to the unconsented legacy Nominatim API.
 */
internal fun allowsExternalGeocoding(mode: ConnectionMode?, isAmap: Boolean = false): Boolean =
    mode == ConnectionMode.SELF_HOSTED || isAmap

