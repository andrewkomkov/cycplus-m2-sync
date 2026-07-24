package dev.komkov.m2sync

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Terrain
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

private val dayFormat: DateTimeFormatter =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
private val timeFormat: DateTimeFormatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)

/** Рисунок велокомпьютера: корпус, экран с зарядом и кольцо батареи вокруг. */
@Composable
fun DeviceArt(battery: Int?, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Box(modifier.size(118.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(118.dp)) {
            val body = Size(size.minDimension * 0.5f, size.minDimension * 0.68f)
            val left = (size.width - body.width) / 2
            val top = (size.height - body.height) / 2

            drawRoundRect(
                color = scheme.surfaceVariant,
                topLeft = Offset(left, top),
                size = body,
                cornerRadius = CornerRadius(22f, 22f),
            )
            drawRoundRect(
                color = scheme.outlineVariant,
                topLeft = Offset(left, top),
                size = body,
                cornerRadius = CornerRadius(22f, 22f),
                style = Stroke(width = 3f),
            )
            val screenInset = body.width * 0.12f
            drawRoundRect(
                color = scheme.primaryContainer,
                topLeft = Offset(left + screenInset, top + screenInset),
                size = Size(body.width - screenInset * 2, body.height * 0.62f),
                cornerRadius = CornerRadius(12f, 12f),
            )
            val buttonsY = top + body.height * 0.86f
            listOf(0.3f, 0.5f, 0.7f).forEach { fx ->
                drawCircle(
                    color = scheme.outline,
                    radius = body.width * 0.055f,
                    center = Offset(left + body.width * fx, buttonsY),
                )
            }

            val ring = size.minDimension - 8f
            val ringTopLeft = Offset((size.width - ring) / 2, (size.height - ring) / 2)
            drawArc(
                color = scheme.surfaceVariant,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = ringTopLeft,
                size = Size(ring, ring),
                style = Stroke(width = 7f, cap = StrokeCap.Round),
            )
            if (battery != null) {
                drawArc(
                    color = scheme.primary,
                    startAngle = -90f,
                    sweepAngle = 360f * (battery / 100f),
                    useCenter = false,
                    topLeft = ringTopLeft,
                    size = Size(ring, ring),
                    style = Stroke(width = 7f, cap = StrokeCap.Round),
                )
            }
        }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(bottom = 14.dp),
        ) {
            Text(
                battery?.let { "$it%" } ?: stringResource(R.string.dash),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text(
                stringResource(R.string.battery_caption),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

@Composable
fun DeviceCard(
    device: DeviceSnapshot?,
    busy: Boolean,
    action: String?,
    modifier: Modifier = Modifier,
) {
    ElevatedCard(modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(20.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DeviceArt(device?.battery)
            Spacer(Modifier.width(20.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    device?.name ?: stringResource(R.string.device_unknown),
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    device?.address ?: stringResource(R.string.device_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))

                device?.firmware?.let {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.firmware_label) + ": ",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(it, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (device?.freeKb != null && device.totalKb != null) {
                    val used = device.usedKb ?: 0
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.memory_line, used, device.totalKb),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = { used.toFloat() / device.totalKb.toFloat() },
                        modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                    )
                }
                device?.seenAt?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(
                            R.string.last_seen,
                            timeFormat.format(it.atZone(ZoneId.systemDefault())),
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        AnimatedVisibility(busy) {
            Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 16.dp)) {
                Text(
                    stringResource(
                        when (action) {
                            "SYNC" -> R.string.busy_sync
                            "INFO" -> R.string.busy_info
                            "IMPORT" -> R.string.busy_import
                            "VERIFY" -> R.string.busy_verify
                            "SCAN" -> R.string.busy_scan
                            else -> R.string.busy_other
                        }
                    ),
                    style = MaterialTheme.typography.labelMedium,
                )
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
fun TotalsRow(rides: List<RideSummary>, modifier: Modifier = Modifier) {
    val km = rides.sumOf { it.distanceM } / 1000
    val hours = rides.sumOf { it.movingMin } / 60.0
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        StatTile(stringResource(R.string.stat_rides), rides.size.toString(), Modifier.weight(1f))
        StatTile(
            stringResource(R.string.stat_km),
            String.format(Locale.getDefault(), "%.0f", km),
            Modifier.weight(1f),
        )
        StatTile(
            stringResource(R.string.stat_hours),
            String.format(Locale.getDefault(), "%.1f", hours),
            Modifier.weight(1f),
        )
        StatTile(
            stringResource(R.string.stat_in_health),
            rides.count { it.imported }.toString(),
            Modifier.weight(1f),
        )
    }
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(
        modifier,
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Column(
            Modifier.padding(vertical = 14.dp, horizontal = 8.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                value,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

@Composable
fun ActionsRow(
    onSync: () -> Unit,
    onInfo: () -> Unit,
    onVerify: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onSync, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Rounded.Sync, null, Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.btn_sync), maxLines = 1)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = onInfo, enabled = enabled, modifier = Modifier.weight(1f)) {
                Icon(Icons.Rounded.Bluetooth, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.btn_poll), maxLines = 1)
            }
            OutlinedButton(onClick = onVerify, enabled = enabled, modifier = Modifier.weight(1f)) {
                Icon(Icons.Rounded.CheckCircle, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.btn_verify), maxLines = 1)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RideCard(
    ride: RideSummary,
    selected: Boolean,
    selectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onShare: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceContainerHigh
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (selectionMode) {
                    Icon(
                        if (selected) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                        null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(12.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(
                            R.string.ride_distance,
                            String.format(Locale.getDefault(), "%.2f", ride.distanceM / 1000),
                        ),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        dayFormat.format(ride.start.atZone(ZoneId.systemDefault())),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (ride.imported) {
                    Icon(
                        Icons.Rounded.CloudUpload,
                        stringResource(R.string.cd_in_health),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                if (!selectionMode) {
                    IconButton(onClick = onShare) {
                        Icon(Icons.Rounded.Share, stringResource(R.string.cd_share))
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip(Icons.Rounded.Timer, stringResource(R.string.chip_moving, ride.movingMin))
                Chip(Icons.Rounded.Schedule, stringResource(R.string.chip_total, ride.elapsedMin))
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ride.avgHeartRate?.let { Chip(Icons.Rounded.Favorite, stringResource(R.string.chip_hr, it)) }
                ride.avgCadence?.let { Chip(Icons.Rounded.Refresh, stringResource(R.string.chip_cadence, it)) }
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ride.ascent?.takeIf { it > 0 }?.let {
                    Chip(Icons.Rounded.Terrain, stringResource(R.string.chip_ascent, it))
                }
                if (ride.hasRoute) {
                    Chip(Icons.Rounded.Route, stringResource(R.string.chip_points, ride.points))
                }
            }
        }
    }
}

@Composable
private fun Chip(icon: ImageVector, label: String) {
    AssistChip(
        onClick = {},
        label = { Text(label, style = MaterialTheme.typography.labelMedium) },
        leadingIcon = { Icon(icon, null, Modifier.size(16.dp)) },
        colors = AssistChipDefaults.assistChipColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLowest
        ),
    )
}

@Composable
fun LogCard(lines: List<String>, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    Card(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.log_title),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            Text(
                lines.lastOrNull()?.take(40) ?: stringResource(R.string.log_empty),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null)
        }
        AnimatedVisibility(expanded) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .height(260.dp)
                    .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                    .verticalScroll(rememberScrollState())
                    .padding(12.dp)
            ) {
                Text(
                    lines.takeLast(120).joinToString("\n"),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                )
            }
        }
    }
}

@Composable
fun RidesList(
    rides: List<RideSummary>,
    selection: Set<String>,
    onToggle: (RideSummary) -> Unit,
    onLongClick: (RideSummary) -> Unit,
    onShare: (RideSummary) -> Unit,
    header: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { header() }
        if (rides.isEmpty()) {
            item {
                Text(
                    stringResource(R.string.rides_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 24.dp),
                )
            }
        }
        items(rides, key = { it.file }) { ride ->
            RideCard(
                ride = ride,
                selected = ride.file in selection,
                selectionMode = selection.isNotEmpty(),
                onClick = { onToggle(ride) },
                onLongClick = { onLongClick(ride) },
                onShare = { onShare(ride) },
            )
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}
