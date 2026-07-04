package com.portal.kasa.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.portal.kasa.data.Plug
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

// Constant (non-responsive) design values, named so they read as intent rather than magic numbers.
private val CardCorner = RoundedCornerShape(24.dp)
private val Hairline = 1.dp // card / placeholder border
private val ActionIcon = 18.dp // leading glyph inside buttons and the badge-independent controls

// Meta Portal platform requirements — not tunable aesthetics. Named so a future design pass can't mistake
// them for spacing tokens and slide the chrome back under the system pills or shrink targets below spec.
private val PortalOverlayReserve = 64.dp // top strip where Portal floats its back/home + Wi-Fi pills
private val PortalTouchTargetMin = 64.dp // touch-target floor at Portal viewing/reach distance
private val TouchGap = 16.dp // minimum spacing between adjacent hit targets

// Tier breakpoints (see [dimensFor]). Portal TV is not in the matrix yet — it would need a leanback tier.
private val PhoneTierMax = 600.dp // canvases narrower OR shorter than this read as a phone
private val PortalPlusMinWidth = 1400.dp // between the 1280dp Portals and the 1920dp Portal+

/**
 * Responsive design tokens for one screen-size tier. Every size-dependent measurement lives here, so the
 * composables below read named tokens (`d.clock`, `d.cardPad`, …) instead of inline literals, and this table
 * is the single place to tune the layout when a new device size shows up. Values follow Meta's Portal design
 * requirements (≥64dp top overlay reserve, 64dp touch targets on the Portal tiers / 52dp on phone, ≥14sp
 * text, larger type for viewing distance).
 */
internal data class Dimens(
    // Page frame
    val pagePadH: Dp,
    val pageTop: Dp, // reserves the Portal top system-overlay strip (back/home + Wi-Fi pills)
    val pageBottom: Dp,
    val headerGap: Dp, // header → control band
    // Grid
    val gridGap: Dp,
    val gridMaxW: Dp, // cap so cards don't stretch to an uncomfortable measure on very wide screens
    val minCardW: Dp, // comfortable card width; caps the column *count* (not the rendered width) so narrow / portrait canvases don't crowd
    val cardMinH: Dp,
    val cardMaxH: Dp,
    val skeletonCount: Int,
    // Card
    val singleColumn: Boolean, // phone: stack the card's contents; larger screens use a single wide row
    val cardPad: Dp,
    val badge: Dp,
    val cardName: TextUnit,
    val cardNameLine: TextUnit,
    val cardStatus: TextUnit,
    // Controls
    val touchMinH: Dp, // Meta's Portal touch-target floor
    // Header typography
    val clock: TextUnit,
    val amPm: TextUnit,
    val amPmBaseline: Dp,
    val greeting: TextUnit,
    val date: TextUnit,
    val summary: TextUnit, // "x of y on"
)

/**
 * The Portal family isn't one size. Most models (Portal, Portal 10", Portal Mini, Portal Go) present a
 * ~1280dp-wide landscape canvas at mdpi; the 15.6" Portal+ is ~1920dp; and the app should still degrade to a
 * phone. Rather than tune one "large" treatment for the widest screen, we pick tokens from the available
 * size so each device reads as a deliberate layout.
 *
 * [height] guards the width-only trap: a landscape phone is 600–950dp *wide* but only ~360–450dp tall — the
 * Portal tiers' chrome (overlay reserve for pills the phone doesn't have, 56sp clock, 64dp controls) would
 * eat most of that. Any canvas shorter than 600dp gets the phone tier; every real Portal is ≥800dp tall in
 * both orientations, so only actual phones (and similar short windows) are affected.
 */
