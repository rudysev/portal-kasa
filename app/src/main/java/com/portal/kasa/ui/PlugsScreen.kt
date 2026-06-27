package com.portal.kasa.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Coffee
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Outlet
import androidx.compose.material.icons.filled.Park
import androidx.compose.material.icons.filled.PowerOff
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.portal.kasa.data.Plug
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The plug dashboard: a responsive grid of cards, each a large touch target that lights up green when on so
 * the room's state is glanceable from across it (the Portal is viewed at a distance). Header summarises how
 * many are on and offers a one-tap "All off"; tapping a card — or its Switch — toggles that plug.
 *
 * Layout adapts to the **available width** (via [BoxWithConstraints]) rather than assuming the Portal's
 * 1920-wide screen, so it also reads well on a phone: the grid reflows its column count and spacing/typography
 * step down below a 600dp "compact" breakpoint. (`dp`/`sp` already handle pixel density.)
 */
@Composable
fun PlugsScreen(viewModel: PlugsViewModel) {
    val plugs by viewModel.plugs.collectAsStateWithLifecycle()
    val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        val compact = maxWidth < 600.dp
        val hPad = if (compact) 16.dp else 40.dp
        val gap = if (compact) 12.dp else 16.dp

        val now = rememberClock()
        val onCount = plugs.count { it.on }

        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = hPad)
                .padding(top = if (compact) 24.dp else 40.dp, bottom = if (compact) 20.dp else 32.dp),
        ) {
            HeroHeader(
                compact = compact,
                now = now,
                onCount = onCount,
                total = plugs.size,
                refreshing = refreshing,
                onRefresh = viewModel::refresh,
            )

            if (plugs.isNotEmpty()) {
                Spacer(Modifier.height(if (compact) 16.dp else 20.dp))
                QuickActions(
                    compact = compact,
                    anyOn = onCount > 0,
                    anyOff = onCount < plugs.size,
                    onAllOn = { viewModel.setAll(true) },
                    onAllOff = { viewModel.setAll(false) },
                )
            }

            // Grid fills the remaining space as a balanced, centred block so the dashboard reads as a
            // deliberate layout rather than content huddled in one corner of the wide Portal screen.
            Box(Modifier.fillMaxWidth().weight(1f)) {
                when {
                    plugs.isEmpty() && refreshing -> LoadingGrid(compact = compact, gap = gap)
                    plugs.isEmpty() -> CenterState { EmptyState(onRetry = viewModel::refresh) }
                    else -> PlugGrid(plugs = plugs, compact = compact, gap = gap, onToggle = viewModel::toggle)
                }
            }
        }
    }
}

