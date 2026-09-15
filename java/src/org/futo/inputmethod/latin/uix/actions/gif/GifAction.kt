package org.futo.inputmethod.latin.uix.actions.gif

import android.util.Log
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.futo.inputmethod.latin.BuildConfig
import org.futo.inputmethod.latin.R
import org.futo.inputmethod.latin.common.Constants
import org.futo.inputmethod.latin.uix.Action
import org.futo.inputmethod.latin.uix.ActionHeaderSearch
import org.futo.inputmethod.latin.uix.ActionWindow
import org.futo.inputmethod.latin.uix.CloseResult
import org.futo.inputmethod.latin.uix.DataStoreHelper
import org.futo.inputmethod.latin.uix.KeyboardManagerForAction
import org.futo.inputmethod.latin.uix.LocalKeyboardScheme
import org.futo.inputmethod.latin.uix.PreferenceUtils
import org.futo.inputmethod.latin.uix.SettingsKey
import org.futo.inputmethod.latin.uix.actions.clipboard.CLIPBOARD_AUTHORITY
import org.futo.inputmethod.latin.uix.actions.clipboard.ClipboardPasteRequest
import org.futo.inputmethod.latin.uix.actions.clipboard.ClipboardProviderState
import org.futo.inputmethod.latin.uix.settings.useDataStore
import org.futo.inputmethod.latin.uix.settings.useDataStoreValue
import org.futo.inputmethod.latin.uix.theme.Typography
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

private const val TAG = "GifAction"
private const val PAGE_SIZE = 24
private const val SEARCH_DEBOUNCE_MS = 400L
private const val GIF_CACHE_MAX_AGE_MS = 24L * 60L * 60L * 1000L
private const val PREF_GIF_CUSTOMER_ID = "gif_customer_id"

/** Which GIF provider the picker prefers ("giphy" default, or "klipy") */
val GIF_PROVIDER = SettingsKey(
    stringPreferencesKey("gif_provider"),
    GifProviderKind.DEFAULT.settingValue
)

// Short-timeout client for provider API calls (JSON metadata only). Thumbnail
// and full-GIF downloads live in GifThumbnailLoader with its own client.
private val gifHttpClient = OkHttpClient.Builder()
    .connectTimeout(5, TimeUnit.SECONDS)
    .readTimeout(8, TimeUnit.SECONDS)
    .build()

private suspend fun fetchGifPage(
    provider: GifProvider,
    query: String?,
    page: Int,
    customerId: String,
    language: String?,
    countryCode: String?
): GifPage? =
    withContext(Dispatchers.IO) {
        val url = provider.buildUrl(
            query = query,
            page = page,
            perPage = PAGE_SIZE,
            customerId = customerId,
            language = language,
            countryCode = countryCode
        )

        try {
            gifHttpClient.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "${provider.kind} request failed: HTTP ${response.code}")
                    return@withContext null
                }
                val body = response.body?.string() ?: return@withContext null
                provider.parseResponse(body)
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "${provider.kind} request failed", e)
            null
        }
    }

// Both providers want a stable per-user id (Klipy: customer_id, GIPHY:
// customer_id from a Random ID). Random UUID generated once per install;
// pseudonymous, not tied to any account or device identifier.
private fun getOrCreateCustomerId(context: android.content.Context): String {
    val prefs = PreferenceUtils.getDefaultSharedPreferences(context)
    prefs.getString(PREF_GIF_CUSTOMER_ID, null)?.let { return it }
    val id = UUID.randomUUID().toString()
    prefs.edit { putString(PREF_GIF_CUSTOMER_ID, id) }
    return id
}

private fun gifCacheDir(context: android.content.Context): File =
    File(context.cacheDir, "gifs")

private fun gifFileName(id: String): String =
    id.map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '_' }.joinToString("") + ".gif"

val GifAction = Action(
    icon = R.drawable.image,
    name = R.string.action_gif_title,
    canShowKeyboard = true,
    simplePressImpl = null,
    windowImpl = { manager, _ -> GifActionWindow(manager) }
)