internal fun dimensFor(width: Dp, height: Dp = Dp.Infinity): Dimens = when {
    // Phone — single-column cards, tighter type. The platform already insets the status bar here, so the top
    // only needs modest breathing room rather than the full Portal overlay reserve.
    width < PhoneTierMax || height < PhoneTierMax -> Dimens(
        pagePadH = 16.dp, pageTop = 24.dp, pageBottom = 20.dp, headerGap = 16.dp,
        gridGap = 12.dp, gridMaxW = Dp.Infinity, minCardW = 160.dp, cardMinH = 132.dp, cardMaxH = 200.dp, skeletonCount = 4,
        singleColumn = true, cardPad = 16.dp, badge = 46.dp,
        cardName = 17.sp, cardNameLine = 21.sp, cardStatus = 14.sp,
        touchMinH = 52.dp,
        clock = 44.sp, amPm = 16.sp, amPmBaseline = 8.dp, greeting = 18.sp, date = 14.sp, summary = 16.sp,
    )
    // Portal 10" / Mini / Go (~1280dp).
    width < PortalPlusMinWidth -> Dimens(
        pagePadH = 32.dp, pageTop = PortalOverlayReserve, pageBottom = 24.dp, headerGap = 18.dp,
        gridGap = 16.dp, gridMaxW = Dp.Infinity, minCardW = 340.dp, cardMinH = 150.dp, cardMaxH = 200.dp, skeletonCount = 6,
        singleColumn = false, cardPad = 20.dp, badge = 56.dp,
        cardName = 20.sp, cardNameLine = 24.sp, cardStatus = 15.sp,
        touchMinH = PortalTouchTargetMin,
        clock = 56.sp, amPm = 18.sp, amPmBaseline = 10.dp, greeting = 20.sp, date = 15.sp, summary = 18.sp,
    )
    // Portal+ (~1920dp).
    else -> Dimens(
        pagePadH = 40.dp, pageTop = PortalOverlayReserve, pageBottom = 32.dp, headerGap = 20.dp,
        gridGap = 16.dp, gridMaxW = 1500.dp, minCardW = 340.dp, cardMinH = 150.dp, cardMaxH = 230.dp, skeletonCount = 6,
        singleColumn = false, cardPad = 22.dp, badge = 60.dp,
        cardName = 21.sp, cardNameLine = 25.sp, cardStatus = 15.sp,
        touchMinH = PortalTouchTargetMin,
        clock = 64.sp, amPm = 20.sp, amPmBaseline = 12.dp, greeting = 22.sp, date = 16.sp, summary = 19.sp,
    )
}

/**
 * The plug dashboard: a responsive grid of cards, each a large touch target that lights up green when on so
 * the room's state is glanceable from across it (the Portal is viewed at a distance). Header summarises how
 * many are on and offers a one-tap "All off"; tapping a card — or its Switch — toggles that plug.
 *
 * Layout adapts to the **available width** (via [BoxWithConstraints] → [dimensFor]) rather than assuming any
 * one Portal's screen, so it reads well across the whole family — the 1280-wide Portal 10" / Mini / Go, the
 * 1920-wide Portal+, and a phone — reflowing column count, spacing, and typography per tier. (`dp`/`sp`
 * already handle pixel density.)
 */
