package org.futo.inputmethod.latin.uix.actions.gif

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for the GIPHY v1 API logic (URL building, response parsing
 * incl. pagination math, editor mime-type support checks). No Android
 * dependencies.
 *
 * Response fixtures mirror the schema documented at
 * https://developers.giphy.com/docs/api/ — data[] of GIF Objects with
 * images{<rendition>{url,width,height,size}} where dims are string-encoded
 * ints, plus pagination{offset,total_count,count}.
 */
class GiphyApiTest {

    // ---------- contentSupportsGif (shared helper in GifProvider.kt) ----------

    @Test
    fun `null mime types means no rich content support`() {
        assertFalse(contentSupportsGif(null))
    }

    @Test
    fun `empty mime types means no rich content support`() {
        assertFalse(contentSupportsGif(emptyArray()))
    }

    @Test
    fun `exact image gif is supported`() {
        assertTrue(contentSupportsGif(arrayOf("image/gif")))
    }

    @Test
    fun `image wildcard is supported`() {
        assertTrue(contentSupportsGif(arrayOf("image/*")))
    }

    @Test
    fun `gif among several mime types is supported`() {
        assertTrue(contentSupportsGif(arrayOf("image/png", "image/gif", "image/jpeg")))
    }

    @Test
    fun `other image types alone are not supported`() {
        assertFalse(contentSupportsGif(arrayOf("image/png", "image/jpeg")))
    }

    @Test
    fun `non-image types are not supported`() {
        assertFalse(contentSupportsGif(arrayOf("text/plain", "video/mp4")))
    }

    @Test
    fun `malformed entries are ignored not fatal`() {
        assertFalse(contentSupportsGif(arrayOf("gif", "image", "imagegif")))
        assertTrue(contentSupportsGif(arrayOf("garbage", "image/gif")))
    }

    // ---------- buildUrl ----------

    @Test
    fun `trending url carries key limit offset customer`() {
        val url = GiphyApi.buildUrl(
            endpoint = GiphyApi.ENDPOINT_TRENDING,
            apiKey = "KEY123",
            customerId = "cid-123",
            query = null,
            offset = 0,
            limit = 24
        )
        assertTrue(url.startsWith("https://api.giphy.com/v1/gifs/trending?"))
        assertTrue(url.contains("api_key=KEY123"))
        assertTrue(url.contains("limit=24"))
        assertTrue(url.contains("offset=0"))
        assertTrue(url.contains("customer_id=cid-123"))
        assertFalse(url.contains("&q="))
        assertFalse(url.contains("&lang="))
        assertFalse(url.contains("&country_code="))
    }

    @Test
    fun `search url encodes query language and country`() {
        val url = GiphyApi.buildUrl(
            endpoint = GiphyApi.ENDPOINT_SEARCH,
            apiKey = "K",
            customerId = "c",
            query = "thumbs up & down",
            offset = 48,
            limit = 24,
            language = "en",
            countryCode = "us"
        )
        assertTrue(url.startsWith("https://api.giphy.com/v1/gifs/search?"))
        assertTrue(url.contains("offset=48"))
        assertTrue(url.contains("q=thumbs+up+%26+down"))
        assertTrue(url.contains("lang=en"))
        assertTrue(url.contains("country_code=us"))
    }

    @Test
    fun `blank query is omitted`() {
        val url = GiphyApi.buildUrl(GiphyApi.ENDPOINT_TRENDING, "K", "c", "", 0)
        assertFalse(url.contains("&q="))
    }

    // ---------- parseResponse ----------

    private fun rendition(url: String, w: Int, h: Int, size: Int) =
        """{ "url": "$url", "width": "$w", "height": "$h", "size": "$size" }"""

    private fun gifObject(id: String, title: String, images: String) =
        """{ "id": "$id", "title": "$title", "type": "gif", "images": { $images } }"""

    private fun response(items: String, offset: Int, count: Int, total: Int) = """
        {
          "data": [ $items ],
          "pagination": { "offset": $offset, "count": $count, "total_count": $total },
          "meta": { "status": 200, "msg": "OK", "response_id": "abc123" }
        }
    """.trimIndent()

    private val fullImages = """
        "original": ${rendition("https://media1.giphy.com/media/x/giphy.gif", 480, 270, 3500000)},
        "downsized": ${rendition("https://media1.giphy.com/media/x/giphy-downsized.gif", 480, 270, 1800000)},
        "downsized_medium": ${rendition("https://media1.giphy.com/media/x/giphy-downsized-medium.gif", 480, 270, 4500000)},
        "fixed_height": ${rendition("https://media1.giphy.com/media/x/200.gif", 356, 200, 32381)},
        "fixed_height_small": ${rendition("https://media1.giphy.com/media/x/100.gif", 178, 100, 12000)}
    """.trimIndent()

