package com.matelink.data.repository

import com.matelink.data.local.ConnectionMode

/**
 * External reverse geocoding is retained for self-hosted installs only.
 * Cloud mode must not send vehicle coordinates to the legacy Nominatim API.
 */
internal fun allowsExternalGeocoding(mode: ConnectionMode?): Boolean =
    mode == ConnectionMode.SELF_HOSTED
