package org.futo.inputmethod.latin.uix.actions

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.futo.inputmethod.latin.R
import org.futo.inputmethod.latin.uix.Action
import org.futo.inputmethod.latin.uix.ActionWindow
import org.futo.inputmethod.latin.uix.KeyboardManagerForAction
import org.futo.inputmethod.latin.uix.PersistentActionState
import org.futo.inputmethod.latin.uix.PersistentStateInitialization
import org.futo.inputmethod.latin.uix.actions.clipboard.sanitizeClipboardText

/** One remembered clip. Pinned entries survive eviction from the ring. */
data class QuickClip(val text: String, val pinned: Boolean)

/**
 * In-memory clipboard history backing the Quick Clipboard action: a ring of
 * the last [MAX_CLIPS] copied texts, captured via a ClipboardManager listener
 * for as long as the keyboard process is alive. Nothing touches disk — the
 * heavyweight, persistent history lives in
 * actions/clipboard/ClipboardHistoryAction.kt; this action trades durability
 * for zero setup (no enable toggle, no files) so it can simply always be on.
 */
class QuickClipboardState(
    private val manager: KeyboardManagerForAction
) : PersistentActionState {
    companion object {
        const val MAX_CLIPS = 20

        // The ring is heap-only, so cap entry size to bound worst-case RSS.
        const val MAX_CLIP_CHARS = 16_000
    }

    // Newest last; the window displays asReversed(). mutableStateListOf so an
    // open window recomposes live when a new clip arrives.
    val clips = mutableStateListOf<QuickClip>()

    private val context = manager.getContext()
    private val clipboardManager =
        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    private val listener = ClipboardManager.OnPrimaryClipChangedListener { captureCurrentClip() }

    init {
        // Listener callbacks land on the registering thread; registering on
        // Main keeps every mutation of `clips` on the UI thread, which
        // mutableStateListOf requires.
        manager.getLifecycleScope().launch(Dispatchers.Main) {
            clipboardManager.addPrimaryClipChangedListener(listener)
        }
    }

    private fun captureCurrentClip() {
        // Never remember what was copied while a password field is focused —
        // that is likely a password manager round-trip.
        val editorInfo = manager.getLatinIMEForDebug().currentInputEditorInfo
        if (editorInfo != null && isPasswordField(editorInfo.inputType)) return

        val clip = try {
            clipboardManager.primaryClip
        } catch (_: Exception) {
            // Reading the clip can throw if the owning app died; nothing to save.
            null
        } ?: return

        // Apps (e.g. password managers) can flag a clip sensitive — honor it.
        val isSensitive = clip.description.extras
            ?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE, false) == true
        if (isSensitive) return

        val text = clip.getItemAt(0)?.coerceToText(context)?.toString() ?: return
        if (text.isBlank() || text.length > MAX_CLIP_CHARS) return

        // Re-copying an existing text moves it to newest and keeps its pin —
        // same dedupe behavior as the persistent clipboard manager.
        val wasPinned = clips.firstOrNull { it.text == text }?.pinned == true
        clips.removeAll { it.text == text }
        clips.add(QuickClip(text, wasPinned))
        evictOverflow()
    }

    /** Evict the oldest unpinned entries until the unpinned count fits the ring. */
    private fun evictOverflow() {
        while (clips.count { !it.pinned } > MAX_CLIPS) {
            clips.removeAt(clips.indexOfFirst { !it.pinned })
        }
    }

    fun togglePin(clip: QuickClip) {
        val index = clips.indexOf(clip)
        if (index >= 0) clips[index] = clip.copy(pinned = !clip.pinned)
    }

    override suspend fun cleanUp() {
        // No-op: the ring is bounded (~MAX_CLIPS * MAX_CLIP_CHARS worst case),
        // small enough that dropping it on a low-memory hint would only
        // surprise the user.
    }

    override fun close() {
        manager.getLifecycleScope().launch(Dispatchers.Main) {
            clipboardManager.removePrimaryClipChangedListener(listener)
        }
    }
}

/**
 * The Quick Clipboard window: newest clips first. Tap inserts at the cursor;
 * long-press (or the pin icon) pins a clip so it survives ring eviction.
 */
class QuickClipboardWindow(
    private val manager: KeyboardManagerForAction,
    private val state: QuickClipboardState
) : ActionWindow() {
    @Composable
    override fun windowName(): String = stringResource(R.string.action_quick_clipboard_title)

    @Composable
    override fun WindowContents(keyboardShown: Boolean) {
        if (state.clips.isEmpty()) {
            Text(
                "Copied text appears here while the keyboard is running. " +
                        "Tap a clip to insert it; long-press to pin it.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp)
            )
            return
        }

        Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(8.dp)) {
            // n <= MAX_CLIPS + pinned entries, so a plain Column beats LazyColumn setup.
            state.clips.asReversed().forEach { clip ->
                ClipRow(clip)
            }
        }
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun ClipRow(clip: QuickClip) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            tonalElevation = 2.dp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .combinedClickable(
                    onClick = { insert(clip) },
                    onLongClick = { state.togglePin(clip) }
                )
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                IconButton(onClick = { state.togglePin(clip) }, modifier = Modifier.size(32.dp)) {
                    Icon(
                        painterResource(id = R.drawable.push_pin),
                        contentDescription = if (clip.pinned) "Unpin" else "Pin",
                        // Same visual language as the persistent clipboard manager:
                        // rotated = unpinned, dimmed; upright = pinned, full color.
                        tint = MaterialTheme.colorScheme.onSurface.copy(
                            alpha = if (clip.pinned) 1f else 0.5f
                        ),
                        modifier = Modifier.size(16.dp).rotate(if (clip.pinned) 0f else 45f)
                    )
                }
                Text(
                    sanitizeClipboardText(clip.text),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(start = 4.dp)
                )
            }
        }
    }

    private fun insert(clip: QuickClip) {
        manager.typeText(clip.text)
        manager.closeActionWindow()
    }
}

// Registered LAST in Registry.kt ("quick_clipboard"), not pinned by default.
val QuickClipboardAction = Action(
    icon = R.drawable.clipboard,
    name = R.string.action_quick_clipboard_title,
    simplePressImpl = null,
    canShowKeyboard = true,
    // Capture must run from keyboard load, not first window open — otherwise
    // clips copied before the first tap would be missing from the history.
    persistentState = { manager -> QuickClipboardState(manager) },
    persistentStateInitialization = PersistentStateInitialization.OnKeyboardLoad,
    windowImpl = { manager, persistent ->
        QuickClipboardWindow(manager, persistent as QuickClipboardState)
    },
)
