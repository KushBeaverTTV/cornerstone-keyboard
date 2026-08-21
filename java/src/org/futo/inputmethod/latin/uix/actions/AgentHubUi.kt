package org.futo.inputmethod.latin.uix.actions

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Shared Agent Hub primitives (premium redesign 2026-08-21). Internal rather
// than private so AgentHub.kt can use them; still module-local, not API.
// Every color comes from MaterialTheme.colorScheme — the Cornerstone themes
// already map the KBP house style (black canvas, amber primary, white text).

/**
 * Tactile press feedback: scales content to 0.97f while pressed. Applied by
 * every interactive primitive so the whole hub responds uniformly to touch.
 */
@Composable
internal fun Modifier.hubPressScale(interactionSource: MutableInteractionSource?): Modifier {
    val pressed = interactionSource?.collectIsPressedAsState()?.value ?: false
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = tween(durationMillis = 120, easing = FastOutSlowInEasing),
        label = "hubPressScale"
    )
    return this.graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

/**
 * The one card container: 16.dp rounded, surfaceContainerHighest fill,
 * 1.dp outlineVariant border, 16.dp content padding. Optional left accent bar
 * (3.dp) for onboarding/guard/message cards; optional click/long-click.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun HubCard(
    modifier: Modifier = Modifier,
    accentColor: Color? = null,
    borderColor: Color? = null,
    borderWidth: Dp = 1.dp,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(16.dp)
    val cardInteraction = remember { MutableInteractionSource() }
    val clickModifier = when {
        onClick != null && onLongClick != null ->
            Modifier.combinedClickable(
                interactionSource = cardInteraction,
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick
            )
        onClick != null ->
            Modifier.clickable(
                interactionSource = cardInteraction,
                indication = null,
                onClick = onClick
            )
        else -> Modifier
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .hubPressScale(if (onClick != null) cardInteraction else null)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .border(
                width = borderWidth,
                color = borderColor ?: MaterialTheme.colorScheme.outlineVariant,
                shape = shape
            )
            .then(clickModifier)
    ) {
        if (accentColor != null) {
            // matchParentSize (not fillMaxHeight) so the bar tracks the card's
            // content height even inside a verticalScroll's infinite constraints.
            Box(modifier = Modifier.matchParentSize()) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .width(3.dp)
                        .fillMaxHeight()
                        .background(accentColor)
                )
            }
        }
        Column(modifier = Modifier.padding(contentPadding), content = content)
    }
}

/** Uppercase micro-header for sections ("DEVICE ACTIONS", agent name). */
@Composable
internal fun HubSectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        fontSize = 11.sp,
        letterSpacing = 1.5.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
    )
}

/**
 * Circle with the agent's first letter. Filled primary with onPrimary letter
 * when active (selected or online); surfaceContainerHighest fill with primary
 * letter otherwise.
 */
@Composable
internal fun HubMonogram(
    name: String,
    active: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp
) {
    val fill = if (active) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.surfaceContainerHighest
    }
    val letter = if (active) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.primary
    }
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(fill),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = name.firstOrNull()?.uppercase() ?: "?",
            color = letter,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

/** 8.dp reachability dot: online = primary, offline = error, unknown = outline. */
@Composable
internal fun HubStatusDot(online: Boolean?, unknown: Boolean, modifier: Modifier = Modifier) {
    val color = when {
        unknown -> MaterialTheme.colorScheme.outline
        online == true -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.error
    }
    Box(modifier = modifier) {
        if (online == true && !unknown) {
            // Soft pulsing ring behind the live dot — "online" breathes.
            val transition = rememberInfiniteTransition(label = "hubLiveDot")
            val ringScale by transition.animateFloat(
                initialValue = 1f,
                targetValue = 2.2f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 1400, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Restart
                ),
                label = "hubLiveRingScale"
            )
            val ringAlpha by transition.animateFloat(
                initialValue = 0.45f,
                targetValue = 0f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 1400, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Restart
                ),
                label = "hubLiveRingAlpha"
            )
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .graphicsLayer {
                        scaleX = ringScale
                        scaleY = ringScale
                        this.alpha = ringAlpha
                    }
                    .clip(CircleShape)
                    .background(color)
            )
        }
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color)
        )
    }
}

/**
 * The hero CTA: fully rounded primary pill, 52.dp full-width (compact: 44.dp
 * wrap-width). onPrimary text, 15.sp semiBold.
 */
@Composable
internal fun HubPrimaryPill(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    compact: Boolean = false
) {
    val shape = RoundedCornerShape(percent = 50)
    val pillInteraction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .then(if (compact) Modifier else Modifier.fillMaxWidth())
            // 48dp minimum interactive target, compact included — matches the
            // accessibility floor Material buttons enforce.
            .height(if (compact) 48.dp else 52.dp)
            .hubPressScale(pillInteraction)
            .clip(shape)
            .then(
                if (!enabled) {
                    // Disabled needs the outline too: a flat near-black fill on
                    // the black canvas reads as an inert card, not a button.
                    Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
                } else {
                    Modifier
                }
            )
            .background(
                if (enabled) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHighest
                }
            )
            .clickable(enabled = enabled, interactionSource = pillInteraction, indication = null, onClick = onClick)
            .padding(horizontal = 24.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = if (enabled) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

/**
 * Outlined pill for secondary actions: 1.dp outline, primary text, 13.sp.
 * onClick is LAST so call sites can use trailing-lambda syntax.
 */
@Composable
internal fun HubChip(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    textColor: Color? = null,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(percent = 50)
    val chipInteraction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            // 48dp minimum interactive target (was 36 — Material buttons
            // enforce 48 via minimumInteractiveComponentSize).
            .height(48.dp)
            .hubPressScale(if (enabled) chipInteraction else null)
            .clip(shape)
            .border(
                width = 1.dp,
                color = if (enabled) {
                    MaterialTheme.colorScheme.outline
                } else {
                    MaterialTheme.colorScheme.outlineVariant
                },
                shape = shape
            )
            .clickable(enabled = enabled, interactionSource = chipInteraction, indication = null, onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = when {
                !enabled -> MaterialTheme.colorScheme.onSurfaceVariant
                textColor != null -> textColor
                else -> MaterialTheme.colorScheme.primary
            },
            fontSize = 13.sp
        )
    }
}

/** One row in a HubRowCard: label, enabled state, tap action. */
internal data class HubRow(
    val label: String,
    val enabled: Boolean = true,
    val onClick: () -> Unit
)

/**
 * A card of action rows separated by 1.dp outlineVariant dividers (32.dp
 * indent). Each row: 15.sp label, trailing "›" chevron, 48.dp min height.
 */
@Composable
internal fun HubRowCard(rows: List<HubRow>, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
    ) {
        rows.forEachIndexed { index, row ->
            val rowInteraction = remember { MutableInteractionSource() }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .hubPressScale(if (row.enabled) rowInteraction else null)
                    .clickable(
                        enabled = row.enabled,
                        interactionSource = rowInteraction,
                        indication = null,
                        onClick = row.onClick
                    )
                    .padding(horizontal = 16.dp)
            ) {
                Text(
                    text = row.label,
                    fontSize = 15.sp,
                    color = if (row.enabled) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.weight(1f)
                )
                // Chevron is an affordance — suppress it on disabled rows so it
                // never invites a tap that does nothing.
                if (row.enabled) {
                    Text(
                        text = "›",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 18.sp
                    )
                }
            }
            if (index < rows.lastIndex) {
                Box(
                    modifier = Modifier
                        .padding(start = 32.dp)
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant)
                )
            }
        }
    }
}
