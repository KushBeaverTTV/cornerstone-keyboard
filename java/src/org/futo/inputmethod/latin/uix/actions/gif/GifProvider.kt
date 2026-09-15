package org.futo.inputmethod.latin.uix.actions.gif

/**
 * Provider-agnostic core of the GIF picker. The panel (GifAction) only ever
 * talks to [GifProvider]; GIPHY- and Klipy-specific URL/JSON details live in
 * GiphyApi / KlipyApi and their respective provider implementations.
 *
 * Everything here is pure JVM so it can be unit-tested (see GiphyApiTest,
 * KlipyApiTest, GifProviderTest).
 */

const val GIF_MIME_TYPE = "image/gif"
const val MAX_FULL_GIF_BYTES = 10L * 1024L * 1024L

// Same matching semantics as UixManager.mimeTypeMatches: the editor's
// declared pattern matches the schema if the top-level types are equal and
// the subtype is either "*" or equal.
private fun mimeTypeMatches(mimeTypePattern: String, schema: String): Boolean {
    if (mimeTypePattern == schema) return true
    val patternParts = mimeTypePattern.split('/')
    val schemaParts = schema.split('/')
    if (patternParts.size != 2 || schemaParts.size != 2) return false
    return (patternParts[0] == schemaParts[0] &&
            (patternParts[1] == "*" || patternParts[1] == schemaParts[1]))
}

/**
 * True if the editor's declared contentMimeTypes (EditorInfo.contentMimeTypes)
 * indicate it can receive a GIF via InputConnection.commitContent. A null or
 * empty array means the field accepts no rich content.
 */
fun contentSupportsGif(contentMimeTypes: Array<String>?): Boolean {
    if (contentMimeTypes == null) return false
    return contentMimeTypes.any { mimeTypeMatches(it, GIF_MIME_TYPE) }
}

data class GifItem(
    val id: String,
    val description: String,
    val gifUrl: String,
    val thumbUrl: String,
    val width: Int,
    val height: Int
)

data class GifPage(
    val items: List<GifItem>,
    val hasNext: Boolean
)

enum class GifProviderKind(val wireName: String, val settingValue: String) {
    GIPHY("GIPHY", "giphy"),
    KLIPY("KLIPY", "klipy");

    companion object {
        val DEFAULT = GIPHY

        fun fromSetting(value: String): GifProviderKind =
            entries.firstOrNull { it.settingValue == value } ?: DEFAULT
    }
}

interface GifProvider {
    val kind: GifProviderKind
    val apiKey: String

    val isConfigured: Boolean
        get() = apiKey.isNotBlank()

    /**
     * Builds the request URL. [page] is 1-based; providers map it to their own
     * pagination scheme (Klipy: page/per_page, GIPHY: limit/offset).
     * [language] is an ISO 639-1 code, [countryCode] an ISO 3166 alpha-2 code;
     * either may be null, providers use what they support.
     */
    fun buildUrl(
        query: String?,
        page: Int,
        perPage: Int,
        customerId: String,
        language: String?,
        countryCode: String?
    ): String

    /** Parses a response body. Defensive: entries without usable media are skipped. */
    fun parseResponse(body: String): GifPage
}

class KlipyProvider(override val apiKey: String) : GifProvider {
    override val kind = GifProviderKind.KLIPY

    override fun buildUrl(
        query: String?,
        page: Int,
        perPage: Int,
        customerId: String,
        language: String?,
        countryCode: String?
    ): String = KlipyApi.buildUrl(
        endpoint = if (query == null) KlipyApi.ENDPOINT_TRENDING else KlipyApi.ENDPOINT_SEARCH,
        appKey = apiKey,
        customerId = customerId,
        query = query,
        page = page,
        perPage = perPage,
        locale = countryCode
    )

    override fun parseResponse(body: String): GifPage = KlipyApi.parseResponse(body)
}

class GiphyProvider(override val apiKey: String) : GifProvider {
    override val kind = GifProviderKind.GIPHY

    override fun buildUrl(
        query: String?,
        page: Int,
        perPage: Int,
        customerId: String,
        language: String?,
        countryCode: String?
    ): String = GiphyApi.buildUrl(
        endpoint = if (query == null) GiphyApi.ENDPOINT_TRENDING else GiphyApi.ENDPOINT_SEARCH,
        apiKey = apiKey,
        customerId = customerId,
        query = query,
        offset = (page - 1) * perPage,
        limit = perPage,
        language = language,
        countryCode = countryCode
    )

    override fun parseResponse(body: String): GifPage = GiphyApi.parseResponse(body)
}

data class GifProviderChoice(
    val provider: GifProviderKind,
    /** True when [provider] is not the preferred one but the preferred one has no key */
    val isFallback: Boolean
)

/**
 * Selects which provider to use: the user's preferred provider when it has an
 * API key configured, otherwise the other provider if it has one, otherwise
 * null (panel shows the no-key placeholder).
 */
fun chooseGifProvider(
    preferred: GifProviderKind,
    giphyConfigured: Boolean,
    klipyConfigured: Boolean
): GifProviderChoice? {
    fun configured(kind: GifProviderKind) = when (kind) {
        GifProviderKind.GIPHY -> giphyConfigured
        GifProviderKind.KLIPY -> klipyConfigured
    }

    if (configured(preferred)) return GifProviderChoice(preferred, isFallback = false)

    val other = GifProviderKind.entries.first { it != preferred }
    return if (configured(other)) GifProviderChoice(other, isFallback = true) else null
}
