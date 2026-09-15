package org.futo.inputmethod.latin.uix.actions.gif

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLEncoder

/**
 * Pure-JVM logic for the GIPHY v1 API: URL building and response parsing.
 * No Android dependencies so this can be unit-tested on the plain JVM
 * (see GiphyApiTest).
 *
 * Coded against https://developers.giphy.com/docs/api/:
 *   GET https://api.giphy.com/v1/gifs/trending?api_key=&limit=&offset=&customer_id=&country_code=
 *   GET https://api.giphy.com/v1/gifs/search?api_key=&q=&limit=&offset=&lang=&customer_id=
 * Response:
 *   { "data": [ {GIF Object}, ... ],
 *     "pagination": { "offset": int, "total_count": int, "count": int },
 *     "meta": { "status": 200, "msg": "OK", "response_id": "..." } }
 * GIF Object: { "id": string, "title": string, "alt_text": string,
 *   "images": { <rendition>: { "url": string, "width"/"height"/"size": string-int } } }
 * Renditions used: fixed_height (200px, grid preview) for thumbnails;
 * downsized (<2MB) for insertion, falling back to downsized_medium (<5MB)
 * then original.
 */
object GiphyApi {
    const val BASE_URL = "https://api.giphy.com"

    const val ENDPOINT_TRENDING = "trending"
    const val ENDPOINT_SEARCH = "search"

    const val LIMIT = 24

    // Documented offset caps: 499 trending, 4999 search. Treat reaching the
    // cap as the end of pagination rather than letting the API error out.
    private const val MAX_OFFSET_TRENDING = 499
    private const val MAX_OFFSET_SEARCH = 4999

    fun buildUrl(
        endpoint: String,
        apiKey: String,
        customerId: String,
        query: String?,
        offset: Int,
        limit: Int = LIMIT,
        language: String? = null,
        countryCode: String? = null
    ): String {
        val sb = StringBuilder(BASE_URL).append("/v1/gifs/").append(endpoint)
        sb.append("?api_key=").append(URLEncoder.encode(apiKey, "UTF-8"))
        sb.append("&limit=").append(limit)
        sb.append("&offset=").append(offset)
        sb.append("&customer_id=").append(URLEncoder.encode(customerId, "UTF-8"))
        if (!query.isNullOrEmpty()) {
            sb.append("&q=").append(URLEncoder.encode(query, "UTF-8"))
        }
        if (!language.isNullOrEmpty()) {
            sb.append("&lang=").append(URLEncoder.encode(language, "UTF-8"))
        }
        if (!countryCode.isNullOrEmpty()) {
            sb.append("&country_code=").append(URLEncoder.encode(countryCode, "UTF-8"))
        }
        return sb.toString()
    }

    fun parseResponse(body: String, endpoint: String = ENDPOINT_SEARCH): GifPage {
        val root = Json.parseToJsonElement(body).jsonObject
        val data = root["data"]?.jsonArray ?: return GifPage(emptyList(), hasNext = false)

        val pagination = root["pagination"] as? JsonObject
        val count = pagination?.get("count")?.jsonPrimitive?.intOrNull ?: data.size
        val offset = pagination?.get("offset")?.jsonPrimitive?.intOrNull ?: 0
        val totalCount = pagination?.get("total_count")?.jsonPrimitive?.intOrNull ?: (offset + count)
        val maxOffset = if (endpoint == ENDPOINT_TRENDING) MAX_OFFSET_TRENDING else MAX_OFFSET_SEARCH

        val nextOffset = offset + count
        val hasNext = count > 0 && nextOffset < totalCount && nextOffset < maxOffset

        val items = data.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val images = obj["images"] as? JsonObject ?: return@mapNotNull null

            // First rendition in the preference list that exists and has a URL
            fun rendition(vararg names: String): JsonObject? =
                names.firstNotNullOfOrNull { name ->
                    (images[name] as? JsonObject)
                        ?.takeIf { !it["url"]?.jsonPrimitive?.contentOrNull.isNullOrEmpty() }
                }

            val full = rendition("downsized", "downsized_medium", "original")
                ?: return@mapNotNull null
            val thumb = rendition("fixed_height", "fixed_height_small", "fixed_width", "downsized")
                ?: full

            // Rendition dims are string-encoded ints in the GIPHY schema
            fun intField(o: JsonObject, key: String): Int =
                o[key]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0

            val description = obj["alt_text"]?.jsonPrimitive?.contentOrNull
                ?: obj["title"]?.jsonPrimitive?.contentOrNull
                ?: ""

            GifItem(
                id = id,
                description = description,
                gifUrl = full["url"]!!.jsonPrimitive.content,
                thumbUrl = thumb["url"]!!.jsonPrimitive.content,
                width = intField(full, "width"),
                height = intField(full, "height")
            )
        }

        return GifPage(items, hasNext)
    }
}
