package com.example.procleaner

import android.os.Environment
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
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
fun Thumb(file: File, size: Dp = 48.dp, onClick: (() -> Unit)? = null) {
    val ext = file.extension.lowercase()
    val kind = remember(ext) { if (ext in imageExt) 1 else if (typeOf(ext) == FType.VIDEOS) 2 else 0 }
    val bmp by produceState<ImageBitmap?>(null, file) {
        value = when (kind) {
            1 -> withContext(Dispatchers.IO) { thumb(file)?.asImageBitmap() }
            2 -> withContext(Dispatchers.IO) { videoThumb(file)?.asImageBitmap() }
            else -> null
        }
    }
    val base = Modifier.size(size).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
    Box(if (onClick != null) base.clickable { onClick() } else base, contentAlignment = Alignment.Center) {
        val b = bmp
        if (b != null) Image(b, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Text(if (file.isDirectory) "DIR" else ext.uppercase().take(4).ifEmpty { "FILE" },
            style = MaterialTheme.typography.labelSmall)
        if (kind == 2) Text("▶", color = Color.White, style = MaterialTheme.typography.titleMedium)
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
fun TypeBreakdown(types: Map<FType, Pair<Int, Long>>, onOpen: (FType) -> Unit) {
    val ctx = LocalContext.current
    val total = types.values.sumOf { it.second }.coerceAtLeast(1L)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("What's using your storage", style = MaterialTheme.typography.titleMedium)
            Text("Tap a type to see its files with pictures", style = MaterialTheme.typography.bodySmall)
            types.entries.sortedByDescending { it.value.second }.forEach { (t, v) ->
                Column(Modifier.fillMaxWidth().clickable { onOpen(t) }.padding(vertical = 4.dp)) {
                    Row(Modifier.fillMaxWidth()) {
                        Text("${t.label} · ${v.first} files  ›", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        Text(fmt(ctx, v.second), style = MaterialTheme.typography.labelLarge)
                    }
                    LinearProgressIndicator(progress = { v.second.toFloat() / total }, modifier = Modifier.fillMaxWidth().height(6.dp))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TypeBrowser(state: CleanState, type: FType) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val base = remember { Environment.getExternalStorageDirectory().path }
    val all = state.byType[type] ?: emptyList()
    var sort by remember { mutableIntStateOf(0) }
    var ext by remember { mutableStateOf<String?>(null) }
    val selected = remember { mutableStateMapOf<String, Boolean>() }
    var confirm by remember { mutableStateOf(false) }
    val extStats = remember(all) {
        all.groupBy { it.file.extension.lowercase() }
            .map { (k, v) -> Triple(k, v.size, v.sumOf { e -> e.size }) }
            .sortedByDescending { it.third }.take(10)
    }
    val shown = remember(all, sort, ext) {
        val f = if (ext == null) all else all.filter { it.file.extension.lowercase() == ext }
        when (sort) {
            0 -> f
            1 -> f.sortedByDescending { it.file.lastModified() }
            2 -> f.sortedBy { it.file.lastModified() }
            else -> f.sortedBy { it.file.name.lowercase() }
        }
    }
    val picked = if (selected.isEmpty()) emptyList() else all.filter { selected[it.file.path] == true }
    val pickedSize = picked.sumOf { it.size }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { state.browse = null }) { Text("← Back") }
                Text("${type.label} · ${all.size} files · ${fmt(ctx, all.sumOf { it.size })}", style = MaterialTheme.typography.titleMedium)
            }
            Text("Tap a file to select it. Tap its picture to open it.", style = MaterialTheme.typography.bodySmall)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { FilterChip(selected = ext == null, onClick = { ext = null }, label = { Text("All types") }) }
                items(extStats) { (k, n, s) ->
                    FilterChip(selected = ext == k, onClick = { ext = if (ext == k) null else k },
                        label = { Text("${if (k.isEmpty()) "no ext" else ".$k"} · $n · ${fmt(ctx, s)}") })
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Largest", "Newest", "Oldest", "Name").forEachIndexed { i, t ->
                    FilterChip(selected = sort == i, onClick = { sort = i }, label = { Text(t) })
                }
            }
        }
        LazyColumn(Modifier.weight(1f)) {
            items(shown, key = { it.file.path }) { e ->
                val path = e.file.path
                Row(
                    Modifier.fillMaxWidth()
                        .clickable { if (selected[path] == true) selected.remove(path) else selected[path] = true }
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(checked = selected[path] == true,
                        onCheckedChange = { v -> if (v) selected[path] = true else selected.remove(path) })
                    Thumb(e.file, 64.dp) { openFile(ctx, e.file) }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(e.file.name, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                        Text((e.file.parent ?: "").removePrefix(base).ifEmpty { "/" }, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(dateOf(e.file.lastModified()), style = MaterialTheme.typography.labelSmall)
                    }
                    Spacer(Modifier.width(6.dp))
                    Text(fmt(ctx, e.size), style = MaterialTheme.typography.labelLarge)
                }
            }
        }
        if (picked.isNotEmpty()) Surface(tonalElevation = 3.dp) {
            Button(modifier = Modifier.fillMaxWidth().padding(12.dp), onClick = { confirm = true }) {
                Text("Delete ${picked.size} files · ${fmt(ctx, pickedSize)}")
            }
        }
    }

    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("Delete ${picked.size} files?") },
        text = { Text("${fmt(ctx, pickedSize)} will be permanently deleted. This cannot be undone.") },
        confirmButton = {
            TextButton(onClick = {
                confirm = false
                scope.launch {
                    val (freed, gone) = withContext(Dispatchers.IO) {
                        var f = 0L
                        val g = HashSet<String>()
                        picked.forEach { if (it.file.delete()) { f += it.size; g.add(it.file.path) } }
                        f to g
                    }
                    state.removePaths(gone)
                    selected.clear()
                    Toast.makeText(ctx, "Freed ${fmt(ctx, freed)}", Toast.LENGTH_SHORT).show()
                }
            }) { Text("Delete") }
        },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } }
    )
}

@Composable
fun CleanScreen(state: CleanState, onScan: () -> Unit) {
    val br = state.browse
    if (br != null && state.byType.containsKey(br)) {
        BackHandler { state.browse = null }
        TypeBrowser(state, br)
    } else CleanMain(state, onScan)
}

@Composable
fun CleanMain(state: CleanState, onScan: () -> Unit) {
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
            if (state.types.isNotEmpty()) item { TypeBreakdown(state.types) { state.browse = it } }
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
