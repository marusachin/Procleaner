package com.example.procleaner

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

enum class Cat(val label: String, val defaultOn: Boolean) {
    TEMP("Temp, log & backup files", true),
    THUMBS("Thumbnail caches", true),
    APK("Leftover APK installers", true),
    EMPTY("Empty folders", true),
    DUP("Duplicate files (keeps 1 copy)", true),
    LARGE("Large files over 100 MB", false)
}

data class Item(val file: File, val size: Long, val cat: Cat)

object Scanner {
    private val junkExt = setOf("tmp", "temp", "log", "bak", "old", "chk", "dmp")
    private const val LARGE = 100L * 1024 * 1024
    private const val DUP_MIN = 50L * 1024

    suspend fun scan(root: File, onProgress: (Int) -> Unit): List<Item> = withContext(Dispatchers.IO) {
        val out = mutableListOf<Item>()
        val bySize = HashMap<Long, MutableList<File>>()
        var n = 0
        root.walkTopDown()
            .onEnter { d -> !(d.name == "Android" && d.parentFile == root) } // protected app dirs
            .forEach { f ->
                ensureActive()
                if (++n % 300 == 0) onProgress(n)
                if (f.isDirectory) {
                    if (f != root && f.list()?.isEmpty() == true) out += Item(f, 0, Cat.EMPTY)
                    return@forEach
                }
                val len = f.length()
                val ext = f.extension.lowercase()
                when {
                    f.parentFile?.name == ".thumbnails" -> out += Item(f, len, Cat.THUMBS)
                    ext in junkExt -> out += Item(f, len, Cat.TEMP)
                    ext == "apk" -> out += Item(f, len, Cat.APK)
                    else -> {
                        if (len >= LARGE) out += Item(f, len, Cat.LARGE)
                        if (len >= DUP_MIN) bySize.getOrPut(len) { mutableListOf() }.add(f)
                    }
                }
            }
        for ((_, files) in bySize) {
            if (files.size < 2) continue
            val seen = HashSet<String>()
            for (f in files) {
                ensureActive()
                val h = hash(f) ?: continue
                if (!seen.add(h)) out += Item(f, f.length(), Cat.DUP)
            }
        }
        onProgress(n)
        out
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