@Composable
fun PlugsScreen(viewModel: PlugsViewModel) {
    val plugs by viewModel.plugs.collectAsStateWithLifecycle()
    val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()

    // Report visibility so the re-sync backs off when the dashboard leaves the screen (see PlugsViewModel).
    // A configuration change (rotation) tears the composition down and back up; that is NOT the app leaving the
    // screen, so we must not report it as "hidden" — that would make the return look like a resume and trigger a
    // spurious rediscover.
    val activity = LocalContext.current.findActivity()
    LifecycleStartEffect(Unit) {
        viewModel.onVisibilityChanged(true)
        onStopOrDispose {
            if (activity?.isChangingConfigurations != true) viewModel.onVisibilityChanged(false)
        }
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        // remember: without it, every recomposition of this scope (each minute tick, each plugs emission)
        // builds a fresh Dimens whose new identity would defeat skipping in every child it's passed to.
        val d = remember(maxWidth, maxHeight) { dimensFor(maxWidth, maxHeight) }

        val now = rememberClock()
        val onCount = plugs.count { it.on }

        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = d.pagePadH)
                // top: reserve the Portal system-overlay strip so the clock / refresh clear the white pills.
                .padding(top = d.pageTop, bottom = d.pageBottom),
        ) {
            HeroHeader(
                d = d,
                now = now,
                onCount = onCount,
                total = plugs.size,
            )

            // Always present so the refresh affordance keeps a fixed home across the loading / empty / populated
            // states; the bulk-toggle pair only appears once there are plugs to act on.
            Spacer(Modifier.height(d.headerGap))
            ControlBar(
                d = d,
                anyOn = onCount > 0,
                anyOff = onCount < plugs.size,
                refreshing = refreshing,
                onAllOn = { viewModel.setAll(true) },
                onAllOff = { viewModel.setAll(false) },
                onRefresh = viewModel::refresh,
            )

            // Grid fills the remaining space as a balanced, centred block so the dashboard reads as a
            // deliberate layout rather than content huddled in one corner of the wide Portal screen.
            Box(Modifier.fillMaxWidth().weight(1f)) {
                when {
                    plugs.isEmpty() && refreshing -> LoadingGrid(d = d)
                    plugs.isEmpty() -> CenterState { EmptyState(d = d, onRetry = viewModel::refresh) }
                    else -> PlugGrid(plugs = plugs, d = d, onToggle = viewModel::toggle)
                }
            }
        }
    }
}

/** Unwrap the [Activity] from a (possibly wrapped) Compose [Context] — used to read `isChangingConfigurations`. */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
private fun HeroHeader(
    d: Dimens,
    now: LocalDateTime,
    onCount: Int,
    total: Int,
) {
    val subtle = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.fillMaxWidth()) {
        // Time on the left, passive status (date + on-count) on the right, sharing one row that is centred on
        // the time — so the date lines up beside the clock rather than floating above the tall glyph (whose
        // large line box otherwise pushes the small date up). The greeting drops below; the interactive refresh
        // lives in the ControlBar, keeping tap targets out of the top-right corner where Portal floats its pills.
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.Bottom) {
                Text(
                    now.format(TIME_FMT),
                    color = MaterialTheme.colorScheme.onBackground,
                    fontSize = d.clock,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    now.format(AMPM_FMT),
                    color = subtle,
                    fontSize = d.amPm,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(bottom = d.amPmBaseline),
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    now.format(DATE_FMT),
                    color = subtle,
                    fontSize = d.date,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    if (total == 0) "Searching your network…" else "$onCount of $total on",
                    color = if (onCount > 0) MaterialTheme.colorScheme.primary else subtle,
                    fontSize = d.summary,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        Text(
            greeting(now.hour),
            color = subtle,
            fontSize = d.greeting,
        )
    }
}

/**
 * The dashboard's single control band: bulk toggles grouped on the left (shown only when there are plugs to
 * act on), the utility refresh pushed to the far right. Consolidating every tap target into one row — instead
 * of scattering refresh into the header's top-right — gives one "controls" zone and one "status" zone, which
 * scans faster at Portal viewing distance and keeps interactive chrome clear of the system-overlay corner.
 */
