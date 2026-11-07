package com.example.procleaner

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
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
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

object Prefs {
    private fun sp(ctx: Context) = ctx.getSharedPreferences("procleaner", Context.MODE_PRIVATE)
    fun get(ctx: Context, key: String): Set<String> = (sp(ctx).getStringSet(key, emptySet()) ?: emptySet()).toSet()
    fun put(ctx: Context, key: String, v: Set<String>) { sp(ctx).edit().putStringSet(key, v).apply() }
}

fun toggle(s: Set<String>, k: String): Set<String> = if (k in s) s - k else s + k

fun stopApp(ctx: Context, pkg: String) {
    try { ctx.getSystemService(ActivityManager::class.java).killBackgroundProcesses(pkg) } catch (e: Exception) { }
}

fun clearDirContents(dir: File?): Long {
    var n = 0L
    if (dir == null || !dir.exists()) return 0
    dir.walkBottomUp().filter { it != dir }.forEach { f -> val s = f.length(); if (f.delete()) n += s }
    return n
}

fun clearExternalCaches(ctx: Context): Long {
    var n = clearDirContents(ctx.cacheDir) + clearDirContents(ctx.externalCacheDir)
    File(Environment.getExternalStorageDirectory(), "Android/data").listFiles()?.forEach { app ->
        n += clearDirContents(File(app, "cache"))
    }
    return n
}

fun openAppSettings(ctx: Context, pkg: String) {
    ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")))
}

fun openHibernate(ctx: Context, pkg: String) {
    val u = Uri.parse("package:$pkg")
    try {
        if (Build.VERSION.SDK_INT >= 30 && !Build.MANUFACTURER.equals("samsung", true)) ctx.startActivity(Intent("android.intent.action.AUTO_REVOKE_PERMISSIONS", u))
        else ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, u))
    } catch (e: Exception) {
        try { ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, u)) } catch (e2: Exception) { }
    }
}

// type: 0 = Sleeping apps, 1 = Deep sleeping apps, 2 = Never sleeping apps
fun openSamsungSleep(ctx: Context, type: Int): Boolean {
    val tries = listOf(
        Intent("com.samsung.android.sm.ACTION_OPEN_CHECKABLE_LISTACTIVITY").setPackage("com.samsung.android.lool").putExtra("activity_type", type),
        Intent().setComponent(ComponentName("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity")),
        Intent().setComponent(ComponentName("com.samsung.android.sm", "com.samsung.android.sm.ui.battery.BatteryActivity")),
        ctx.packageManager.getLaunchIntentForPackage("com.samsung.android.lool") ?: Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)
    )
    for (i in tries) {
        try { ctx.startActivity(i); return true } catch (e: Exception) { }
    }
    return false
}

@Composable
fun AppIcon(pkg: String) {
    val ctx = LocalContext.current
    val icon by produceState<ImageBitmap?>(null, pkg) {
        value = withContext(Dispatchers.IO) {
            try { ctx.packageManager.getApplicationIcon(pkg).toBitmap(96, 96).asImageBitmap() } catch (e: Exception) { null }
        }
    }
    Box(Modifier.size(40.dp)) {
        val i = icon
        if (i != null) Image(i, null, Modifier.fillMaxSize())
    }
}

@Composable
fun NoteCard(text: String) {
    Card(Modifier.fillMaxWidth()) { Text(text, Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall) }
}

@Composable
fun BoostScreen(sub: Int, onSub: (Int) -> Unit, resumes: Int) {
    val ctx = LocalContext.current
    var apps by remember { mutableStateOf(listOf<AppRow>()) }
    LaunchedEffect(resumes) { apps = withContext(Dispatchers.IO) { loadApps(ctx) } }
    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = sub) {
            listOf("RAM", "Cache", "Sleep").forEachIndexed { i, t ->
                Tab(selected = sub == i, onClick = { onSub(i) }, text = { Text(t) })
            }
        }
        when (sub) {
            0 -> RamTab(apps)
            1 -> CacheTab(apps, resumes)
            else -> SleepTab(apps)
        }
    }
}

