package org.futo.inputmethod.latin.uix.actions.gif

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for the GIF picker logic (Klipy URL building, response
 * parsing, editor mime-type support checks). No Android dependencies.
 *
 * Response fixtures mirror the schema documented at
 * docs.klipy.com/gifs-api/gifs-trending-api and the official
 * klipy-android-demo-app DTOs: result/data{data[], has_next} envelope, items
 * with slug/title/type/file{hd,md,sm,xs}{gif{url,width,height,size}}.
 */
class KlipyApiTest {

    // ---------- contentSupportsGif ----------

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
    fun `trending url carries key page customer and no query`() {
        val url = KlipyApi.buildUrl(
            endpoint = KlipyApi.ENDPOINT_TRENDING,
            appKey = "APPKEY",
            customerId = "cid-123",
            query = null,
            page = 1,
            perPage = 24
        )
        assertTrue(url.startsWith("https://api.klipy.com/api/v1/APPKEY/gifs/trending?"))
        assertTrue(url.contains("page=1"))
        assertTrue(url.contains("per_page=24"))
        assertTrue(url.contains("customer_id=cid-123"))
        assertTrue(url.contains("format_filter=gif"))
        assertFalse(url.contains("&q="))
        assertFalse(url.contains("&locale="))
    }

    @Test
    fun `search url encodes query and locale`() {
        val url = KlipyApi.buildUrl(
            endpoint = KlipyApi.ENDPOINT_SEARCH,
            appKey = "K",
            customerId = "c",
            query = "thumbs up & down",
            page = 3,
            perPage = 24,
            locale = "us"
        )
        assertTrue(url.startsWith("https://api.klipy.com/api/v1/K/gifs/search?"))
        assertTrue(url.contains("page=3"))
        assertTrue(url.contains("q=thumbs+up+%26+down"))
        assertTrue(url.contains("locale=us"))
    }

    @Test
    fun `blank query and locale are omitted`() {
        val url = KlipyApi.buildUrl(KlipyApi.ENDPOINT_TRENDING, "K", "c", "", 1, locale = "")
        assertFalse(url.contains("&q="))
        assertFalse(url.contains("&locale="))
    }

    // ---------- parseResponse ----------

    private fun gifVariant(url: String, w: Int, h: Int, size: Int) =
        """{ "gif": { "url": "$url", "width": $w, "height": $h, "size": $size } }"""

    private val sampleResponse = """
        {
          "result": true,
          "data": {
            "data": [
              {
                "id": 8041071659142944,
                "slug": "hello-hi-662",
                "title": "Hello",
                "type": "gif",
                "file": {
                  "hd": ${gifVariant("https://static.klipy.com/ii/x/hd.gif", 498, 373, 4001918)},
                  "md": ${gifVariant("https://static.klipy.com/ii/x/md.gif", 498, 373, 3721260)},
                  "sm": ${gifVariant("https://static.klipy.com/ii/x/sm.gif", 220, 165, 314884)},
                  "xs": ${gifVariant("https://static.klipy.com/ii/x/xs.gif", 90, 68, 71468)}
                }
              },
              {
                "id": 90210,
                "slug": "fallback-item",
                "file": {
                  "hd": ${gifVariant("https://static.klipy.com/ii/y/hd.gif", 300, 200, 1000)},
                  "xs": ${gifVariant("https://static.klipy.com/ii/y/xs.gif", 90, 60, 100)}
                }
              },
              {
                "type": "ad",
                "width": 300,
                "height": 250,
                "content": "<div>ad</div>"
              },
              {
                "slug": "no-file"
              }
            ],
            "has_next": true,
            "meta": { "item_min_width": 100 }
          }
        }
    """.trimIndent()

    @Test
    fun `parses items with urls dims and titles`() {
        val page = KlipyApi.parseResponse(sampleResponse)

        assertEquals(2, page.items.size)
        assertTrue(page.hasNext)

        val first = page.items[0]
        assertEquals("hello-hi-662", first.id)
        assertEquals("Hello", first.description)
        // md gif is the insertion variant, sm gif the thumbnail
        assertEquals("https://static.klipy.com/ii/x/md.gif", first.gifUrl)
        assertEquals("https://static.klipy.com/ii/x/sm.gif", first.thumbUrl)
        assertEquals(498, first.width)
        assertEquals(373, first.height)
    }

    @Test
    fun `falls back across size variants and title is optional`() {
        val page = KlipyApi.parseResponse(sampleResponse)

        val second = page.items[1]
        assertEquals("fallback-item", second.id)
        assertEquals("", second.description)
        // no md -> hd for the full gif; no sm -> xs for the thumbnail
        assertEquals("https://static.klipy.com/ii/y/hd.gif", second.gifUrl)
        assertEquals("https://static.klipy.com/ii/y/xs.gif", second.thumbUrl)
    }

    @Test
    fun `ad entries and entries without usable media are skipped`() {
        val page = KlipyApi.parseResponse(sampleResponse)
        assertFalse(page.items.any { it.id == "no-file" })
        assertEquals(2, page.items.size)
    }

    @Test
    fun `missing data yields empty page without next`() {
        val page = KlipyApi.parseResponse("""{"result": true, "data": {"data": [], "has_next": false}}""")
        assertTrue(page.items.isEmpty())
        assertFalse(page.hasNext)
    }

    @Test
    fun `missing envelope yields empty page`() {
        val page = KlipyApi.parseResponse("""{"result": false}""")
        assertTrue(page.items.isEmpty())
        assertFalse(page.hasNext)
    }

    @Test
    fun `missing has_next defaults to false`() {
        val page = KlipyApi.parseResponse("""{"data": {"data": []}}""")
        assertFalse(page.hasNext)
    }

    @Test
    fun `thumbnail falls back to full gif variant when no small size exists`() {
        val body = """
            { "result": true, "data": { "has_next": true, "data": [ {
                "slug": "x",
                "file": { "md": ${gifVariant("https://static.klipy.com/ii/z/md.gif", 10, 10, 10)} }
            } ] } }
        """.trimIndent()
        val page = KlipyApi.parseResponse(body)
        val item = page.items.single()
        assertEquals("https://static.klipy.com/ii/z/md.gif", item.thumbUrl)
        assertTrue(page.hasNext)
    }
}
