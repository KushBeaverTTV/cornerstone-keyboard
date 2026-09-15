package org.futo.inputmethod.latin.uix.actions.gif

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for GIF provider selection/fallback and setting mapping.
 * No Android dependencies.
 */
class GifProviderTest {

    // ---------- chooseGifProvider ----------

    @Test
    fun `preferred provider with key wins without fallback`() {
        val choice = chooseGifProvider(GifProviderKind.GIPHY, giphyConfigured = true, klipyConfigured = true)!!
        assertEquals(GifProviderKind.GIPHY, choice.provider)
        assertFalse(choice.isFallback)
    }

    @Test
    fun `klipy preference is honored when configured`() {
        val choice = chooseGifProvider(GifProviderKind.KLIPY, giphyConfigured = true, klipyConfigured = true)!!
        assertEquals(GifProviderKind.KLIPY, choice.provider)
        assertFalse(choice.isFallback)
    }

    @Test
    fun `missing giphy key falls back to klipy`() {
        val choice = chooseGifProvider(GifProviderKind.GIPHY, giphyConfigured = false, klipyConfigured = true)!!
        assertEquals(GifProviderKind.KLIPY, choice.provider)
        assertTrue(choice.isFallback)
    }

    @Test
    fun `missing klipy key falls back to giphy`() {
        val choice = chooseGifProvider(GifProviderKind.KLIPY, giphyConfigured = true, klipyConfigured = false)!!
        assertEquals(GifProviderKind.GIPHY, choice.provider)
        assertTrue(choice.isFallback)
    }

    @Test
    fun `no keys at all yields null`() {
        assertNull(chooseGifProvider(GifProviderKind.GIPHY, giphyConfigured = false, klipyConfigured = false))
        assertNull(chooseGifProvider(GifProviderKind.KLIPY, giphyConfigured = false, klipyConfigured = false))
    }

    // ---------- provider configuration ----------

    @Test
    fun `blank keys mean unconfigured`() {
        assertFalse(GiphyProvider("").isConfigured)
        assertFalse(GiphyProvider("   ").isConfigured)
        assertFalse(KlipyProvider("").isConfigured)
        assertTrue(GiphyProvider("abc").isConfigured)
        assertTrue(KlipyProvider("abc").isConfigured)
    }

    // ---------- setting mapping ----------

    @Test
    fun `setting value round-trips and defaults to giphy`() {
        assertEquals(GifProviderKind.GIPHY, GifProviderKind.fromSetting("giphy"))
        assertEquals(GifProviderKind.KLIPY, GifProviderKind.fromSetting("klipy"))
        assertEquals(GifProviderKind.GIPHY, GifProviderKind.fromSetting(""))
        assertEquals(GifProviderKind.GIPHY, GifProviderKind.fromSetting("tenor"))
        assertEquals(GifProviderKind.GIPHY, GifProviderKind.DEFAULT)
    }

    // ---------- provider URL plumbing ----------

    @Test
    fun `giphy provider maps pages to offsets`() {
        val provider = GiphyProvider("KEY")
        val page1 = provider.buildUrl(query = null, page = 1, perPage = 24, customerId = "c", language = null, countryCode = null)
        val page3 = provider.buildUrl(query = "cats", page = 3, perPage = 24, customerId = "c", language = null, countryCode = null)
        assertTrue(page1.contains("/trending?"))
        assertTrue(page1.contains("offset=0"))
        assertTrue(page3.contains("/search?"))
        assertTrue(page3.contains("offset=48"))
    }

    @Test
    fun `klipy provider passes pages through`() {
        val provider = KlipyProvider("KEY")
        val url = provider.buildUrl(query = "cats", page = 3, perPage = 24, customerId = "c", language = "en", countryCode = "us")
        assertTrue(url.contains("/search?"))
        assertTrue(url.contains("page=3"))
        assertTrue(url.contains("per_page=24"))
        assertTrue(url.contains("locale=us"))
    }
}