private class GifActionWindow(
    private val manager: KeyboardManagerForAction
) : ActionWindow() {
    // All window work (API calls, downloads, insertion) runs on this scope and
    // is cancelled in close() so nothing outlives the panel.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val providers: Map<GifProviderKind, GifProvider> = mapOf(
        GifProviderKind.GIPHY to GiphyProvider(BuildConfig.GIPHY_API_KEY.trim()),
        GifProviderKind.KLIPY to KlipyProvider(BuildConfig.KLIPY_API_KEY.trim())
    )

    private val customerId = getOrCreateCustomerId(manager.getContext())

    // ISO 639-1 language (GIPHY) and ISO 3166 alpha-2 country (both providers)
    private val language = manager.getActiveLocales().firstOrNull()?.language
    private val countryCode = manager.getActiveLocales().firstOrNull()?.country?.lowercase()

    // The search field overrides the input connection while focused, so support
    // checks and insertion must target the base connection (like clipboard image
    // paste does) to reach the real app.
    private val fieldSupportedAtOpen =
        manager.appSupportsImageInsertion(GIF_MIME_TYPE, ignoreConnectionOverride = true)

    private var items by mutableStateOf<List<GifItem>>(emptyList())
    private var loading by mutableStateOf(false)
    private var loadingMore by mutableStateOf(false)
    private var panelErrorRes by mutableIntStateOf(0)
    private var noticeRes by mutableIntStateOf(0)
    private var insertingId by mutableStateOf<String?>(null)

    private var activeKind by mutableStateOf<GifProviderKind?>(null)
    private var preferredKind by mutableStateOf(GifProviderKind.DEFAULT)
    private var providerFellBack by mutableStateOf(false)

    private val searchText = mutableStateOf("")
    private var nextPage: Int? = null
    private var lastRequestedQuery: String? = null
    private var generation = 0

    init {
        pruneGifCache()
        applyPreferredProvider(
            GifProviderKind.fromSetting(DataStoreHelper.getSetting(GIF_PROVIDER))
        )
    }

    private fun activeProvider(): GifProvider? = activeKind?.let { providers[it] }

    /** Re-selects the provider when the persisted preference changes. */
    private fun applyPreferredProvider(preferred: GifProviderKind) {
        preferredKind = preferred
        val choice = chooseGifProvider(
            preferred = preferred,
            giphyConfigured = providers.getValue(GifProviderKind.GIPHY).isConfigured,
            klipyConfigured = providers.getValue(GifProviderKind.KLIPY).isConfigured
        )
        providerFellBack = choice?.isFallback ?: false
        if (choice?.provider != activeKind) {
            activeKind = choice?.provider
            items = emptyList()
            nextPage = null
            if (activeKind != null) refresh(currentQuery())
        }
    }

    private fun currentQuery(): String? = searchText.value.trim().ifEmpty { null }

    private fun refresh(query: String?) {
        val provider = activeProvider() ?: return
        lastRequestedQuery = query
        val gen = ++generation
        loading = true
        panelErrorRes = 0
        noticeRes = 0
        scope.launch {
            val page = fetchGifPage(provider, query, page = 1, customerId, language, countryCode)
            if (gen != generation) return@launch
            loading = false
            if (page == null) {
                panelErrorRes = R.string.action_gif_error_network
                return@launch
            }
            items = page.items
            nextPage = if (page.hasNext) 2 else null
        }
    }

    private fun loadMore() {
        val provider = activeProvider() ?: return
        val pageToLoad = nextPage ?: return
        if (loading || loadingMore) return
        val gen = generation
        val query = currentQuery()
        loadingMore = true
        scope.launch {
            val page = fetchGifPage(provider, query, page = pageToLoad, customerId, language, countryCode)
            loadingMore = false
            if (gen != generation || page == null) return@launch
            val seen = items.mapTo(HashSet()) { it.id }
            items = items + page.items.filter { it.id !in seen }
            nextPage = if (page.hasNext) pageToLoad + 1 else null
        }
    }

    private fun onGifSelected(item: GifItem, view: View) {
        // Re-check at tap time: the user may have switched fields while open
        if (!manager.appSupportsImageInsertion(GIF_MIME_TYPE, ignoreConnectionOverride = true)) {
            noticeRes = R.string.action_gif_unsupported_field
            return
        }
        if (insertingId != null) return

        scope.launch {
            insertingId = item.id
            val context = manager.getContext()
            // NOTE: both providers' standard terms require loading media
            // directly from their URLs and disallow caching/re-hosting without
            // written approval. A keyboard cannot pass an https URL to
            // commitContent reliably, so we stage the GIF in a private cache
            // (pruned after 24h) and serve it via our own content provider
            // only at the moment of insertion. Acceptable gray area for a
            // personal-use build; a distributed app should contact the
            // providers (developers@klipy.com / GIPHY partner rep) for approval.
            val file = withContext(Dispatchers.IO) {
                val bytes = GifThumbnailLoader.downloadFullGif(item.gifUrl) ?: return@withContext null
                val dir = gifCacheDir(context).apply { mkdirs() }
                val file = File(dir, gifFileName(item.id))
                try {
                    file.writeBytes(bytes)
                    file
                } catch (e: IOException) {
                    Log.w(TAG, "failed to write GIF to cache", e)
                    null
                }
            }
            insertingId = null

            if (file == null) {
                noticeRes = R.string.action_gif_error_network
                return@launch
            }

            // Reuse the clipboard content provider to serve the downloaded file;
            // it is a generic expiring file/mime request registry
            val request = ClipboardProviderState.addRequest(
                ClipboardPasteRequest(
                    file = file,
                    mimeType = GIF_MIME_TYPE,
                    expiration = System.currentTimeMillis() + 5L * 60L * 1000L
                )
            )
            val uri = "content://$CLIPBOARD_AUTHORITY/clip/$request".toUri()

            // typeUri shows its own toast if commitContent is rejected
            if (manager.typeUri(uri, listOf(GIF_MIME_TYPE), ignoreConnectionOverride = true)) {
                manager.performHapticAndAudioFeedback(Constants.CODE_OUTPUT_TEXT, view)
            }
        }
    }

    private fun pruneGifCache() {
        scope.launch(Dispatchers.IO) {
            val cutoff = System.currentTimeMillis() - GIF_CACHE_MAX_AGE_MS
            gifCacheDir(manager.getContext()).listFiles()
                ?.filter { it.lastModified() < cutoff }
                ?.forEach { it.delete() }
        }
    }

    override fun close(): CloseResult {
        scope.cancel()
        return CloseResult.Default
    }

    @Composable
    override fun windowName(): String = stringResource(R.string.action_gif_title)

    @Composable
    override fun WindowTitleBar(rowScope: RowScope) {
        with(rowScope) {
            // Klipy's terms require the exact "Search KLIPY" placeholder
            val hintRes = when (activeKind) {
                GifProviderKind.KLIPY -> R.string.action_gif_search_hint_klipy
                else -> R.string.action_gif_search_hint_giphy
            }
            ActionHeaderSearch(
                searchText,
                Modifier.weight(1.0f),
                placeholder = stringResource(hintRes)
            )
            ProviderSwitcher()
        }
    }

    @Composable
    private fun ProviderSwitcher() {
        val providerSetting = useDataStore(GIF_PROVIDER)
        val selected = GifProviderKind.fromSetting(providerSetting.value)

        Surface(
            color = LocalKeyboardScheme.current.keyboardContainer,
            contentColor = LocalKeyboardScheme.current.onKeyboardContainer,
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.padding(2.dp)
        ) {
            Row {
                GifProviderKind.entries.forEach { kind ->
                    val isSelected = kind == selected
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(24.dp))
                            .background(
                                if (isSelected) {
                                    LocalKeyboardScheme.current.keyboardPress
                                } else {
                                    Color.Transparent
                                }
                            )
                            .clickable { providerSetting.setValue(kind.settingValue) }
                            .padding(horizontal = 8.dp, vertical = 6.dp)
                            .semantics {
                                role = Role.Button
                                contentDescription = "Use ${kind.wireName}"
                            }
                    ) {
                        Text(
                            kind.wireName,
                            style = Typography.SmallMl,
                            color = LocalKeyboardScheme.current.onKeyboardContainer.copy(
                                alpha = if (isSelected) 1.0f else 0.6f
                            )
                        )
                    }
                }
            }
        }
    }

    @Composable
    override fun WindowContents(keyboardShown: Boolean) {
        // Apply the persisted provider preference (also fires on toggle)
        val preferredSetting = useDataStoreValue(GIF_PROVIDER)
        LaunchedEffect(preferredSetting) {
            applyPreferredProvider(GifProviderKind.fromSetting(preferredSetting))
        }

        // Debounced search; refresh() itself discards stale responses via generation
        LaunchedEffect(searchText.value) {
            val query = searchText.value.trim()
            if (query == (lastRequestedQuery ?: "")) return@LaunchedEffect
            delay(SEARCH_DEBOUNCE_MS)
            refresh(query.ifEmpty { null })
        }

        Column(modifier = Modifier.fillMaxSize().padding(4.dp, 0.dp)) {
            if (!fieldSupportedAtOpen) {
                NoticeBanner(stringResource(R.string.action_gif_unsupported_field))
            } else if (noticeRes != 0) {
                NoticeBanner(stringResource(noticeRes))
            } else if (providerFellBack) {
                NoticeBanner(
                    stringResource(
                        R.string.action_gif_provider_fallback,
                        preferredKind.wireName,
                        activeKind?.wireName ?: ""
                    )
                )
            }

            Box(modifier = Modifier.weight(1.0f)) {
                when {
                    activeProvider() == null -> MessagePanel(stringResource(R.string.action_gif_no_api_key))
                    loading && items.isEmpty() -> MessagePanel(stringResource(R.string.action_gif_loading))
                    panelErrorRes != 0 && items.isEmpty() -> MessagePanel(
                        text = stringResource(panelErrorRes),
                        retry = { refresh(currentQuery()) }
                    )
                    items.isEmpty() -> MessagePanel(stringResource(R.string.action_gif_no_results))
                    else -> GifGrid()
                }
            }

            // "Powered by GIPHY" is required attribution when GIPHY is active;
            // shown for Klipy too (optional there) for a consistent panel
            activeKind?.let { kind ->
                Text(
                    stringResource(R.string.action_gif_powered_by, kind.wireName),
                    style = Typography.SmallMl,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                    modifier = Modifier
                        .align(Alignment.End)
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                )
            }
        }
    }

    @Composable
    private fun NoticeBanner(text: String) {
        Surface(
            color = LocalKeyboardScheme.current.keyboardPress,
            contentColor = LocalKeyboardScheme.current.onKeyboardContainer,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(4.dp)
        ) {
            Text(text, style = Typography.SmallMl, modifier = Modifier.padding(8.dp))
        }
    }

    @Composable
    private fun MessagePanel(text: String, retry: (() -> Unit)? = null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text,
                    style = Typography.SmallMl,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(16.dp)
                )
                if (retry != null) {
                    TextButton(onClick = retry) {
                        Text(stringResource(R.string.action_gif_retry))
                    }
                }
            }
        }
    }

    @Composable
    private fun GifGrid() {
        val gridState = rememberLazyGridState()

        val nearEnd by remember {
            derivedStateOf {
                val info = gridState.layoutInfo
                val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: return@derivedStateOf false
                info.totalItemsCount > 0 && lastVisible >= info.totalItemsCount - 6
            }
        }
        LaunchedEffect(nearEnd) {
            if (nearEnd) loadMore()
        }

        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 96.dp),
            state = gridState,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier
                .fillMaxSize()
                .background(
                    LocalKeyboardScheme.current.keyboardContainer,
                    RoundedCornerShape(9.dp)
                )
                .padding(4.dp)
        ) {
            items(items, key = { "${activeKind}:${it.id}" }) { item ->
                GifCell(item)
            }
        }
    }

    @Composable
    private fun GifCell(item: GifItem) {
        val view = LocalView.current
        val targetWidthPx = with(LocalDensity.current) { 128.dp.toPx().roundToInt() }
        val aspect = remember(item) {
            if (item.width > 0 && item.height > 0) {
                (item.width.toFloat() / item.height.toFloat()).coerceIn(0.5f, 2.0f)
            } else {
                1.0f
            }
        }

        // produceState cancels the in-flight load when the cell leaves composition
        // (scrolled away or panel closed)
        val thumbnail by produceState<ImageBitmap?>(
            initialValue = GifThumbnailLoader.cached(item.thumbUrl),
            key1 = item.thumbUrl
        ) {
            if (value == null) {
                value = GifThumbnailLoader.load(item.thumbUrl, targetWidthPx)
            }
        }

        Box(
            modifier = Modifier
                .aspectRatio(aspect)
                .clip(RoundedCornerShape(8.dp))
                .background(LocalKeyboardScheme.current.keyboardPress)
                .clickable { onGifSelected(item, view) }
                .semantics { contentDescription = item.description }
        ) {
            thumbnail?.let {
                androidx.compose.foundation.Image(
                    bitmap = it,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
            if (insertingId == item.id) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(24.dp),
                    strokeWidth = 2.dp
                )
            }
        }
    }
}