@Composable
private fun HeroHeader(
    compact: Boolean,
    now: LocalDateTime,
    onCount: Int,
    total: Int,
    refreshing: Boolean,
    onRefresh: () -> Unit,
) {
    val subtle = MaterialTheme.colorScheme.onSurfaceVariant
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    now.format(TIME_FMT),
                    color = MaterialTheme.colorScheme.onBackground,
                    fontSize = if (compact) 44.sp else 64.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    now.format(AMPM_FMT),
                    color = subtle,
                    fontSize = if (compact) 16.sp else 20.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(bottom = if (compact) 8.dp else 12.dp),
                )
            }
            Text(
                greeting(now.hour),
                color = subtle,
                fontSize = if (compact) 18.sp else 22.sp,
            )
        }
        // Right cluster: the refresh control grouped over the status it relates to, so the top band reads as
        // one tidy unit instead of two disconnected corners.
        Column(horizontalAlignment = Alignment.End) {
            RefreshButton(refreshing = refreshing, onRefresh = onRefresh)
            Spacer(Modifier.height(10.dp))
            Text(
                now.format(DATE_FMT),
                color = subtle,
                fontSize = if (compact) 14.sp else 16.sp,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                if (total == 0) "Searching your network…" else "$onCount of $total on",
                color = if (onCount > 0) MaterialTheme.colorScheme.primary else subtle,
                fontSize = if (compact) 16.sp else 19.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun QuickActions(
    compact: Boolean,
    anyOn: Boolean,
    anyOff: Boolean,
    onAllOn: () -> Unit,
    onAllOff: () -> Unit,
) {
    // ≥48dp tall (56 on the Portal, touched at arm's length) so the global actions clear the touch-target min.
    val minH = if (compact) 48.dp else 56.dp
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(
            onClick = onAllOn,
            enabled = anyOff,
            modifier = Modifier.heightIn(min = minH),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
        ) {
            Icon(Icons.Filled.Bolt, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("All on")
        }
        OutlinedButton(
            onClick = onAllOff,
            enabled = anyOn,
            modifier = Modifier.heightIn(min = minH),
        ) {
            Icon(Icons.Filled.PowerSettingsNew, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("All off")
        }
    }
}

/** A clock that re-reads the time once a minute (cheap — not a per-frame animation). */
@Composable
private fun rememberClock(): LocalDateTime {
    val now by produceState(initialValue = LocalDateTime.now()) {
        while (true) {
            val t = LocalDateTime.now()
            value = t
            delay(((60 - t.second).coerceAtLeast(1)) * 1000L) // sleep to the next minute boundary
        }
    }
    return now
}

private fun greeting(hour: Int): String = when {
    hour < 12 -> "Good morning"
    hour < 18 -> "Good afternoon"
    else -> "Good evening"
}

private val TIME_FMT = DateTimeFormatter.ofPattern("h:mm", Locale.getDefault())
private val AMPM_FMT = DateTimeFormatter.ofPattern("a", Locale.getDefault())
private val DATE_FMT = DateTimeFormatter.ofPattern("EEE, MMM d", Locale.getDefault())

/**
 * Labelled refresh control (rather than a bare corner glyph) so the one recovery affordance is discoverable
 * and a comfortable touch target. It spins **only while discovering**: the spinning transition is created
 * inside the `refreshing` branch so it lives in composition just for that window — when idle there is no
 * running animation, so the screen stops requesting frames and the app goes fully idle (a perpetual animation
 * would pin the compositor at 60fps and drag the whole device down).
 */
@Composable
private fun RefreshButton(
    refreshing: Boolean,
    onRefresh: () -> Unit,
) {
    OutlinedButton(
        onClick = onRefresh,
        modifier = Modifier.heightIn(min = 48.dp),
        contentPadding = PaddingValues(horizontal = 16.dp),
    ) {
        if (refreshing) {
            val spin = rememberInfiniteTransition(label = "spin")
            val angle by spin.animateFloat(
                initialValue = 0f,
                targetValue = 360f,
                animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart),
                label = "angle",
            )
            Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp).rotate(angle))
        } else {
            Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(6.dp))
        Text(if (refreshing) "Searching…" else "Refresh")
    }
}

/**
 * A balanced grid that fills the available area and never leaves a hole: full rows stretch edge-to-edge while
 * an incomplete final row is **centred** (so five plugs read as a deliberate 3-over-2, not a 3×2 with a
 * missing card). Card width is fixed across rows so every card is the same size, and rows share the height
 * equally so the block fills the space instead of floating.
 */
