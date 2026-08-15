package com.example.mediasessiontest.ui

import android.media.session.MediaController
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.mediasessiontest.data.TelemetryState

@Composable
fun DeveloperUiView(
    telemetryState: TelemetryState,
    selectedController: MediaController?,
    onInjectDrift: (Double) -> Unit,
    onToggleMode: () -> Unit
) {
    val darkBackground = Color(0xFF090D16)
    val cardBackground = Color(0xFF131C2E)
    val devAccent = Color(0xFF10B981)
    val devAmber = Color(0xFFF59E0B)
    val textPrimary = Color(0xFFF8FAFC)
    val textSecondary = Color(0xFF94A3B8)
    val codeFont = FontFamily.Monospace

    var selectedTab by remember { mutableStateOf(0) }
    val tabs = listOf("Telemetry", "Event Logs", "Simulators")

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(darkBackground)
            .padding(16.dp)
    ) {
        // --- Developer Header ---
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    tint = devAccent,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "DEV CONTROL CENTER",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontFamily = codeFont,
                        fontWeight = FontWeight.Bold
                    ),
                    color = devAccent
                )
            }

            Surface(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .clickable { onToggleMode() },
                color = cardBackground,
                border = androidx.compose.foundation.BorderStroke(1.dp, devAccent)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "DEV VIEW",
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontFamily = codeFont,
                            fontWeight = FontWeight.Bold
                        ),
                        color = devAccent
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Switch Mode",
                        tint = textSecondary,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // --- Dev Navigation Tabs ---
        TabRow(
            selectedTabIndex = selectedTab,
            containerColor = cardBackground,
            contentColor = devAccent
        ) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTab == index,
                    onClick = { selectedTab = index },
                    text = {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.labelMedium.copy(fontFamily = codeFont),
                            fontWeight = if (selectedTab == index) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        when (selectedTab) {
            0 -> TelemetryTab(telemetryState, devAccent, devAmber, cardBackground, textPrimary, textSecondary, codeFont)
            1 -> EventLogsTab(telemetryState, cardBackground, textPrimary, textSecondary, codeFont)
            2 -> SimulatorsTab(telemetryState, onInjectDrift, devAccent, devAmber, cardBackground, textPrimary, textSecondary, codeFont)
        }
    }
}

@Composable
fun TelemetryTab(
    telemetryState: TelemetryState,
    devAccent: Color,
    devAmber: Color,
    cardBackground: Color,
    textPrimary: Color,
    textSecondary: Color,
    codeFont: FontFamily
) {
    Column {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = cardBackground)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "LIVE DRIFT METRICS",
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = codeFont),
                    color = textSecondary
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = String.format("%.1f ms", telemetryState.currentDriftMs),
                        style = MaterialTheme.typography.headlineMedium.copy(
                            fontFamily = codeFont,
                            fontWeight = FontWeight.Bold
                        ),
                        color = if (Math.abs(telemetryState.currentDriftMs) < 80) devAccent else devAmber
                    )

                    Text(
                        text = if (Math.abs(telemetryState.currentDriftMs) < 80) "Status: OPTIMAL" else "Status: CORRECTING",
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = codeFont),
                        color = if (Math.abs(telemetryState.currentDriftMs) < 80) devAccent else devAmber
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Canvas Graph for Drift History
                Text(
                    text = "DRIFT OFFSET SPARKLINE",
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = codeFont),
                    color = textSecondary
                )
                Spacer(modifier = Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(100.dp)
                        .background(Color(0xFF0B1320), RoundedCornerShape(8.dp))
                        .padding(8.dp)
                ) {
                    val points = telemetryState.driftHistory.toList()
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        if (points.size > 1) {
                            val maxVal = points.maxOfOrNull { Math.abs(it) }?.coerceAtLeast(100.0) ?: 100.0
                            val widthStep = size.width / (points.size - 1)
                            val midY = size.height / 2

                            val path = Path()
                            points.forEachIndexed { i, valMs ->
                                val x = i * widthStep
                                val y = midY - ((valMs / maxVal) * (size.height / 2)).toFloat()
                                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                            }
                            drawPath(path, color = devAccent, style = Stroke(width = 3f))
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = cardBackground)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "NETWORK LATENCY (RTT)",
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = codeFont),
                    color = textSecondary
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "${telemetryState.currentRttMs} ms",
                    style = MaterialTheme.typography.headlineSmall.copy(
                        fontFamily = codeFont,
                        fontWeight = FontWeight.Bold
                    ),
                    color = textPrimary
                )
            }
        }
    }
}

@Composable
fun EventLogsTab(
    telemetryState: TelemetryState,
    cardBackground: Color,
    textPrimary: Color,
    textSecondary: Color,
    codeFont: FontFamily
) {
    Card(
        modifier = Modifier.fillMaxSize(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = cardBackground)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "WEBSOCKET EVENT CONSOLE",
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = codeFont),
                color = textSecondary
            )
            Spacer(modifier = Modifier.height(8.dp))

            val logs = telemetryState.eventLogs.reversed()
            if (logs.isEmpty()) {
                Text(
                    text = "No events logged yet.",
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = codeFont),
                    color = textSecondary,
                    modifier = Modifier.padding(top = 16.dp)
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(logs) { entry ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (entry.direction == "IN") "▼ IN" else "▲ OUT",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = codeFont,
                                    fontWeight = FontWeight.Bold
                                ),
                                color = if (entry.direction == "IN") Color(0xFF38BDF8) else Color(0xFFF472B6)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = entry.eventType,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = codeFont,
                                    fontWeight = FontWeight.Bold
                                ),
                                color = textPrimary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = entry.payload,
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = codeFont),
                                color = textSecondary,
                                maxLines = 1
                            )
                        }
                        HorizontalDivider(color = Color(0xFF1E293B))
                    }
                }
            }
        }
    }
}

@Composable
fun SimulatorsTab(
    telemetryState: TelemetryState,
    onInjectDrift: (Double) -> Unit,
    devAccent: Color,
    devAmber: Color,
    cardBackground: Color,
    textPrimary: Color,
    textSecondary: Color,
    codeFont: FontFamily
) {
    Column {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = cardBackground)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "SIMULATE DRIFT INJECTION",
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = codeFont),
                    color = devAmber
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Force local timeline offset to verify drift correction algorithm",
                    style = MaterialTheme.typography.bodySmall,
                    color = textSecondary
                )
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    Button(
                        onClick = { onInjectDrift(-2.0) },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626))
                    ) {
                        Text("-2.0s Drift", fontFamily = codeFont)
                    }

                    Button(
                        onClick = { onInjectDrift(2.0) },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB))
                    ) {
                        Text("+2.0s Drift", fontFamily = codeFont)
                    }
                }
            }
        }
    }
}