    @Test
    fun `parses items with urls string dims and titles`() {
        val page = GiphyApi.parseResponse(
            response(gifObject("abc123", "Happy Dance", fullImages), offset = 0, count = 1, total = 100)
        )

        assertEquals(1, page.items.size)
        assertTrue(page.hasNext)

        val item = page.items[0]
        assertEquals("abc123", item.id)
        assertEquals("Happy Dance", item.description)
        // downsized (<2MB) is the insertion rendition, fixed_height the thumbnail
        assertEquals("https://media1.giphy.com/media/x/giphy-downsized.gif", item.gifUrl)
        assertEquals("https://media1.giphy.com/media/x/200.gif", item.thumbUrl)
        assertEquals(480, item.width)
        assertEquals(270, item.height)
    }

    @Test
    fun `falls back across rendition chains`() {
        val images = """
            "original": ${rendition("https://media1.giphy.com/media/y/giphy.gif", 300, 200, 900000)},
            "fixed_height_small": ${rendition("https://media1.giphy.com/media/y/100.gif", 150, 100, 8000)}
        """.trimIndent()
        val page = GiphyApi.parseResponse(
            response(gifObject("y", "Fallback", images), offset = 0, count = 1, total = 1)
        )

        val item = page.items.single()
        // no downsized/downsized_medium -> original for insertion
        assertEquals("https://media1.giphy.com/media/y/giphy.gif", item.gifUrl)
        // no fixed_height -> fixed_height_small for thumbnail
        assertEquals("https://media1.giphy.com/media/y/100.gif", item.thumbUrl)
        assertFalse(page.hasNext)
    }

    @Test
    fun `renditions without a url are skipped within the chain`() {
        // downsized_small is mp4-only; an entry with an empty url must not win
        val images = """
            "downsized": { "url": "", "width": "300", "height": "200", "size": "100" },
            "downsized_medium": ${rendition("https://media1.giphy.com/media/z/medium.gif", 300, 200, 4000000)},
            "fixed_height": ${rendition("https://media1.giphy.com/media/z/200.gif", 300, 200, 50000)}
        """.trimIndent()
        val page = GiphyApi.parseResponse(
            response(gifObject("z", "NoUrl", images), offset = 0, count = 1, total = 1)
        )
        assertEquals("https://media1.giphy.com/media/z/medium.gif", page.items.single().gifUrl)
    }

    @Test
    fun `entries without usable media are skipped`() {
        val items = gifObject("good", "Good", fullImages) + "," +
                """{ "id": "noimages", "title": "Broken" }""" + "," +
                """{ "title": "NoId", "images": { $fullImages } }"""
        val page = GiphyApi.parseResponse(response(items, offset = 0, count = 3, total = 3))
        assertEquals(listOf("good"), page.items.map { it.id })
    }

    @Test
    fun `pagination math stops at total count`() {
        val page = GiphyApi.parseResponse(
            response(gifObject("a", "A", fullImages), offset = 96, count = 24, total = 120)
        )
        assertFalse(page.hasNext)
    }

    @Test
    fun `pagination math continues mid-list`() {
        val page = GiphyApi.parseResponse(
            response(gifObject("a", "A", fullImages), offset = 24, count = 24, total = 120)
        )
        assertTrue(page.hasNext)
    }

    @Test
    fun `pagination stops at the documented offset cap`() {
        // Search caps offset at 4999: even with more total results, we stop
        val page = GiphyApi.parseResponse(
            response(gifObject("a", "A", fullImages), offset = 4980, count = 24, total = 100000),
            endpoint = GiphyApi.ENDPOINT_SEARCH
        )
        assertFalse(page.hasNext)
    }

    @Test
    fun `trending uses the tighter offset cap`() {
        val page = GiphyApi.parseResponse(
            response(gifObject("a", "A", fullImages), offset = 480, count = 24, total = 100000),
            endpoint = GiphyApi.ENDPOINT_TRENDING
        )
        assertFalse(page.hasNext)
    }

    @Test
    fun `zero count never paginates`() {
        val page = GiphyApi.parseResponse(response("", offset = 0, count = 0, total = 500))
        assertTrue(page.items.isEmpty())
        assertFalse(page.hasNext)
    }

    @Test
    fun `missing data yields empty page`() {
        // Synthetic error response: meta.status=200, empty response_id, no payload
        val page = GiphyApi.parseResponse("""{"meta": {"status": 200, "msg": "OK", "response_id": ""}}""")
        assertTrue(page.items.isEmpty())
        assertFalse(page.hasNext)
    }

    @Test
    fun `thumbnail falls back to full rendition when no preview exists`() {
        val images = """
            "downsized": ${rendition("https://media1.giphy.com/media/w/d.gif", 100, 100, 50000)}
        """.trimIndent()
        val page = GiphyApi.parseResponse(
            response(gifObject("w", "W", images), offset = 0, count = 1, total = 1)
        )
        val item = page.items.single()
        assertEquals("https://media1.giphy.com/media/w/d.gif", item.thumbUrl)
    }
}
