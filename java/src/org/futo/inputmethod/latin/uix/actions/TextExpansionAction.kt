package org.futo.inputmethod.latin.uix.actions

import android.content.Context
import android.util.Log
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.json.JSONException
import org.json.JSONObject
import org.futo.inputmethod.latin.R
import org.futo.inputmethod.latin.uix.Action
import org.futo.inputmethod.latin.uix.ActionWindow
import org.futo.inputmethod.latin.uix.KeyboardManagerForAction
import org.futo.inputmethod.latin.uix.TEXT_EXPANSIONS
import org.futo.inputmethod.latin.uix.getSettingBlocking
import org.futo.inputmethod.latin.uix.setSettingBlocking

/**
 * Text expansion engine: typing a shortcut followed by space commits the
 * phrase instead (hooked in InputLogic.handleSeparatorEvent).
 *
 * Pairs live in the TEXT_EXPANSIONS DataStore setting as a JSON object. Reads
 * go through a cache keyed on the raw JSON string, so the per-space-press
 * lookup in the input path never re-parses, and any write — from anywhere —
 * is picked up the next time the stored string changes (no invalidation
 * protocol to get wrong).
 */
object TextExpander {
    private var cachedJson: String? = null
    private var cachedExpansions: Map<String, String> = emptyMap()

    @JvmStatic
    @Synchronized
    fun expansions(context: Context): Map<String, String> {
        val json = context.getSettingBlocking(TEXT_EXPANSIONS)
        if (json != cachedJson) {
            cachedExpansions = parseExpansions(json)
            cachedJson = json
        }
        return cachedExpansions
    }

    /** The phrase for an exactly-matched shortcut, or null. O(1) map lookup. */
    @JvmStatic
    fun expand(context: Context, typedWord: String): String? =
        expansions(context)[typedWord]

    @Synchronized
    fun setExpansions(context: Context, expansions: Map<String, String>) {
        val json = JSONObject(expansions).toString()
        // Update the cache synchronously: the DataStore collector refreshes
        // asynchronously, so without this the very next space press could
        // still see the old map.
        cachedJson = json
        cachedExpansions = expansions
        context.setSettingBlocking(TEXT_EXPANSIONS.key, json)
    }

    private fun parseExpansions(json: String): Map<String, String> = try {
        val obj = JSONObject(json)
        buildMap {
            for (key in obj.keys()) {
                val phrase = obj.optString(key)
                if (key.isNotBlank() && phrase.isNotBlank()) put(key, phrase)
            }
        }
    } catch (e: JSONException) {
        // A corrupt setting must not break typing — treat it as "no expansions".
        Log.w("TextExpander", "ignoring unparseable text_expansions setting", e)
        emptyMap()
    }
}

/**
 * Editor window for expansion pairs: existing pairs with per-row delete, and
 * an add form. Shortcuts may not contain whitespace — expansion triggers on
 * space, so a spaced shortcut could never fire.
 */
class TextExpansionWindow(
    manager: KeyboardManagerForAction
) : ActionWindow() {
    private val context = manager.getContext()

    private var expansions by mutableStateOf(TextExpander.expansions(context))
    private var shortcutDraft by mutableStateOf("")
    private var phraseDraft by mutableStateOf("")

    @Composable
    override fun windowName(): String = stringResource(R.string.action_text_expansion_title)

    @Composable
    override fun WindowContents(keyboardShown: Boolean) {
        Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            Text(
                "Type a shortcut followed by space to replace it with the phrase.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            // Sorted for a stable scan order; user-defined lists stay small.
            expansions.toSortedMap().forEach { (shortcut, phrase) ->
                ExpansionRow(shortcut, phrase)
            }

            AddForm()
        }
    }

    @Composable
    private fun ExpansionRow(shortcut: String, phrase: String) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            tonalElevation = 2.dp,
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(shortcut, style = MaterialTheme.typography.titleSmall)
                    Text(
                        phrase,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = { setAll(expansions - shortcut) }) {
                    Icon(
                        painterResource(id = R.drawable.close),
                        contentDescription = "Remove $shortcut",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }

    @Composable
    private fun AddForm() {
        OutlinedTextField(
            value = shortcutDraft,
            onValueChange = { shortcutDraft = it },
            label = { Text("Shortcut") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
        )
        OutlinedTextField(
            value = phraseDraft,
            onValueChange = { phraseDraft = it },
            label = { Text("Phrase") },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
        )
        Button(
            onClick = {
                setAll(expansions + (shortcutDraft.trim() to phraseDraft))
                shortcutDraft = ""
                phraseDraft = ""
            },
            enabled = isValidPair(shortcutDraft, phraseDraft),
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
        ) {
            Text("Add")
        }
    }

    private fun isValidPair(shortcut: String, phrase: String): Boolean {
        val trimmed = shortcut.trim()
        return trimmed.isNotEmpty() && trimmed.none { it.isWhitespace() } && phrase.isNotBlank()
    }

    private fun setAll(map: Map<String, String>) {
        expansions = map
        TextExpander.setExpansions(context, map)
    }
}

// Registered LAST in Registry.kt ("text_expansion"), not pinned by default.
val TextExpansionAction = Action(
    icon = R.drawable.edit_text,
    name = R.string.action_text_expansion_title,
    simplePressImpl = null,
    windowImpl = { manager, _ -> TextExpansionWindow(manager) },
)
