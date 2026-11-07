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
    Box(Modifier.size(190.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize().padding(12.dp)) {
            drawArc(track, 135f, 270f, false, style = Stroke(width = 28f, cap = StrokeCap.Round))
            drawArc(arc, 135f, 270f * frac.coerceIn(0f, 1f), false, style = Stroke(width = 28f, cap = StrokeCap.Round))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            Text(sub, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun Stat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun HomeScreen(state: CleanState, resumes: Int, onScan: () -> Unit, onOpenClean: () -> Unit, onBoost: (Int) -> Unit) {
    val ctx = LocalContext.current
    val access = remember(resumes) { hasAccess(ctx) }
    val base = remember { Environment.getExternalStorageDirectory().path }
    val st = StatFs(Environment.getDataDirectory().path)
    val total = st.totalBytes
    val free = st.availableBytes
    val used = total - free
    val mi = ActivityManager.MemoryInfo().also { ctx.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }
    val grouped = state.items.groupBy { it.cat }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        item { Text("Pro Cleaner", style = MaterialTheme.typography.headlineMedium) }
        item { Ring(used.toFloat() / total, "${used * 100 / total}%", "of ${fmt(ctx, total)} used") }
        item {
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceEvenly) {
                Stat("Used", fmt(ctx, used))
                Stat("Free", fmt(ctx, free))
                Stat("RAM used", "${fmt(ctx, mi.totalMem - mi.availMem)} / ${fmt(ctx, mi.totalMem)}")
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { onBoost(0) }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 4.dp)) { Text("Boost RAM") }
                FilledTonalButton(onClick = { onBoost(1) }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 4.dp)) { Text("Clean cache") }
                FilledTonalButton(onClick = { onBoost(2) }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 4.dp)) { Text("Sleep apps") }
            }
        }
        item {
            Button(modifier = Modifier.fillMaxWidth().height(52.dp), enabled = !state.scanning, onClick = onScan) {
                Text(
                    if (state.scanning) "Scanning… ${state.seen} files"
                    else if (!access) "Grant file access"
                    else if (state.scanned) "Scan again" else "Scan phone"
                )
            }
        }
        if (state.scanning) item {
            Column(Modifier.fillMaxWidth()) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(state.path.removePrefix(base).ifEmpty { "/" }, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall)
            }
        }
        if (state.scanned && !state.scanning) {
            item {
                val cleanable = state.items.filter { it.cat.defaultOn }.distinctBy { it.file.path }.sumOf { it.size }
                Text("Found ${fmt(ctx, cleanable)} you can safely clean", style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.fillMaxWidth())
            }
            items(Cat.values().filter { grouped[it] != null }) { c ->
                val list = grouped[c]!!
                Card(Modifier.fillMaxWidth().clickable { onOpenClean() }) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(c.label, style = MaterialTheme.typography.titleSmall)
                            Text(c.desc, style = MaterialTheme.typography.bodySmall)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(fmt(ctx, list.sumOf { it.size }), fontWeight = FontWeight.Bold)
                            Text("${list.size} items", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            item { Button(modifier = Modifier.fillMaxWidth(), onClick = onOpenClean) { Text("Review files and clean") } }
        }
    }
}