@Composable
private fun BalancedGrid(
    count: Int,
    compact: Boolean,
    gap: Dp,
    keyOf: ((Int) -> Any)? = null,
    content: @Composable (Int) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val cols = columnsFor(count, compact)
        val rows = ((count + cols - 1) / cols).coerceAtLeast(1)
        val cardH = ((maxHeight - gap * (rows - 1)) / rows)
            .coerceIn(if (compact) 132.dp else 150.dp, if (compact) 200.dp else 260.dp)
        // Use more of the Portal's width so the dashboard fills the screen instead of huddling in a column.
        val gridWidth = if (compact) maxWidth else minOf(maxWidth, 1500.dp)
        val cardW = (gridWidth - gap * (cols - 1)) / cols
        val blockH = (cardH * rows + gap * (rows - 1)).coerceAtMost(maxHeight)

        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                Modifier.width(gridWidth).height(blockH),
                verticalArrangement = Arrangement.spacedBy(gap),
            ) {
                for (row in 0 until rows) {
                    val start = row * cols
                    val end = minOf(start + cols, count)
                    Row(
                        Modifier.fillMaxWidth().weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(gap, Alignment.CenterHorizontally),
                    ) {
                        for (i in start until end) {
                            // Key by stable identity (the plug's ip) so per-card animation state moves with the
                            // plug when the list reorders — a plug appearing/dropping shifts positions, and
                            // without a key the colour animations would briefly belong to the wrong card.
                            key(keyOf?.invoke(i) ?: i) {
                                Box(Modifier.width(cardW).fillMaxHeight()) { content(i) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlugGrid(
    plugs: List<Plug>,
    compact: Boolean,
    gap: Dp,
    onToggle: (String, Boolean) -> Unit,
) {
    BalancedGrid(count = plugs.size, compact = compact, gap = gap, keyOf = { plugs[it].ip }) { i ->
        val plug = plugs[i]
        PlugCard(plug = plug, compact = compact, onToggle = { on -> onToggle(plug.ip, on) })
    }
}

/** Column count that yields a balanced grid (≈2 rows for a handful of plugs, more rows as the count grows). */
private fun columnsFor(n: Int, compact: Boolean): Int {
    if (n <= 1) return 1
    if (compact) return 2
    val rows = when {
        n <= 3 -> 1
        n <= 8 -> 2
        n <= 15 -> 3
        else -> 4
    }
    return (n + rows - 1) / rows
}

@Composable
private fun PlugCard(
    plug: Plug,
    compact: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    val on = plug.on
    // A noticeably green fill (not a faint 13% wash) so the on-state is glanceable from across the room.
    val cardBg by animateColorAsState(
        if (on) accent.copy(alpha = 0.22f) else MaterialTheme.colorScheme.surface,
        label = "cardBg",
    )
    val borderColor by animateColorAsState(
        if (on) accent.copy(alpha = 0.7f) else MaterialTheme.colorScheme.outline,
        label = "border",
    )
    val pad = if (compact) 16.dp else 22.dp
    val badge = if (compact) 46.dp else 60.dp

    Box(
        Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(24.dp))
            .background(cardBg)
            .border(1.dp, borderColor, RoundedCornerShape(24.dp))
            .clickable { onToggle(!on) }
            .padding(pad),
    ) {
        val status = @Composable {
            Text(
                if (on) "On" else "Off",
                color = if (on) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = if (compact) 14.sp else 15.sp,
                fontWeight = FontWeight.Medium,
            )
        }

        if (compact) {
            // Narrow: badge + switch on top, name/status anchored to the bottom. Names wrap to two lines.
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                    IconBadge(icon = iconFor(plug.alias), on = on, size = badge)
                    Switch(checked = on, onCheckedChange = onToggle)
                }
                Spacer(Modifier.weight(1f))
                Text(
                    plug.alias,
                    color = MaterialTheme.colorScheme.onBackground,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    lineHeight = 21.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(3.dp))
                status()
            }
        } else {
            // Wide: badge, name/status, and switch in one vertically-centred row. The name wraps to two lines
            // and ellipsises beyond that — no marquee, since a perpetually-scrolling name would keep the
            // always-on Portal rendering frames (see the RefreshButton note on avoiding perpetual animation).
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                IconBadge(icon = iconFor(plug.alias), on = on, size = badge)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        plug.alias,
                        color = MaterialTheme.colorScheme.onBackground,
                        fontSize = 21.sp,
                        fontWeight = FontWeight.SemiBold,
                        lineHeight = 25.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(4.dp))
                    status()
                }
                Spacer(Modifier.width(12.dp))
                Switch(checked = on, onCheckedChange = onToggle)
            }
        }
    }
}

