package com.example.procleaner

import android.os.Environment
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun Thumb(file: File) {
    val ext = file.extension.lowercase()
    val bmp by produceState<ImageBitmap?>(null, file) {
        value = if (ext in imageExt) withContext(Dispatchers.IO) { thumb(file)?.asImageBitmap() } else null
    }
    Box(
        Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        val b = bmp
        if (b != null) Image(b, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Text(if (file.isDirectory) "DIR" else ext.uppercase().take(4).ifEmpty { "FILE" },
            style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
fun ItemRow(item: Item, base: String) {
    val ctx = LocalContext.current
    Row(
        Modifier.fillMaxWidth().clickable { item.selected = !item.selected }.padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = item.selected, onCheckedChange = { item.selected = it })
        Thumb(item.file)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(item.file.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            Text((item.file.parent ?: "").removePrefix(base).ifEmpty { "/" }, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(listOfNotNull(dateOf(item.file.lastModified()), item.note.ifEmpty { null }).joinToString(" · "),
                maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall)
        }
        Spacer(Modifier.width(6.dp))
        Text(fmt(ctx, item.size), style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun CategoryCard(cat: Cat, list: List<Item>, base: String) {
    val ctx = LocalContext.current
    var open by remember { mutableStateOf(false) }
    val sorted = remember(list) { list.sortedByDescending { it.size } }
    val sel = list.count { it.selected }
    val selSize = list.filter { it.selected }.sumOf { it.size }
    Card(Modifier.fillMaxWidth()) {
        Column {
            Row(Modifier.fillMaxWidth().clickable { open = !open }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = sel == list.size, onCheckedChange = { v -> list.forEach { it.selected = v } })
                Column(Modifier.weight(1f)) {
                    Text(cat.label, style = MaterialTheme.typography.titleMedium)
                    Text(cat.desc, style = MaterialTheme.typography.bodySmall)
                    Text("${list.size} files · ${fmt(ctx, list.sumOf { it.size })} total · $sel selected (${fmt(ctx, selSize)})",
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
                Text(if (open) "Hide" else "Details", style = MaterialTheme.typography.labelLarge)
            }
            if (open) {
                HorizontalDivider()
                sorted.take(60).forEach { ItemRow(it, base) }
                if (list.size > 60) Text(
                    "Showing the 60 largest of ${list.size}. The checkbox at the top applies to all ${list.size}.",
                    Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
fun TypeBreakdown(types: Map<FType, Pair<Int, Long>>) {
    val ctx = LocalContext.current
    val total = types.values.sumOf { it.second }.coerceAtLeast(1L)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("What's using your storage", style = MaterialTheme.typography.titleMedium)
            types.entries.sortedByDescending { it.value.second }.forEach { (t, v) ->
                Column {
                    Row(Modifier.fillMaxWidth()) {
                        Text("${t.label} · ${v.first} files", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        Text(fmt(ctx, v.second), style = MaterialTheme.typography.labelLarge)
                    }
                    LinearProgressIndicator(progress = { v.second.toFloat() / total }, modifier = Modifier.fillMaxWidth().height(6.dp))
                }
            }
        }
    }
}

@Composable
fun CleanScreen(state: CleanState, onScan: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var confirm by remember { mutableStateOf(false) }
    val base = remember { Environment.getExternalStorageDirectory().path }
    val grouped = state.items.groupBy { it.cat }
    val sel = state.items.filter { it.selected }.distinctBy { it.file.path }
    val total = sel.sumOf { it.size }

    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.weight(1f).padding(horizontal = 16.dp),
            contentPadding = PaddingValues(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item { Text("Clean", style = MaterialTheme.typography.headlineMedium) }
            if (!state.scanned) item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("No scan yet. Scan to see every file that can be cleaned, with names, folders, dates and sizes.")
                        Button(onClick = onScan, enabled = !state.scanning) {
                            Text(if (state.scanning) "Scanning… ${state.seen}" else "Scan phone")
                        }
                    }
                }
            }
            if (state.message.isNotEmpty()) item { Text(state.message, color = MaterialTheme.colorScheme.primary) }
            if (state.types.isNotEmpty()) item { TypeBreakdown(state.types) }
            items(Cat.values().filter { grouped[it] != null }) { c -> CategoryCard(c, grouped[c]!!, base) }
        }
        if (state.items.isNotEmpty()) Surface(tonalElevation = 3.dp) {
            Button(modifier = Modifier.fillMaxWidth().padding(12.dp), enabled = sel.isNotEmpty(), onClick = { confirm = true }) {
                Text("Clean ${sel.size} files · ${fmt(ctx, total)}")
            }
        }
    }

    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("Delete selected files?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Cat.values().forEach { c ->
                    val l = state.items.filter { it.cat == c && it.selected }
                    if (l.isNotEmpty()) Text("• ${c.label}: ${l.size} (${fmt(ctx, l.sumOf { it.size })})")
                }
                Text("Total ${fmt(ctx, total)}. This is permanent and cannot be undone.")
            }
        },
        confirmButton = {
            TextButton(onClick = {
                confirm = false
                scope.launch {
                    val (freed, left) = withContext(Dispatchers.IO) {
                        var f = 0L
                        state.items.filter { it.selected }.forEach { if (it.file.exists() && it.file.delete()) f += it.size }
                        f to state.items.filter { it.file.exists() }
                    }
                    state.items = left
                    state.message = "Freed ${fmt(ctx, freed)}"
                }
            }) { Text("Delete") }
        },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } }
    )
}