@Composable
private fun ControlBar(
    d: Dimens,
    anyOn: Boolean,
    anyOff: Boolean,
    refreshing: Boolean,
    onAllOn: () -> Unit,
    onAllOff: () -> Unit,
    onRefresh: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(TouchGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // `anyOn || anyOff` is exactly "there are plugs" — false only when the list is empty.
        if (anyOn || anyOff) {
            Button(
                onClick = onAllOn,
                enabled = anyOff,
                modifier = Modifier.heightIn(min = d.touchMinH),
                // Tighter than M3's 24dp default so all three controls fit a narrow phone in one row.
                contentPadding = PaddingValues(horizontal = 16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            ) {
                Icon(Icons.Filled.Bolt, contentDescription = null, modifier = Modifier.size(ActionIcon))
                Spacer(Modifier.width(6.dp))
                Text("All on")
            }
            OutlinedButton(
                onClick = onAllOff,
                enabled = anyOn,
                modifier = Modifier.heightIn(min = d.touchMinH),
                contentPadding = PaddingValues(horizontal = 16.dp),
            ) {
                Icon(Icons.Filled.PowerSettingsNew, contentDescription = null, modifier = Modifier.size(ActionIcon))
                Spacer(Modifier.width(6.dp))
                Text("All off")
            }
        }
        // Push refresh to the far right; the wide gap separates it from the bulk pair so it reads as a distinct
        // utility action rather than a third "All …" toggle.
        Spacer(Modifier.weight(1f))
        RefreshButton(minH = d.touchMinH, refreshing = refreshing, onRefresh = onRefresh)
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
 * Labelled refresh control, outlined like "All off" so it visibly belongs to the control band (a borderless
 * text button disappears against the dark background at Portal viewing distance); the filled "All on" stays
 * the single strongest control. The label never changes — activity is narrated by the spinning icon and the
 * header's "Searching your network…", so the end-aligned button doesn't resize (and shift its tap target)
 * mid-interaction. It spins **only while discovering**: the spinning transition is created inside the
 * `refreshing` branch so it lives in composition just for that window — when idle there is no running
 * animation, so the screen stops requesting frames and the app goes fully idle (a perpetual animation would
 * pin the compositor at 60fps and drag the whole device down).
 */
@Composable
private fun RefreshButton(
    minH: Dp,
    refreshing: Boolean,
    onRefresh: () -> Unit,
) {
    OutlinedButton(
        onClick = onRefresh,
        modifier = Modifier.heightIn(min = minH),
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
            // Angle is read only inside graphicsLayer, so each spin frame skips recomposition.
            Icon(
                Icons.Filled.Refresh,
                contentDescription = null,
                modifier = Modifier.size(ActionIcon).graphicsLayer { rotationZ = angle },
            )
        } else {
            Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(ActionIcon))
        }
        Spacer(Modifier.width(6.dp))
        Text("Refresh")
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
    d: Dimens,
    keyOf: (Int) -> Any = { it },
    content: @Composable (Int) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Cap the grid on very wide screens (Portal+) so cards keep a comfortable measure; the narrower Portals
        // and phone fall back to their full width (gridMaxW is unbounded there).
        val gridWidth = minOf(maxWidth, d.gridMaxW)
        // How many comfortable-width cards fit — the ceiling that keeps portrait / narrow canvases to 1–2
        // columns instead of crushing the wide card layout.
        val maxByWidth = fitCount(gridWidth, d.minCardW, d.gridGap)
        // How many rows fit before cards squash below cardMinH — the non-scrolling grid splits the height
        // equally, so past this the badge/switch would clip. columnCount adds columns rather than exceed it.
        val maxRows = fitCount(maxHeight, d.cardMinH, d.gridGap)
        val cols = columnCount(count, d.singleColumn, maxByWidth, maxRows)
        val rows = ((count + cols - 1) / cols).coerceAtLeast(1)
        // cardMinH is input to the column math (via maxRows), not a hard render minimum: in a pathologically
        // short window (maxRows = 1, many plugs) cards still share whatever height exists.
        val cardH = ((maxHeight - d.gridGap * (rows - 1)) / rows).coerceAtMost(d.cardMaxH)
        val cardW = (gridWidth - d.gridGap * (cols - 1)) / cols
        val blockH = cardH * rows + d.gridGap * (rows - 1)

        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                Modifier.width(gridWidth).height(blockH),
                verticalArrangement = Arrangement.spacedBy(d.gridGap),
            ) {
                for (row in 0 until rows) {
                    val start = row * cols
                    val end = minOf(start + cols, count)
                    Row(
                        Modifier.fillMaxWidth().weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(d.gridGap, Alignment.CenterHorizontally),
                    ) {
                        for (i in start until end) {
                            // Key by stable identity (the plug's ip) so per-card animation state moves with the
                            // plug when the list reorders — a plug appearing/dropping shifts positions, and
                            // without a key the colour animations would briefly belong to the wrong card.
                            key(keyOf(i)) {
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
    d: Dimens,
    onToggle: (String, Boolean) -> Unit,
) {
    BalancedGrid(count = plugs.size, d = d, keyOf = { plugs[it].ip }) { i ->
        val plug = plugs[i]
        PlugCard(plug = plug, d = d, onToggle = { on -> onToggle(plug.ip, on) })
    }
}

/**
 * How many gap-separated items of at least [minItem] fit in [available]: n items need n·minItem + (n−1)·[gap].
 * Used for both grid axes (columns from width, rows from height); never less than 1, so a canvas smaller than
 * one item still lays out. `internal` so the gap-aware math is unit-tested in PlugsLayoutTest.
 */
internal fun fitCount(available: Dp, minItem: Dp, gap: Dp): Int =
    ((available + gap) / (minItem + gap)).toInt().coerceAtLeast(1)

/**
 * Column count for the balanced grid. It aims for ≈2 rows as the plug count grows, but never exceeds
 * [maxByWidth] — how many comfortable-width cards actually fit — so a narrow / portrait canvas collapses to
 * 1–2 columns instead of slicing the wide card layout into unreadable slivers. [maxRows] pushes the other
 * way: the non-scrolling grid splits the height equally between rows, so once the count would squash cards
 * below their height floor, columns are added *past* the width cap — a narrow card is cramped, a short card
 * clips its badge and falls below the touch floor. `internal` (not `private`) so the breakpoint behaviour is
 * unit-tested in PlugsLayoutTest.
 */
internal fun columnCount(n: Int, singleColumn: Boolean, maxByWidth: Int, maxRows: Int = Int.MAX_VALUE): Int {
    if (n <= 1) return 1
    val cap = maxByWidth.coerceAtLeast(1)
    val base = if (singleColumn) {
        minOf(2, cap)
    } else {
        val rows = when {
            n <= 3 -> 1
            n <= 8 -> 2
            n <= 15 -> 3
            else -> 4
        }
        minOf((n + rows - 1) / rows, cap)
    }
    val byHeight = (n + maxRows - 1) / maxRows // fewest columns that keep rows within the height floor
    return maxOf(base, byHeight)
}

@Composable
private fun PlugCard(
    plug: Plug,
    d: Dimens,
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

    Box(
        Modifier
            .fillMaxSize()
            .clip(CardCorner)
            .background(cardBg)
            .border(Hairline, borderColor, CardCorner)
            .clickable { onToggle(!on) }
            .padding(d.cardPad),
    ) {
        // Shared by both layout branches so the two form factors can't drift apart in styling.
        val status = @Composable {
            Text(
                if (on) "On" else "Off",
                color = if (on) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = d.cardStatus,
                fontWeight = FontWeight.Medium,
            )
        }
        val name = @Composable {
            Text(
                plug.alias,
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = d.cardName,
                fontWeight = FontWeight.SemiBold,
                lineHeight = d.cardNameLine,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (d.singleColumn) {
            // Narrow: badge + switch on top, name/status anchored to the bottom. Names wrap to two lines.
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                    IconBadge(icon = iconFor(plug.alias), on = on, size = d.badge)
                    Switch(checked = on, onCheckedChange = onToggle)
                }
                Spacer(Modifier.weight(1f))
                name()
                Spacer(Modifier.height(3.dp))
                status()
            }
        } else {
            // Wide: badge, name/status, and switch in one vertically-centred row. The name wraps to two lines
            // and ellipsises beyond that — no marquee, since a perpetually-scrolling name would keep the
            // always-on Portal rendering frames (see the RefreshButton note on avoiding perpetual animation).
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                IconBadge(icon = iconFor(plug.alias), on = on, size = d.badge)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    name()
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
private fun LoadingGrid(d: Dimens) {
    val pulse = rememberInfiniteTransition(label = "skeleton")
    val alpha by pulse.animateFloat(
        initialValue = 0.4f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse),
        label = "pulse",
    )
    // The placeholder cards are decorative, so carry the loading message as the region's accessibility label
    // (a screen reader would otherwise hear nothing for this area — the old spinner had a visible caption).
    Box(
        Modifier
            .fillMaxSize()
            .semantics { contentDescription = "Looking for plugs on your Wi-Fi" },
    ) {
        BalancedGrid(count = d.skeletonCount, d = d) {
            SkeletonCard(d = d, pulseAlpha = { alpha })
        }
    }
}

/**
 * [pulseAlpha] is a lambda, not a value, so the pulse is read only inside [graphicsLayer] — each animation
 * frame then updates the layer directly instead of recomposing/redrawing the card, which matters during
 * startup when this animation runs concurrently with first-launch JIT and discovery. Mirrors [PlugCard]'s
 * two layouts (stacked on the phone tier, wide row elsewhere) so the placeholders settle into the same
 * rhythm as the cards that replace them.
 */
@Composable
private fun SkeletonCard(d: Dimens, pulseAlpha: () -> Float) {
    val ph = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.10f)
    Box(
        Modifier
            .fillMaxSize()
            .clip(CardCorner)
            .background(MaterialTheme.colorScheme.surface)
            .border(Hairline, MaterialTheme.colorScheme.outline, CardCorner)
            .padding(d.cardPad),
    ) {
        // The pulse fades only the blobs (the card chrome stays steady), so the layer wraps just the content.
        // ModulateAlpha folds the fade into each draw instead of an offscreen buffer (the default for
        // alpha < 1) — which also means the blobs must never overlap, or they'd double-darken.
        val pulseLayer = Modifier.fillMaxSize().graphicsLayer {
            alpha = pulseAlpha()
            compositingStrategy = CompositingStrategy.ModulateAlpha
        }
        val nameBlob = @Composable { Box(Modifier.fillMaxWidth(0.7f).height(18.dp).clip(RoundedCornerShape(6.dp)).background(ph)) }
        val statusBlob = @Composable { Box(Modifier.fillMaxWidth(0.3f).height(14.dp).clip(RoundedCornerShape(6.dp)).background(ph)) }

        if (d.singleColumn) {
            // Stacked, like the phone-tier card: badge top-left, name/status anchored to the bottom.
            Column(pulseLayer) {
                Box(Modifier.size(d.badge).clip(CircleShape).background(ph))
                Spacer(Modifier.weight(1f))
                nameBlob()
                Spacer(Modifier.height(10.dp))
                statusBlob()
            }
        } else {
            Row(pulseLayer, verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(d.badge).clip(CircleShape).background(ph))
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    nameBlob()
                    Spacer(Modifier.height(10.dp))
                    statusBlob()
                }
            }
        }
    }
}

/**
 * The "no plugs found" recovery state — a centred message the user reads up close while already interacting
 * with the device, so its type is intentionally a fixed size rather than tier-scaled like the dashboard chrome
 * (only its CTA borrows the responsive touch-target floor).
 */
@Composable
private fun EmptyState(d: Dimens, onRetry: () -> Unit) {
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
        // A prominent CTA at the touch-target floor — this is the one action in the state where the user is stuck.
        Button(onClick = onRetry, modifier = Modifier.heightIn(min = d.touchMinH)) { Text("Search again") }
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
                modifier = Modifier.size(ActionIcon),
            )
            Text(
                "Tip: turn on \"Kasa Plugs\" in Assistant → External tools to control plugs by voice.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 14.sp,
            )
        }
    }
}
