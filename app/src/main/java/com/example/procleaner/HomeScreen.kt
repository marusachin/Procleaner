package com.example.procleaner

import android.app.ActivityManager
import android.os.Environment
import android.os.StatFs
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
fun Ring(frac: Float, label: String, sub: String) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    val arc = MaterialTheme.colorScheme.primary
    Box(Modifier.size(150.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize().padding(10.dp)) {
            drawArc(track, 135f, 270f, false, style = Stroke(width = 24f, cap = StrokeCap.Round))
            drawArc(arc, 135f, 270f * frac.coerceIn(0f, 1f), false, style = Stroke(width = 24f, cap = StrokeCap.Round))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(sub, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun MetricCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun HomeScreen(state: CleanState, resumes: Int, onScan: () -> Unit, onOpenClean: () -> Unit, onBoost: (Int) -> Unit) {
    val ctx = LocalContext.current
    val access = remember(resumes) { hasAccess(ctx) }
    val base = remember { Environment.getExternalStorageDirectory().path }
    val storage = StatFs(Environment.getDataDirectory().path)
    val total = storage.totalBytes.coerceAtLeast(1L)
    val free = storage.availableBytes
    val used = total - free
    val memory = ActivityManager.MemoryInfo().also {
        ctx.getSystemService(ActivityManager::class.java).getMemoryInfo(it)
    }
    val grouped = state.items.groupBy { it.cat }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 18.dp, top = 22.dp, end = 18.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Your phone, at a glance", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("See what’s using space and choose what to tidy.", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        item {
            Card(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth().padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Ring(used.toFloat() / total, "${used * 100 / total}%", "in use")
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Phone storage", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text("${fmt(ctx, free)} free of ${fmt(ctx, total)}",
                            style = MaterialTheme.typography.bodyMedium)
                        Text("Cleaning is always your choice. Review items before removing anything.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MetricCard("Available storage", fmt(ctx, free), Modifier.weight(1f))
                MetricCard("Memory in use", fmt(ctx, memory.totalMem - memory.availMem), Modifier.weight(1f))
            }
        }

        if (free * 100 / total < 15) item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Storage is getting low", style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onErrorContainer)
                    Text("${free * 100 / total}% is available. Scan to review large and temporary files.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
                }
            }
        }

        item {
            Text("Quick actions", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilledTonalButton(
                    onClick = { onBoost(0) },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 12.dp)
                ) { Text("Memory") }
                FilledTonalButton(
                    onClick = { onBoost(1) },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 12.dp)
                ) { Text("Cache help") }
                FilledTonalButton(
                    onClick = { onBoost(2) },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 12.dp)
                ) { Text("App tools") }
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Storage scan", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(
                    if (state.scanned) "Review the results before you choose what to clean."
                    else "Find large files and temporary items on your phone.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                    enabled = !state.scanning,
                    onClick = onScan
                ) {
                    Text(
                        when {
                            state.scanning -> "Scanning… ${state.seen} files"
                            !access -> "Set up file access"
                            state.scanned -> "Scan again"
                            else -> "Scan my phone"
                        }
                    )
                }
            }
        }

        if (state.scanning) item {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(
                    "Checking: " + state.path.removePrefix(base).ifEmpty { "phone storage" },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (state.scanned && !state.scanning) {
            val cleanable = state.items.filter { it.cat.defaultOn }.distinctBy { it.file.path }.sumOf { it.size }
            item {
                Text(
                    if (cleanable > 0) "Review ${fmt(ctx, cleanable)} of suggested items"
                    else "No suggested items to clean",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
            items(Cat.values().filter { grouped[it] != null }) { category ->
                val found = grouped[category]!!
                Card(
                    modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenClean),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
                ) {
                    Row(
                        Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(category.label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Text(category.desc, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(fmt(ctx, found.sumOf { it.size }), fontWeight = FontWeight.Bold)
                            Text("${found.size} items", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            item {
                Button(modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), onClick = onOpenClean) {
                    Text("Review scan results")
                }
            }
        }
    }
}