@Composable
fun RamTab(apps: List<AppRow>) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val am = remember { ctx.getSystemService(ActivityManager::class.java) }
    var tick by remember { mutableIntStateOf(0) }
    var keep by remember { mutableStateOf(Prefs.get(ctx, "keep")) }
    var msg by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val mi = remember(tick) { ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) } }
    val used = mi.totalMem - mi.availMem
    val targets = apps.filter { it.pkg !in keep && it.pkg != ctx.packageName }

    LazyColumn(
        Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.CenterHorizontally
    ) {
        item { Ring(used.toFloat() / mi.totalMem, "${used * 100 / mi.totalMem}%", "RAM used · ${fmt(ctx, mi.availMem)} free") }
        item {
            NoteCard("Boost stops the background processes of the apps ticked below. Android may restart some of them, so the gain can be temporary. The app you're using now is never affected.")
        }
        item {
            Button(modifier = Modifier.fillMaxWidth().height(52.dp), enabled = !busy && targets.isNotEmpty(), onClick = {
                scope.launch {
                    busy = true
                    val b = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.availMem
                    targets.forEach { stopApp(ctx, it.pkg) }
                    delay(2000)
                    val a = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.availMem
                    msg = if (a > b) "Freed ${fmt(ctx, a - b)}. Free RAM is now ${fmt(ctx, a)}."
                    else "Stopped ${targets.size} apps. Free RAM is ${fmt(ctx, a)}; few apps were running in the background."
                    tick++; busy = false
                }
            }) { Text(if (busy) "Boosting…" else "Boost RAM now (${targets.size} apps)") }
        }
        if (msg.isNotEmpty()) item { Text(msg, color = MaterialTheme.colorScheme.primary) }
        item { Text("Apps to stop (untick to leave running)", style = MaterialTheme.typography.titleSmall, modifier = Modifier.fillMaxWidth()) }
        items(apps, key = { it.pkg }) { a ->
            Row(
                Modifier.fillMaxWidth().clickable { keep = toggle(keep, a.pkg); Prefs.put(ctx, "keep", keep) },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(checked = a.pkg !in keep, onCheckedChange = { keep = toggle(keep, a.pkg); Prefs.put(ctx, "keep", keep) })
                AppIcon(a.pkg)
                Spacer(Modifier.width(10.dp))
                Text(a.label, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
fun CacheTab(apps: List<AppRow>, resumes: Int) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val usage = remember(resumes) { hasUsage(ctx) }
    var msg by remember { mutableStateOf("") }
    val withCache = apps.filter { it.cacheB > 0 }.sortedByDescending { it.cacheB }
    val total = withCache.sumOf { it.cacheB }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            NoteCard("Android doesn't let one app delete another app's cache. 'Clean what I can now' clears what is allowed on your phone. For the rest, tap Clear beside an app, then choose Storage and Clear cache.")
        }
        if (!usage) item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Turn on usage access so Pro Cleaner can show each app's cache size.")
                    Button(onClick = { ctx.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }) { Text("Open usage access settings") }
                }
            }
        }
        item { Text("Total app cache: ${fmt(ctx, total)}", style = MaterialTheme.typography.titleLarge) }
        item {
            Button(modifier = Modifier.fillMaxWidth(), onClick = {
                scope.launch {
                    val n = withContext(Dispatchers.IO) { clearExternalCaches(ctx) }
                    msg = if (n > 0) "Cleared ${fmt(ctx, n)}."
                    else "Nothing could be cleared directly. On Android 11 and newer, use the Clear buttons below."
                }
            }) { Text("Clean what I can now") }
        }
        item {
            OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = {
                ctx.startActivity(Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS))
            }) { Text("Open system storage screen") }
        }
        if (msg.isNotEmpty()) item { Text(msg, color = MaterialTheme.colorScheme.primary) }
        items(withCache, key = { it.pkg }) { a ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                AppIcon(a.pkg)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(a.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("Cache ${fmt(ctx, a.cacheB)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
                TextButton(onClick = { openAppSettings(ctx, a.pkg) }) { Text("Clear") }
            }
        }
    }
}

@Composable
fun SleepTab(apps: List<AppRow>) {
    val ctx = LocalContext.current
    val samsung = remember { Build.MANUFACTURER.equals("samsung", true) }
    var sleep by remember { mutableStateOf(Prefs.get(ctx, "sleep")) }
    var msg by remember { mutableStateOf("") }
    val picked = apps.filter { it.pkg in sleep }

    fun openList(t: Int, name: String) {
        if (!openSamsungSleep(ctx, t)) msg = "Couldn't open $name. Go to Settings, Battery, Background usage limits."
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            NoteCard(
                if (samsung) "Samsung keeps apps asleep with its own lists, and Android doesn't let other apps add to them. Tick apps below to make a shortlist, open the list you want, tap + and add them. 'Stop' ends an app's background activity right now."
                else "Only Android itself can keep an app in deep sleep. 'Stop' ends an app's background activity right now, then opens its settings so you can set Battery to Restricted."
            )
        }
        if (samsung) item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Samsung sleep lists", style = MaterialTheme.typography.titleMedium)
                    Button(modifier = Modifier.fillMaxWidth(), onClick = { openList(0, "Sleeping apps") }) { Text("Sleeping apps") }
                    Button(modifier = Modifier.fillMaxWidth(), onClick = { openList(1, "Deep sleeping apps") }) { Text("Deep sleeping apps") }
                    OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = { openList(2, "Never sleeping apps") }) { Text("Never sleeping apps") }
                    if (picked.isNotEmpty()) Text(
                        "Add these inside the list: " + picked.joinToString(", ") { it.label },
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
        item {
            Button(modifier = Modifier.fillMaxWidth(), enabled = sleep.isNotEmpty(), onClick = {
                sleep.forEach { stopApp(ctx, it) }
                msg = "Stopped ${sleep.size} apps in your list."
            }) { Text("Stop my list now (${sleep.size})") }
        }
        item {
            OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = {
                ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }) { Text("Open battery optimisation list") }
        }
        if (msg.isNotEmpty()) item { Text(msg, color = MaterialTheme.colorScheme.primary) }
        items(apps, key = { it.pkg }) { a ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = a.pkg in sleep, onCheckedChange = { sleep = toggle(sleep, a.pkg); Prefs.put(ctx, "sleep", sleep) })
                AppIcon(a.pkg)
                Spacer(Modifier.width(10.dp))
                Text(a.label, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                TextButton(onClick = {
                    stopApp(ctx, a.pkg)
                    openHibernate(ctx, a.pkg)
                }) { Text("Stop") }
            }
        }
    }
}