@Composable
private fun IconBadge(
    icon: ImageVector,
    on: Boolean,
    size: Dp,
) {
    val accent = MaterialTheme.colorScheme.primary
    val bg by animateColorAsState(
        if (on) accent else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.07f),
        label = "badgeBg",
    )
    val tint by animateColorAsState(
        if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
        label = "badgeTint",
    )
    Box(
        Modifier.size(size).clip(CircleShape).background(bg),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(size * 0.54f))
    }
}

/** Pick a glanceable icon from the plug's name so each card reads at a distance. Falls back to an outlet. */
private fun iconFor(alias: String): ImageVector {
    val a = alias.lowercase()
    fun has(vararg words: String) = words.any { a.contains(it) }
    return when {
        has("coffee", "espresso", "kettle", "brew") -> Icons.Filled.Coffee
        has("light", "lamp", "bulb", "globe", "sconce", "chandelier") -> Icons.Filled.Lightbulb
        has("tv", "television", "monitor", "screen") -> Icons.Filled.Tv
        has("fan", "air", "purifier") -> Icons.Filled.Air
        has("speaker", "music", "stereo", "sound") -> Icons.Filled.Speaker
        has("heat", "heater", "warmer") -> Icons.Filled.Thermostat
        has("christmas", "tree", "holiday") -> Icons.Filled.Park
        else -> Icons.Filled.Outlet
    }
}

@Composable
private fun CenterState(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}

/**
 * Loading state that keeps the dashboard's shape: shimmering placeholder cards laid out on the same grid,
 * rather than a lone spinner in an empty void. The header already says "Searching your network…", and the
 * refresh control spins, so the placeholders carry the visual rhythm.
 */
@Composable
private fun LoadingGrid(compact: Boolean, gap: Dp) {
    val pulse = rememberInfiniteTransition(label = "skeleton")
    val alpha by pulse.animateFloat(
        initialValue = 0.4f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse),
        label = "pulse",
    )
    val skeletonCount = if (compact) 4 else 6
    // The placeholder cards are decorative, so carry the loading message as the region's accessibility label
    // (a screen reader would otherwise hear nothing for this area — the old spinner had a visible caption).
    Box(
        Modifier
            .fillMaxSize()
            .semantics { contentDescription = "Looking for plugs on your Wi-Fi" },
    ) {
        BalancedGrid(count = skeletonCount, compact = compact, gap = gap) {
            SkeletonCard(alpha = alpha)
        }
    }
}

@Composable
private fun SkeletonCard(alpha: Float) {
    val ph = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.10f * alpha)
    Box(
        Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(24.dp))
            .padding(22.dp),
    ) {
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(60.dp).clip(CircleShape).background(ph))
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Box(Modifier.fillMaxWidth(0.7f).height(18.dp).clip(RoundedCornerShape(6.dp)).background(ph))
                Spacer(Modifier.height(10.dp))
                Box(Modifier.fillMaxWidth(0.3f).height(14.dp).clip(RoundedCornerShape(6.dp)).background(ph))
            }
        }
    }
}

@Composable
private fun EmptyState(onRetry: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(
            Icons.Filled.PowerOff,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(56.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "No plugs found",
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "Make sure they're on the same Wi-Fi.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 15.sp,
        )
        Spacer(Modifier.height(20.dp))
        // A prominent, ≥48dp filled CTA — this is the one action in the state where the user is stuck.
        Button(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) { Text("Search again") }
        Spacer(Modifier.height(28.dp))
        // Surface the headline voice feature where a first-run user will see it.
        Row(
            modifier = Modifier.widthIn(max = 460.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                Icons.Filled.RecordVoiceOver,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            Text(
                "Tip: turn on \"Kasa Plugs\" in Assistant → External tools to control plugs by voice.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 14.sp,
            )
        }
    }
}
