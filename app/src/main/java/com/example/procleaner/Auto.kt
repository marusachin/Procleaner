package com.example.procleaner

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

object Notifier {
    fun show(ctx: Context, id: Int, title: String, text: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("care", "Pro Cleaner alerts", NotificationManager.IMPORTANCE_DEFAULT))
        val open = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)
        val pi = open?.let { PendingIntent.getActivity(ctx, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT) }
        val n = NotificationCompat.Builder(ctx, "care")
            .setSmallIcon(android.R.drawable.ic_menu_delete)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        nm.notify(id, n)
    }
}

/** Weekly safe clean: temp files, hidden cache, thumbnails, empty folders, caches, background apps. */
class AutoCleanWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val c = applicationContext
        val force = inputData.getBoolean("force", false)
        if (!force && !Prefs.getBool(c, "auto_clean")) return Result.success()
        var freed = 0L
        withContext(Dispatchers.IO) {
            if (hasAccess(c)) {
                val r = Scanner.scan(Environment.getExternalStorageDirectory(), quick = true) { _, _ -> }
                val safe = setOf(Cat.TEMP, Cat.THUMBS, Cat.HIDDEN, Cat.EMPTY)
                r.items.filter { it.cat in safe }.forEach { if (it.file.exists() && it.file.delete()) freed += it.size }
            }
            freed += clearExternalCaches(c)
            freed += freeSystemCache(c)
            val keep = Prefs.get(c, "keep")
            loadApps(c).filter { it.pkg !in keep && it.pkg != c.packageName }.forEach { stopApp(c, it.pkg) }
        }
        Prefs.putString(c, "last_auto", "Freed ${fmt(c, freed)} on ${dateOf(System.currentTimeMillis())}")
        Notifier.show(c, 1, "Auto-clean finished", "Freed ${fmt(c, freed)} and stopped background apps.")
        return Result.success()
    }
}

/** Notifies when free space drops below 15%. */
class StorageCheckWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val c = applicationContext
        if (!Prefs.getBool(c, "low_alert")) return Result.success()
        val st = StatFs(Environment.getDataDirectory().path)
        val pct = st.availableBytes * 100 / st.totalBytes
        val now = System.currentTimeMillis()
        if (pct < 15 && now - Prefs.getLong(c, "last_alert") > 24L * 3600 * 1000) {
            val top = Prefs.getString(c, "top_types")
            Notifier.show(
                c, 2, "Storage is almost full",
                "Only $pct% free (${fmt(c, st.availableBytes)})." +
                    (if (top.isNotEmpty()) " Biggest: $top." else "") + " Open Pro Cleaner to free space."
            )
            Prefs.putLong(c, "last_alert", now)
        }
        return Result.success()
    }
}

object Scheduler {
    fun refresh(ctx: Context) {
        val wm = WorkManager.getInstance(ctx)
        // Cleaning now always requires an on-screen review and confirmation.
        wm.cancelUniqueWork("auto_clean")
        if (Prefs.getBool(ctx, "low_alert")) {
            wm.enqueueUniquePeriodicWork(
                "low_alert", ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<StorageCheckWorker>(6, TimeUnit.HOURS).build()
            )
        } else wm.cancelUniqueWork("low_alert")
    }
}
