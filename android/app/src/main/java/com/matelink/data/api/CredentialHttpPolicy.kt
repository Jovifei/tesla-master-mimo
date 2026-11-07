package com.matelink.data.api

import okhttp3.OkHttpClient

/** Keep tickets, refresh bodies, and Bearer headers on the configured API origin. */
internal fun credentialHttpClientBuilder(): OkHttpClient.Builder = OkHttpClient.Builder()
    .followRedirects(false)
    .followSslRedirects(false)
