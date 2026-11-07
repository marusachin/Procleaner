package com.example.procleaner

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.app.usage.StorageStatsManager
import android.net.Uri
import android.os.Process
import android.os.storage.StorageManager
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Image
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class AppRow(
    val label: String, val pkg: String, val version: String, val installed: Long,
    val appB: Long, val dataB: Long, val cacheB: Long, val hasStats: Boolean
) { val total get() = appB + dataB + cacheB }

fun loadApps(ctx: Context): List<AppRow> {
    val pm = ctx.packageManager
    val ssm = ctx.getSystemService(Context.STORAGE_STATS_SERVICE) as StorageStatsManager
    return pm.getInstalledApplications(0)
        .filter { it.flags and ApplicationInfo.FLAG_SYSTEM == 0 }
        .map { a ->
            var ver = ""; var inst = 0L
            try { val pi = pm.getPackageInfo(a.packageName, 0); ver = pi.versionName ?: ""; inst = pi.firstInstallTime } catch (e: Exception) {}
            try {
                val s = ssm.queryStatsForPackage(StorageManager.UUID_DEFAULT, a.packageName, Process.myUserHandle())
                AppRow(a.loadLabel(pm).toString(), a.packageName, ver, inst, s.appBytes, s.dataBytes, s.cacheBytes, true)
            } catch (e: Exception) {
                AppRow(a.loadLabel(pm).toString(), a.packageName, ver, inst, 0, 0, 0, false)
            }
        }
}

@Composable
fun AppItem(a: AppRow) {
    val ctx = LocalContext.current
    val icon by produceState<ImageBitmap?>(null, a.pkg) {
        value = withContext(Dispatchers.IO) {
            try { ctx.packageManager.getApplicationIcon(a.pkg).toBitmap(96, 96).asImageBitmap() } catch (e: Exception) { null }
        }
    }
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                val i = icon
                if (i != null) Image(i, null, Modifier.fillMaxSize())
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(a.label, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${a.pkg}", style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("v${a.version} · installed ${if (a.installed > 0) dateOf(a.installed) else "unknown"}",
                    style = MaterialTheme.typography.bodySmall)
                if (a.hasStats) Text(
                    "Total ${fmt(ctx, a.total)} · App ${fmt(ctx, a.appB)} · Data ${fmt(ctx, a.dataB)} · Cache ${fmt(ctx, a.cacheB)}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                else Text("Size hidden (enable usage access)", style = MaterialTheme.typography.bodySmall)
            }
            Column {
                TextButton(onClick = {
                    ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${a.pkg}")))
                }) { Text("Info") }
                TextButton(onClick = {
                    ctx.startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:${a.pkg}")))
                }) { Text("Remove") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppsScreen(resumes: Int) {
    val ctx = LocalContext.current
    var apps by remember { mutableStateOf(listOf<AppRow>()) }
    var loading by remember { mutableStateOf(true) }
    var q by remember { mutableStateOf("") }
    var sort by remember { mutableIntStateOf(0) }
    val usage = remember(resumes) { hasUsage(ctx) }
    LaunchedEffect(resumes) {
        loading = true
        apps = withContext(Dispatchers.IO) { loadApps(ctx) }
        loading = false
    }
    val shown = apps
        .filter { it.label.contains(q, true) || it.pkg.contains(q, true) }
        .let { list ->
            when (sort) {
                0 -> list.sortedByDescending { a -> a.total }
                1 -> list.sortedBy { a -> a.label.lowercase() }
                else -> list.sortedByDescending { a -> a.installed }
            }
        }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Apps (${shown.size})", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 16.dp))
        if (!usage) Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("To see how much space each app uses, turn on usage access for Pro Cleaner.")
                Button(onClick = { ctx.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }) { Text("Open usage access settings") }
            }
        }
        OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth(), label = { Text("Search apps") }, singleLine = true)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Largest", "Name", "Newest").forEachIndexed { i, t ->
                FilterChip(selected = sort == i, onClick = { sort = i }, label = { Text(t) })
            }
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        LazyColumn(Modifier.weight(1f)) { items(shown, key = { it.pkg }) { AppItem(it) } }
    }
}
