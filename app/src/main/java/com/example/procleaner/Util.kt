package com.example.procleaner

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Process
import android.provider.Settings
import android.text.format.Formatter
import java.io.File
import java.text.DateFormat
import java.util.Date

fun fmt(ctx: Context, b: Long): String = Formatter.formatFileSize(ctx, b)

fun dateOf(ms: Long): String = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(ms))

fun hasAccess(ctx: Context): Boolean =
    if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
    else ctx.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

fun openAccessSettings(ctx: Context) {
    val pkg = Uri.parse("package:${ctx.packageName}")
    try {
        if (Build.VERSION.SDK_INT >= 30) ctx.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, pkg))
        else ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg))
    } catch (e: Exception) {
        ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg))
    }
}

@Suppress("DEPRECATION")
fun hasUsage(ctx: Context): Boolean {
    val ops = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
    val mode = if (Build.VERSION.SDK_INT >= 29)
        ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
    else ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
    return mode == AppOpsManager.MODE_ALLOWED
}

fun thumb(f: File, px: Int = 96): Bitmap? = try {
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(f.path, o)
    var s = 1
    while (o.outWidth / (s * 2) >= px && o.outHeight / (s * 2) >= px) s *= 2
    BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = s })
} catch (e: Exception) { null }

fun videoThumb(f: File): Bitmap? {
    val r = MediaMetadataRetriever()
    return try {
        r.setDataSource(f.path)
        val b = r.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: r.getFrameAtTime(0)
        b?.let { Bitmap.createScaledBitmap(it, 160, (160f * it.height / it.width).toInt().coerceAtLeast(1), true) }
    } catch (e: Exception) { null } finally {
        try { r.release() } catch (e: Exception) { }
    }
}

fun openFile(ctx: Context, f: File) {
    try {
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase()) ?: "*/*"
        ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    } catch (e: Exception) {
        android.widget.Toast.makeText(ctx, "No app can open this file", android.widget.Toast.LENGTH_SHORT).show()
    }
}
