package com.matelink.ui.screens.auth

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST

interface TeslaAuthApi {
    @GET("v1/auth/tesla/start")
    suspend fun startAuthorization(
        @Header("X-JourVolt-Terms-Version") termsVersion: String,
        @Header("X-JourVolt-Privacy-Version") privacyVersion: String
    ): Response<TeslaAuthStartResponse>

    @POST("v1/auth/exchange")
    suspend fun exchange(@Body request: TeslaAuthExchangeRequest): Response<TeslaAuthSessionResponse>

    @POST("v1/session/logout")
    suspend fun logout(@Header("Authorization") authorization: String): Response<Unit>

    @DELETE("v1/account")
    suspend fun deleteAccount(@Header("Authorization") authorization: String): Response<TeslaAccountDeletionResponse>
}

@JsonClass(generateAdapter = true)
data class TeslaAuthStartResponse(
    @Json(name = "authorization_url") val authorizationUrl: String,
    @Json(name = "transaction_id") val transactionId: String,
    @Json(name = "expires_at") val expiresAt: String
)

@JsonClass(generateAdapter = true)
data class TeslaAuthExchangeRequest(
    @Json(name = "ticket") val ticket: String
)

@JsonClass(generateAdapter = true)
data class TeslaAuthSessionResponse(
    @Json(name = "access_token") val accessToken: String,
    @Json(name = "refresh_token") val refreshToken: String,
    @Json(name = "expires_in") val expiresIn: Long,
    val user: TeslaAuthUser
)

@JsonClass(generateAdapter = true)
data class TeslaAuthUser(val id: String)

@JsonClass(generateAdapter = true)
data class TeslaAccountDeletionResponse(
    @Json(name = "status") val status: String,
    @Json(name = "tesla_consent_revoke_url") val teslaConsentRevokeUrl: String? = null
)
