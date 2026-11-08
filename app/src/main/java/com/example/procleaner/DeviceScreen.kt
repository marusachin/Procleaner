package com.example.procleaner

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Alignment
import androidx.core.content.ContextCompat
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@Composable
fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
fun DeviceScreen(resumes: Int) {
    val ctx = LocalContext.current
    var msg by remember { mutableStateOf("") }
    var autoClean by remember { mutableStateOf(Prefs.getBool(ctx, "auto_clean")) }
    var lowAlert by remember { mutableStateOf(Prefs.getBool(ctx, "low_alert")) }
    var runMsg by remember { mutableStateOf("") }
    val lastAuto = remember(resumes, runMsg) { Prefs.getString(ctx, "last_auto") }
    val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    fun askNotif() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    val bi = remember(resumes) { ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) }
    val level = bi?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
    val scale = bi?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
    val temp = (bi?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f
    val status = bi?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
    val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
    val st = StatFs(Environment.getDataDirectory().path)
    val ext = StatFs(Environment.getExternalStorageDirectory().path)

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Device", style = MaterialTheme.typography.headlineMedium) }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Automatic care", style = MaterialTheme.typography.titleMedium)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Weekly auto-clean")
                            Text("Cleans temp files, hidden cache, thumbnails and empty folders, clears caches and stops background apps. Never touches photos, videos or duplicates.",
                                style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(checked = autoClean, onCheckedChange = { v ->
                            autoClean = v
                            Prefs.putBool(ctx, "auto_clean", v)
                            if (v) { askNotif(); if (!hasAccess(ctx)) openAccessSettings(ctx) }
                            Scheduler.refresh(ctx)
                        })
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Low storage alert")
                            Text("Sends a notification when free space drops below 15%.", style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(checked = lowAlert, onCheckedChange = { v ->
                            lowAlert = v
                            Prefs.putBool(ctx, "low_alert", v)
                            if (v) askNotif()
                            Scheduler.refresh(ctx)
                        })
                    }
                    if (lastAuto.isNotEmpty()) Text("Last auto-clean: $lastAuto", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary)
                    OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = {
                        askNotif()
                        WorkManager.getInstance(ctx).enqueue(
                            OneTimeWorkRequestBuilder<AutoCleanWorker>().setInputData(workDataOf("force" to true)).build()
                        )
                        runMsg = "Running in the background. You'll get a notification when it's done."
                    }) { Text("Run auto-clean now") }
                    if (runMsg.isNotEmpty()) Text(runMsg, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text("Phone", style = MaterialTheme.typography.titleMedium)
                    InfoRow("Model", "${Build.MANUFACTURER} ${Build.MODEL}")
                    InfoRow("Android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                    InfoRow("CPU", Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown")
                    InfoRow("Cores", "${Runtime.getRuntime().availableProcessors()}")
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text("Battery", style = MaterialTheme.typography.titleMedium)
                    InfoRow("Level", if (level >= 0) "${level * 100 / scale}%" else "unknown")
                    InfoRow("Status", if (charging) "Charging" else "Not charging")
                    InfoRow("Temperature", "%.1f °C".format(temp))
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text("Storage", style = MaterialTheme.typography.titleMedium)
                    InfoRow("Internal total", fmt(ctx, st.totalBytes))
                    InfoRow("Internal free", fmt(ctx, st.availableBytes))
                    InfoRow("Shared storage free", fmt(ctx, ext.availableBytes))
                }
            }
        }
        item {
            Button(modifier = Modifier.fillMaxWidth(), onClick = {
                var n = 0L
                listOfNotNull(ctx.cacheDir, ctx.externalCacheDir).forEach { d ->
                    d.walkBottomUp().filter { it != d }.forEach { f -> val s = f.length(); if (f.delete()) n += s }
                }
                msg = "Cleared ${fmt(ctx, n)} of this app's cache"
            }) { Text("Clear Pro Cleaner cache") }
        }
        item {
            OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = {
                ctx.startActivity(Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS))
            }) { Text("Open system storage / cache cleaner") }
        }
        if (msg.isNotEmpty()) item { Text(msg, color = MaterialTheme.colorScheme.primary) }
        item {
            Text(
                "Android doesn't let apps clear other apps' caches or stop background apps. Use the system storage screen for that.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
