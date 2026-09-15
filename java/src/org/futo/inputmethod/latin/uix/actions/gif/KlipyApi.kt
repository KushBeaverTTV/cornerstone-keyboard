package org.futo.inputmethod.latin.uix.actions.gif

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLEncoder

/**
 * Pure-JVM logic for the Klipy API: URL building and response parsing.
 * No Android dependencies so this can be unit-tested on the plain JVM
 * (see KlipyApiTest). Provider-agnostic pieces (GifItem, GifPage, mime
 * checks, size caps) live in GifProvider.kt.
 *
 * Coded against the native Klipy GIF API:
 *   GET https://api.klipy.com/api/v1/{app_key}/gifs/trending?page=&per_page=&customer_id=&locale=
 *   GET https://api.klipy.com/api/v1/{app_key}/gifs/search?...&q=
 * Response envelope (verified against docs.klipy.com/gifs-api/gifs-trending-api
 * and the official klipy-android-demo-app DTOs):
 *   { "result": true, "data": { "data": [ {item}, ... ], "has_next": bool, "meta": {...} } }
 * Item:
 *   { "id": number, "slug": string, "title": string, "type": "gif"|"ad"|...,
 *     "file": { "hd"|"md"|"sm"|"xs": { "gif": { "url", "width", "height", "size" }, ... } } }
 */
object KlipyApi {
    const val BASE_URL = "https://api.klipy.com"

    const val ENDPOINT_TRENDING = "trending"
    const val ENDPOINT_SEARCH = "search"

    // per_page limits per the docs: search min 8, both max 50, default 24
    const val PER_PAGE = 24

    fun buildUrl(
        endpoint: String,
        appKey: String,
        customerId: String,
        query: String?,
        page: Int,
        perPage: Int = PER_PAGE,
        locale: String? = null
    ): String {
        val sb = StringBuilder(BASE_URL)
            .append("/api/v1/").append(URLEncoder.encode(appKey, "UTF-8"))
            .append("/gifs/").append(endpoint)
        sb.append("?page=").append(page)
        sb.append("&per_page=").append(perPage)
        sb.append("&customer_id=").append(URLEncoder.encode(customerId, "UTF-8"))
        // Only need the GIF renditions; shrinks the response considerably
        sb.append("&format_filter=gif")
        if (!query.isNullOrEmpty()) {
            sb.append("&q=").append(URLEncoder.encode(query, "UTF-8"))
        }
        if (!locale.isNullOrEmpty()) {
            sb.append("&locale=").append(URLEncoder.encode(locale, "UTF-8"))
        }
        return sb.toString()
    }

    fun parseResponse(body: String): GifPage {
        val root = Json.parseToJsonElement(body).jsonObject
        val data = root["data"] as? JsonObject ?: return GifPage(emptyList(), hasNext = false)
        val hasNext = data["has_next"]?.jsonPrimitive?.booleanOrNull ?: false
        val results = data["data"]?.jsonArray ?: return GifPage(emptyList(), hasNext)

        val items = results.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null

            // Responses may include advertisement objects when ads are enabled
            if (obj["type"]?.jsonPrimitive?.contentOrNull == "ad") return@mapNotNull null

            val slug = obj["slug"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val file = obj["file"] as? JsonObject ?: return@mapNotNull null

            fun gifVariant(vararg sizes: String): JsonObject? =
                sizes.firstNotNullOfOrNull { (file[it] as? JsonObject)?.get("gif") as? JsonObject }

            // md is original dimensions at a much higher compression than hd —
            // the right size to hand to another app; sm (~220px) for thumbnails
            val full = gifVariant("md", "hd", "sm", "xs") ?: return@mapNotNull null
            val thumb = gifVariant("sm", "xs", "md", "hd") ?: full

            val gifUrl = full["url"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val thumbUrl = thumb["url"]?.jsonPrimitive?.contentOrNull ?: gifUrl

            val width = full["width"]?.jsonPrimitive?.intOrNull ?: 0
            val height = full["height"]?.jsonPrimitive?.intOrNull ?: 0

            val description = obj["title"]?.jsonPrimitive?.contentOrNull ?: ""

            GifItem(slug, description, gifUrl, thumbUrl, width, height)
        }

        return GifPage(items, hasNext)
    }
}
