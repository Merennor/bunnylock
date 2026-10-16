@file:Suppress("FunctionName")

package dev.ririxi.bunnylock.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.ririxi.bunnylock.data.Carrier
import dev.ririxi.bunnylock.data.Cell
import dev.ririxi.bunnylock.data.Rat
import dev.ririxi.bunnylock.data.Signal
import dev.ririxi.bunnylock.modem.ModemState
import dev.ririxi.bunnylock.modem.andList
import dev.ririxi.bunnylock.modem.formatPlmn
import dev.ririxi.bunnylock.ui.theme.LocalLockGreen
import dev.ririxi.bunnylock.ui.theme.LocalSignalScale

private val RSRP_STEPS = listOf(-80, -90, -100)
private val PLAIN_DBM_STEPS = listOf(-70, -85, -100)
private val RSRQ_STEPS = listOf(-10, -15, -20)
private val SINR_STEPS = listOf(20, 13, 0)

@Composable
fun HomeScreen(
    state: UiState,
    vm: MainViewModel,
    contentPadding: PaddingValues,
    goTo: (Tab) -> Unit,
) {
    LazyColumn(contentPadding = contentPadding.withScreenMargins(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Hero(state, onUnlock = vm::unlockEverything, onBands = { goTo(Tab.BANDS) }, onCell = { goTo(Tab.CELL) }) }

        val carriers = state.modem.carriers
        item {
            SectionHeader("Connection")
            if (carriers.isEmpty()) Panel { SleepyBunny("No serving cell") } else ConnectionCard(state.modem)
        }

        if (carriers.isNotEmpty()) {
            item {
                val anchoredOnLte = carriers.any { it.primary && it.rat == Rat.LTE }
                SectionHeader(if (carriers.size > 1) "Carriers" else "Carrier")
                Segmented(Modifier.animateContentSize()) {
                    carriers.forEach { carrier -> item { shapes -> CarrierItem(shapes, carrier, anchoredOnLte) } }
                }
            }
        }

        item {
            val device = state.modem.device
            val deviceName = "${device?.manufacturer.orEmpty()} ${device?.model.orEmpty()}".trim()
            val platform =
                listOfNotNull(
                    device?.socModel?.ifEmpty { null },
                    device?.platform?.ifEmpty { null },
                ).joinToString(", ")
            SectionHeader("Modem")
            Segmented {
                item { InfoItem(it, "Chipset", device?.vendorLabel.orEmpty()) }
                item { InfoItem(it, "Device", deviceName) }
                item { InfoItem(it, "Platform", platform) }
                item { InfoItem(it, "Baseband", device?.baseband.orEmpty()) }
            }
        }
    }
}

