package com.jewelbox.player.data.net

import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit

/**
 * Builds a [JewelBoxApi] bound to a given base URL. Kept centralized so the whole
 * app has one place that turns "a server URL" into a working API — which is also
 * where mDNS-discovered URLs would flow in later.
 *
 * Timeouts are generous on read: over VPN the link is slower and less stable than
 * on the LAN (see project notes), and this iteration only does small JSON calls.
 */
object ApiClient {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    private val okHttp: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    /**
     * @param rawUrl the user-entered server URL, e.g. "http://192.168.1.20:3001".
     * @throws IllegalArgumentException if the URL is blank or malformed.
     */
    fun create(rawUrl: String): JewelBoxApi {
        val baseUrl = normalize(rawUrl)
        val contentType = "application/json".toMediaType()
        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(okHttp)
            .addConverterFactory(json.asConverterFactory(contentType))
            .build()
            .create(JewelBoxApi::class.java)
    }

    /** Trims, defaults to http:// when no scheme is given, and ensures a trailing "/". */
    fun normalize(rawUrl: String): String {
        var url = rawUrl.trim()
        require(url.isNotEmpty()) { "L'adresse du serveur est vide" }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "http://$url"
        }
        if (!url.endsWith("/")) url += "/"
        return url
    }
}
