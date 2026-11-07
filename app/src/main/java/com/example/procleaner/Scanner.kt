package com.example.procleaner

import android.os.Environment
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

enum class Cat(val label: String, val desc: String, val defaultOn: Boolean) {
    TEMP("Temp, log & backup files", "Files ending in .tmp .log .bak .old .chk .dmp", true),
    THUMBS("Thumbnail caches", "Hidden .thumbnails folders that Android rebuilds", true),
    HIDDEN("Hidden cache folders", "Files inside hidden folders named .cache .tmp .temp .thumbs that apps rebuild", true),
    APK("Leftover APK installers", "Installer files you no longer need after installing apps", true),
    EMPTY("Empty folders", "Folders with nothing inside", true),
    DUP("Duplicate files", "Identical copies (same content). One copy of each is kept", true),
    TRASH("Hidden trash / recycle bins", "Deleted items still stored in .Trash folders (e.g. Samsung Gallery). Gone forever once cleaned", false),
    STATUS("WhatsApp viewed statuses", "Copies of statuses you have watched. Unticked so you can save any first", false),
    WA_SENT("WhatsApp sent-media copies", "Copies of media you sent, kept in WhatsApp Sent folders", false),
    OLD_DL("Old downloads", "Files in Download untouched for over 90 days", false),
    LARGE("Large files", "Files over 100 MB. Check these yourself, may be videos you want", false)
}

enum class FType(val label: String) {
    IMAGES("Images"), VIDEOS("Videos"), AUDIO("Audio"), DOCS("Documents"),
    ARCHIVES("Archives"), APPS("APK files"), OTHER("Other")
}

val imageExt = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif")

fun typeOf(e: String): FType = when (e) {
    in imageExt -> FType.IMAGES
    "mp4", "mkv", "avi", "mov", "3gp", "webm", "flv", "wmv" -> FType.VIDEOS
    "mp3", "m4a", "aac", "wav", "flac", "ogg", "opus", "amr" -> FType.AUDIO
    "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "csv", "epub" -> FType.DOCS
    "zip", "rar", "7z", "tar", "gz" -> FType.ARCHIVES
    "apk", "xapk" -> FType.APPS
    else -> FType.OTHER
}

class Item(val file: File, val size: Long, val cat: Cat, val note: String = "") {
    var selected by mutableStateOf(cat.defaultOn)
}

class ScanResult(val items: List<Item>, val types: Map<FType, Pair<Int, Long>>)

class CleanState {
    var items by mutableStateOf(listOf<Item>())
    var types by mutableStateOf(mapOf<FType, Pair<Int, Long>>())
    var scanning by mutableStateOf(false)
    var scanned by mutableStateOf(false)
    var seen by mutableIntStateOf(0)
    var path by mutableStateOf("")
    var message by mutableStateOf("")

    suspend fun scan() {
        scanning = true; message = ""; seen = 0
        try {
            val r = Scanner.scan(Environment.getExternalStorageDirectory()) { n, p -> seen = n; path = p }
            items = r.items; types = r.types; scanned = true
            if (items.isEmpty()) message = "Nothing to clean. Your phone is tidy!"
        } finally {
            scanning = false
        }
    }
}

object Scanner {
    private val junkExt = setOf("tmp", "temp", "log", "bak", "old", "chk", "dmp")
    private val cacheNames = setOf(".cache", ".tmp", ".temp", ".thumbs", ".thumbnail", ".dthumb")
    private val trashNames = setOf(".trash", ".trashbin")
    private const val LARGE = 100L * 1024 * 1024

    private fun hiddenDir(f: File, root: File): String? {
        var p = f.parentFile
        while (p != null && p != root) {
            val n = p.name.lowercase()
            if (n == ".thumbnails" || n in cacheNames || n in trashNames) return p.name
            p = p.parentFile
        }
        return null
    }
    private const val DUP_MIN = 50L * 1024

    suspend fun scan(root: File, onProgress: (Int, String) -> Unit): ScanResult = withContext(Dispatchers.IO) {
        val out = mutableListOf<Item>()
        val bySize = HashMap<Long, MutableList<File>>()
        val cnt = IntArray(FType.values().size)
        val sz = LongArray(FType.values().size)
        val oldCut = System.currentTimeMillis() - 90L * 24 * 3600 * 1000
        var n = 0
        root.walkTopDown()
            .onEnter { d -> !(d.parentFile?.name == "Android" && d.parentFile?.parentFile == root && (d.name == "data" || d.name == "obb")) }
            .forEach { f ->
                ensureActive()
                if (++n % 200 == 0) onProgress(n, f.parent ?: "")
                if (f.isDirectory) {
                    if (f != root && f.list()?.isEmpty() == true) out += Item(f, 0, Cat.EMPTY, "Empty folder")
                    return@forEach
                }
                val len = f.length()
                val ext = f.extension.lowercase()
                val t = typeOf(ext)
                cnt[t.ordinal]++; sz[t.ordinal] += len
                val hid = hiddenDir(f, root)
                when {
                    f.parentFile?.name.equals(".Statuses", true) -> out += Item(f, len, Cat.STATUS)
                    hid != null -> {
                        val k = hid.lowercase()
                        val c = when {
                            k == ".thumbnails" -> Cat.THUMBS
                            k in trashNames -> Cat.TRASH
                            else -> Cat.HIDDEN
                        }
                        out += Item(f, len, c, "In hidden folder $hid")
                    }
                    ext in junkExt -> out += Item(f, len, Cat.TEMP)
                    ext == "apk" -> out += Item(f, len, Cat.APK)
                    else -> {
                        if (f.path.contains("/WhatsApp", true) && f.parentFile?.name == "Sent") out += Item(f, len, Cat.WA_SENT)
                        if (len >= LARGE) out += Item(f, len, Cat.LARGE)
                        if (f.path.contains("/Download/") && f.lastModified() < oldCut) out += Item(f, len, Cat.OLD_DL)
                        if (len >= DUP_MIN) bySize.getOrPut(len) { mutableListOf() }.add(f)
                    }
                }
            }
        for ((_, files) in bySize) {
            if (files.size < 2) continue
            val firstByHash = HashMap<String, File>()
            for (f in files) {
                ensureActive()
                val h = hash(f) ?: continue
                val keep = firstByHash[h]
                if (keep == null) firstByHash[h] = f
                else out += Item(f, f.length(), Cat.DUP, "Same as ${keep.name}")
            }
        }
        onProgress(n, "")
        val types = FType.values().filter { cnt[it.ordinal] > 0 }.associateWith { cnt[it.ordinal] to sz[it.ordinal] }
        ScanResult(out, types)
    }

    private fun hash(f: File): String? = try {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { s ->
            val buf = ByteArray(64 * 1024)
            while (true) { val r = s.read(buf); if (r < 0) break; md.update(buf, 0, r) }
        }
        md.digest().joinToString("") { "%02x".format(it) }
    } catch (e: Exception) { null }
}