@Composable
private fun Hero(
    state: UiState,
    onUnlock: () -> Unit,
    onBands: () -> Unit,
    onCell: () -> Unit,
) {
    val locked = state.modem.anyBandLock || state.modem.anyCellLock || state.modem.anyPlmnLock
    val strayLocation =
        state.modem.strayBand?.let { "$it, outside the locked bands" }
            ?: state.modem.strayChannel?.let { "$it, off the locked frequency" }
    val lockHeld = locked && strayLocation === null
    val green = LocalLockGreen.current
    val containerColor by animateColorAsState(
        if (lockHeld) {
            green.copy(alpha = 0.2f).compositeOver(MaterialTheme.colorScheme.surfaceContainerHigh)
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        label = "hero",
    )
    Surface(color = containerColor, shape = RoundedCornerShape(36.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        when {
                            strayLocation !== null -> "Ignored"
                            locked -> "Locked"
                            else -> "Free"
                        },
                        style = MaterialTheme.typography.displaySmallEmphasized.copy(fontWeight = FontWeight.Bold),
                        color =
                            when {
                                strayLocation !== null -> MaterialTheme.colorScheme.error
                                locked -> green
                                else -> MaterialTheme.colorScheme.onSurface
                            },
                    )
                    Text(
                        if (strayLocation !== null) "The phone is on $strayLocation." else pinnedSummary(state),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                BouncyLockBadge(locked = locked, ignored = strayLocation !== null, userAction = state.busy, size = 88.dp)
            }

            val lockRows = activeLockRows(state)
            if (lockRows.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    lockRows.forEach { (label, value) ->
                        Row {
                            Text(label, style = MaterialTheme.typography.labelLargeEmphasized, modifier = Modifier.width(96.dp))
                            Text(value, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val buttonHeight = ButtonDefaults.MediumContainerHeight
                if (locked) {
                    Button(
                        onClick = onUnlock,
                        enabled = !state.busy,
                        shapes = ButtonDefaults.shapes(),
                        modifier = Modifier.fillMaxWidth().height(buttonHeight),
                        contentPadding = ButtonDefaults.contentPaddingFor(buttonHeight),
                        colors =
                            ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                                contentColor = MaterialTheme.colorScheme.onError,
                            ),
                    ) {
                        Icon(
                            BunnyIcons.LockOpen,
                            contentDescription = null,
                            modifier = Modifier.size(ButtonDefaults.iconSizeFor(buttonHeight)),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text("Unlock all", style = ButtonDefaults.textStyleFor(buttonHeight), maxLines = 1)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = onBands, shapes = ButtonDefaults.shapes(), modifier = Modifier.weight(1f)) { Text("Bands") }
                    FilledTonalButton(onClick = onCell, shapes = ButtonDefaults.shapes(), modifier = Modifier.weight(1f)) { Text("Cell") }
                }
            }
        }
    }
}

private fun pinnedSummary(state: UiState): String {
    val pinned =
        listOfNotNull(
            "bands".takeIf { state.modem.anyBandLock },
            "cell".takeIf { state.modem.anyCellLock },
            "network".takeIf { state.modem.anyPlmnLock },
        )
    val takesSingularVerb = pinned == listOf("cell") || pinned == listOf("network")
    return when {
        pinned.isEmpty() -> "Nothing is pinned."
        takesSingularVerb -> "The ${pinned[0]} is pinned."
        else -> "The ${andList(pinned)} are pinned."
    }
}

private fun activeLockRows(state: UiState): List<Pair<String, String>> =
    buildList {
        state.modem.groups.filter { it.locked }.forEach { bandGroup ->
            val bands = bandGroup.current!!.sorted().joinToString(", ") { bandGroup.group.bandLabel(it) }
            add(bandGroup.group.label to bands)
        }
        state.modem.plmnLock?.let { add("Network" to it) }
        state.modem.lteLock?.let { add("LTE cell" to it) }
        state.modem.nrLock?.let { add("NR cell" to it) }
        state.modem.legacyCellLock?.let { add("2G/3G cell" to it) }
    }

private fun channelName(rat: Rat) =
    when (rat) {
        Rat.LTE -> "EARFCN"
        Rat.WCDMA -> "UARFCN"
        else -> "ARFCN"
    }

private fun megahertz(khz: Int): String = if (khz % 1000 == 0) "${khz / 1000}" else "%.1f".format(khz / 1000.0)

private fun techSummary(carriers: List<Carrier>): String {
    val lteCount = carriers.count { it.rat == Rat.LTE }
    val technologies =
        listOfNotNull(
            when {
                lteCount > 1 -> "LTE-A"
                lteCount == 1 -> "LTE"
                else -> null
            },
            when {
                carriers.none { it.rat == Rat.NR } -> null
                lteCount > 0 -> "5G NSA"
                else -> "5G SA"
            },
            "3G".takeIf { carriers.any { it.rat == Rat.WCDMA } },
            "2G".takeIf { carriers.any { it.rat == Rat.GSM } },
        )
    val count = if (carriers.size == 1) "1 carrier" else "${carriers.size} carriers"
    return "${technologies.joinToString(" + ")}, $count"
}

private fun totalBandwidth(carriers: List<Carrier>): String? {
    val known = carriers.mapNotNull { it.bandwidthKhz }
    if (known.isEmpty()) return null
    val more = if (known.size < carriers.size) "+" else ""
    return "${megahertz(known.sum())}$more MHz"
}

@Composable
private fun signalColor(
    value: Int,
    steps: List<Int>,
): Color {
    val step = steps.indexOfFirst { value >= it }
    return LocalSignalScale.current[if (step < 0) steps.size else step]
}

// 2G and 3G report RSSI, which runs higher than RSRP.
private fun dbmSteps(rat: Rat) = if (rat == Rat.LTE || rat == Rat.NR) RSRP_STEPS else PLAIN_DBM_STEPS

@Composable
private fun ratColor(rat: Rat): Color =
    when (rat) {
        Rat.NR -> MaterialTheme.colorScheme.primary
        Rat.LTE -> MaterialTheme.colorScheme.tertiary
        Rat.WCDMA -> MaterialTheme.colorScheme.secondary
        Rat.GSM -> MaterialTheme.colorScheme.outline
    }

@Composable
private fun ConnectionCard(modem: ModemState) {
    val carriers = modem.carriers
    val network = modem.registeredName ?: modem.registeredPlmn?.let(::formatPlmn) ?: "Unknown network"
    val primaryRat = carriers.first().rat
    val signals = modem.signals.filter { it.rsrp !== null || it.rsrq !== null || it.sinr !== null }.sortedBy { it.rat != primaryRat }
    Panel(Modifier.animateContentSize(), padding = PaddingValues(20.dp)) {
        Row {
            Column(Modifier.weight(1f)) {
                Text(network, style = MaterialTheme.typography.titleMediumEmphasized)
                Text(techSummary(carriers), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            totalBandwidth(carriers)?.let { total ->
                Column(horizontalAlignment = Alignment.End) {
                    AnimatedContent(
                        targetState = total,
                        transitionSpec = { (slideInVertically { it / 2 } + fadeIn()) togetherWith (slideOutVertically { -it / 2 } + fadeOut()) },
                        label = "total bandwidth",
                    ) { shown -> Text(shown, style = MaterialTheme.typography.headlineSmallEmphasized) }
                    Text("total bandwidth", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (carriers.all { it.bandwidthKhz !== null }) BandwidthBar(carriers)
        signals.forEach { signal -> SignalRow(signal, showRat = signals.size > 1) }
    }
}

@Composable
private fun BandwidthBar(carriers: List<Carrier>) {
    Row(Modifier.fillMaxWidth().height(10.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        val motion = MaterialTheme.motionScheme
        carriers.forEach { carrier ->
            val width = carrier.bandwidthKhz ?: return@forEach
            key(carrier.rat, carrier.arfcn, carrier.pci) {
                val shownWidth = remember { Animatable(0f) }
                LaunchedEffect(width) { shownWidth.animateTo(width.toFloat(), motion.defaultSpatialSpec()) }
                val color by animateColorAsState(ratColor(carrier.rat).copy(alpha = if (carrier.primary) 1f else 0.55f), label = "carrier")
                Box(
                    Modifier
                        .weight(shownWidth.value.coerceAtLeast(1f))
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(5.dp))
                        .background(color),
                )
            }
        }
    }
}

@Composable
private fun SignalRow(
    signal: Signal,
    showRat: Boolean,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
        if (showRat) RatBadge(signal.rat, size = 32.dp)
        signal.rsrp?.let { Metric("RSRP", "$it dBm", signalColor(it, RSRP_STEPS)) }
        signal.rsrq?.let { Metric("RSRQ", "$it dB", signalColor(it, RSRQ_STEPS)) }
        signal.sinr?.let { Metric("SINR", "$it dB", signalColor(it, SINR_STEPS)) }
    }
}

@Composable
private fun Metric(
    label: String,
    value: String,
    color: Color,
) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleMediumEmphasized, color = animateColorAsState(color, label = label).value)
    }
}

@Composable
private fun Tag(
    text: String,
    container: Color,
    content: Color,
) {
    Surface(color = container, contentColor = content, shape = RoundedCornerShape(8.dp)) {
        Text(text, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
    }
}

@Composable
private fun SignalPill(
    dbm: Int,
    rat: Rat,
) {
    val color by animateColorAsState(signalColor(dbm, dbmSteps(rat)), label = "signal")
    Surface(color = color.copy(alpha = 0.16f), contentColor = color, shape = RoundedCornerShape(50)) {
        Text("$dbm dBm", style = MaterialTheme.typography.labelLargeEmphasized, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
    }
}

@Composable
private fun RoleTag(
    carrier: Carrier,
    anchoredOnLte: Boolean,
) {
    val colors = MaterialTheme.colorScheme
    when {
        carrier.primary -> Tag("Primary", colors.secondaryContainer, colors.onSecondaryContainer)
        carrier.rat == Rat.NR && anchoredOnLte -> Tag("5G NSA", colors.primaryContainer, colors.onPrimaryContainer)
        else -> Tag("Secondary", colors.surfaceContainerHighest, colors.onSurfaceVariant)
    }
}

@Composable
private fun CellTag(cell: Cell) {
    val colors = MaterialTheme.colorScheme
    when {
        cell.note == "NSA SCG" -> Tag("5G NSA", colors.primaryContainer, colors.onPrimaryContainer)
        cell.registered -> Tag("Serving", colors.secondaryContainer, colors.onSecondaryContainer)
    }
}

private fun radioDetails(
    rat: Rat,
    arfcn: Long?,
    pci: Int?,
    bandwidthKhz: Int?,
): String =
    listOfNotNull(
        arfcn?.let { "${channelName(rat)} $it" },
        pci?.let { "PCI $it" },
        bandwidthKhz?.let { "${megahertz(it)} MHz" },
    ).joinToString(" · ")

@Composable
private fun RadioRow(
    shapes: ListItemShapes,
    rat: Rat,
    title: String,
    frequencyName: String?,
    details: String,
    rsrp: Int?,
    tag: @Composable () -> Unit,
    onClick: (() -> Unit)? = null,
) {
    val leading: @Composable () -> Unit = { RatBadge(rat) }
    val trailing: @Composable () -> Unit = { rsrp?.let { SignalPill(it, rat) } }
    val supporting: (@Composable () -> Unit)? = if (details.isEmpty()) null else ({ Text(details) })
    val content: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLargeEmphasized)
            frequencyName?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            tag()
        }
    }
    if (onClick !== null) {
        SegmentedListItem(
            onClick = onClick,
            shapes = shapes,
            colors = segmentColors(),
            leadingContent = leading,
            trailingContent = trailing,
            supportingContent = supporting,
            content = content,
        )
    } else {
        SegmentedListItem(
            shapes = shapes,
            colors = segmentColors(),
            leadingContent = leading,
            trailingContent = trailing,
            supportingContent = supporting,
            content = content,
        )
    }
}

@Composable
private fun CarrierItem(
    shapes: ListItemShapes,
    carrier: Carrier,
    anchoredOnLte: Boolean,
) = RadioRow(
    shapes,
    carrier.rat,
    if (carrier.band !== null) carrier.bandLabel else "Unknown band",
    carrier.frequencyName,
    radioDetails(carrier.rat, carrier.arfcn, carrier.pci, carrier.bandwidthKhz),
    carrier.rsrp,
    tag = { RoleTag(carrier, anchoredOnLte) },
)

@Composable
fun CellItem(
    shapes: ListItemShapes,
    cell: Cell,
    onClick: (() -> Unit)? = null,
) = RadioRow(
    shapes,
    cell.rat,
    if (cell.band !== null) cell.bandLabel else "Unknown band",
    cell.frequencyName,
    radioDetails(cell.rat, cell.arfcn, cell.pci.takeIf { it >= 0 }, cell.bandwidthKhz),
    cell.rsrp,
    tag = { CellTag(cell) },
    onClick = onClick,
)
