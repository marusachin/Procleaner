package com.example.procleaner

import android.Manifest
import android.app.ActivityManager
import android.app.usage.StorageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Process
import android.os.StatFs
import android.os.storage.StorageManager
import android.provider.Settings
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

fun fmt(ctx: Context, b: Long): String = Formatter.formatFileSize(ctx, b)

fun hasAccess(ctx: Context): Boolean =
    if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
    else ContextCompat.checkSelfPermission(ctx, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App() {
    var tab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Clean" to Icons.Default.Delete, "Apps" to Icons.Default.Menu, "Device" to Icons.Default.Info)
    Scaffold(
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { i, (t, ic) ->
                    NavigationBarItem(selected = tab == i, onClick = { tab = i },
                        icon = { Icon(ic, t) }, label = { Text(t) })
                }
            }
        }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (tab) { 0 -> CleanScreen(); 1 -> AppsScreen(); else -> DeviceScreen() }
        }
    }
}

@Composable
fun CleanScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf(listOf<Item>()) }
    var scanning by remember { mutableStateOf(false) }
    var seen by remember { mutableIntStateOf(0) }
    var picked by remember { mutableStateOf(setOf<Cat>()) }
    var msg by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf(false) }
    val legacy = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val grouped = items.groupBy { it.cat }
    val total = items.filter { it.cat in picked }.sumOf { it.size }

    fun requestAccess() {
        if (Build.VERSION.SDK_INT >= 30)
            ctx.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${ctx.packageName}")))
        else legacy.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Pro Cleaner", style = MaterialTheme.typography.headlineMedium)
        Button(modifier = Modifier.fillMaxWidth(), enabled = !scanning, onClick = {
            if (!hasAccess(ctx)) { requestAccess(); return@Button }
            scope.launch {
                scanning = true; msg = ""; seen = 0
                items = Scanner.scan(Environment.getExternalStorageDirectory()) { seen = it }
                picked = Cat.values().filter { it.defaultOn }.toSet()
                scanning = false
                if (items.isEmpty()) msg = "Nothing to clean. Your phone is tidy!"
            }
        }) { Text(if (scanning) "Scanning… $seen items" else if (hasAccess(ctx)) "Scan phone" else "Grant file access") }
        if (scanning) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (msg.isNotEmpty()) Text(msg, color = MaterialTheme.colorScheme.primary)

        LazyColumn(Modifier.weight(1f)) {
            items(Cat.values().filter { grouped[it] != null }) { c ->
                val list = grouped[c]!!
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = c in picked, onCheckedChange = { picked = if (it) picked + c else picked - c })
                    Column {
                        Text(c.label, style = MaterialTheme.typography.titleMedium)
                        Text("${list.size} items · ${fmt(ctx, list.sumOf { it.size })}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        Button(modifier = Modifier.fillMaxWidth(), enabled = picked.isNotEmpty() && items.isNotEmpty() && !scanning,
            onClick = { confirm = true }) { Text("Clean ${fmt(ctx, total)}") }
    }

    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("Delete selected files?") },
        text = { Text("${items.count { it.cat in picked }} items (${fmt(ctx, total)}) will be permanently deleted. This cannot be undone.") },
        confirmButton = {
            TextButton(onClick = {
                confirm = false
                scope.launch {
                    val (freed, left) = withContext(Dispatchers.IO) {
                        var f = 0L
                        items.filter { it.cat in picked }.forEach { if (it.file.delete()) f += it.size }
                        f to items.filter { it.file.exists() }
                    }
                    items = left; msg = "Freed ${fmt(ctx, freed)}"
                }
            }) { Text("Delete") }
        },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } }
    )
}

data class AppRow(val label: String, val pkg: String, val size: Long)

fun loadApps(ctx: Context): List<AppRow> {
    val pm = ctx.packageManager
    val ssm = ctx.getSystemService(StorageStatsManager::class.java)
    return pm.getInstalledApplications(0)
        .filter { it.flags and ApplicationInfo.FLAG_SYSTEM == 0 }
        .map { a ->
            val s = try {
                ssm.queryStatsForPackage(StorageManager.UUID_DEFAULT, a.packageName, Process.myUserHandle())
                    .let { it.appBytes + it.dataBytes + it.cacheBytes }
            } catch (e: Exception) { 0L }
            AppRow(a.loadLabel(pm).toString(), a.packageName, s)
        }.sortedByDescending { it.size }
}

@Composable
fun AppsScreen() {
    val ctx = LocalContext.current
    var apps by remember { mutableStateOf(listOf<AppRow>()) }
    var loading by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) { apps = withContext(Dispatchers.IO) { loadApps(ctx) }; loading = false }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("App manager", style = MaterialTheme.typography.headlineMedium)
        OutlinedButton(onClick = { ctx.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }) {
            Text("Enable usage access (shows app sizes)")
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        LazyColumn(Modifier.weight(1f)) {
            items(apps) { a ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(a.label, style = MaterialTheme.typography.titleMedium)
                        Text(if (a.size > 0) fmt(ctx, a.size) else "size unknown", style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = {
                        ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${a.pkg}")))
                    }) { Text("Cache") }
                    TextButton(onClick = {
                        ctx.startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:${a.pkg}")))
                    }) { Text("Remove") }
                }
            }
        }
    }
}

@Composable
fun DeviceScreen() {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    var msg by remember { mutableStateOf("") }
    val st = StatFs(Environment.getDataDirectory().path)
    val total = st.totalBytes; val free = st.availableBytes
    val mi = ActivityManager.MemoryInfo().also { ctx.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }
    key(tick) {}

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Device", style = MaterialTheme.typography.headlineMedium)
        Text("Storage: ${fmt(ctx, total - free)} used of ${fmt(ctx, total)}")
        LinearProgressIndicator(progress = { (total - free).toFloat() / total }, modifier = Modifier.fillMaxWidth())
        Text("RAM: ${fmt(ctx, mi.totalMem - mi.availMem)} used of ${fmt(ctx, mi.totalMem)}")
        LinearProgressIndicator(progress = { (mi.totalMem - mi.availMem).toFloat() / mi.totalMem }, modifier = Modifier.fillMaxWidth())
        Button(modifier = Modifier.fillMaxWidth(), onClick = {
            var n = 0L
            listOfNotNull(ctx.cacheDir, ctx.externalCacheDir).forEach { d ->
                d.walkBottomUp().filter { it != d }.forEach { f -> val s = f.length(); if (f.delete()) n += s }
            }
            msg = "Cleared ${fmt(ctx, n)} of this app's cache"; tick++
        }) { Text("Clear Pro Cleaner cache") }
        Button(modifier = Modifier.fillMaxWidth(), onClick = {
            ctx.startActivity(Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS))
        }) { Text("Open system storage / cache cleaner") }
        if (msg.isNotEmpty()) Text(msg, color = MaterialTheme.colorScheme.primary)
        Text(
            "Android doesn't let apps clear other apps' caches or kill background apps. " +
            "Use the system storage screen for that; this app handles files, duplicates, APKs and app sizes.",
            style = MaterialTheme.typography.bodySmall
        )
    }
}
