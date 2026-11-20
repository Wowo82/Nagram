package tw.nekomimi.nekogram.utils

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.FileObserver
import android.os.Process
import android.os.storage.StorageManager
import android.provider.MediaStore
import android.system.Os
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.FileLoader
import org.telegram.messenger.FileLog
import tw.nekomimi.nekogram.NekoConfig
import java.io.File
import java.io.FileInputStream
import java.util.*

object EnvUtil {

    @JvmStatic
    @Suppress("UNCHECKED_CAST")
    val rootDirectories: List<File> by lazy {

        try {
            val mStorageManager = ApplicationLoader.applicationContext.getSystemService(Context.STORAGE_SERVICE) as StorageManager
            (mStorageManager.javaClass.getMethod("getVolumePaths").invoke(mStorageManager) as Array<String>).map { File(it) }
        } catch (_:  Throwable) {
            AndroidUtilities.getRootDirs()
        }

    }

    @JvmStatic
    val availableDirectories
        get() = LinkedList<File>().apply {

            add(File(ApplicationLoader.getDataDirFixed(), "files/media"))
            add(File(ApplicationLoader.getDataDirFixed(), "cache/media"))

            rootDirectories.forEach {

                add(File(it, "Android/data/" + ApplicationLoader.applicationContext.packageName + "/files"))
                add(File(it, "Android/data/" + ApplicationLoader.applicationContext.packageName + "/cache"))

            }

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                add(Environment.getExternalStoragePublicDirectory("Nagram"))
            }

        }.map { it.path }.toTypedArray()

    /**
     * 公共存储里不允许作为缓存根目录的名字。
     *
     * 历史版本/迁移数据可能把 cachePath 设成 Documents、Download 之类的公共目录，
     * 结果缩略图、m3u8 播放列表、视频分片会散落在用户的文件管理器里。
     * 这里做一次兜底：命中即强制回到应用专属的外部目录。
     */
    private val blockedCacheRoots = arrayOf(
        "Documents", "Download", "Downloads", "Pictures", "DCIM", "Music", "Movies", "Telegram", "Nagram"
    )

    private fun isPublicCacheRoot(dir: File): Boolean {
        val path = dir.absolutePath.trimEnd('/')
        if (path.isEmpty()) return false
        // 已经是应用专属目录，放行
        if (path.contains("/Android/data/") || path.contains("/Android/media/")) return false
        for (root in rootDirectories) {
            val rootPath = root.absolutePath.trimEnd('/')
            if (path == rootPath) return true
            for (name in blockedCacheRoots) {
                if (path == "$rootPath/$name" || path.startsWith("$rootPath/$name/")) {
                    return true
                }
            }
        }
        return false
    }

    /** Telegram 缓存文件名：dcId_documentId[.ext] */
    private val legacyCacheName = Regex("^\\d+_\\d+(\\.[A-Za-z0-9]+)?$")

    /**
     * 清理旧版本落在公共目录里的缓存文件。
     *
     * 只删除严格符合 Telegram 缓存命名（dcId_documentId）以及 m3u8 / ts / part 后缀的文件，
     * 不会碰用户自己的文档。
     */
    private fun cleanupLegacyCache(dir: File) {
        try {
            val files = dir.listFiles() ?: return
            for (file in files) {
                if (file == null || file.isDirectory) continue
                val name = file.name
                if (legacyCacheName.matches(name) || name.endsWith(".m3u8")
                    || name.endsWith(".ts") || name.endsWith(".part")
                ) {
                    try {
                        file.delete()
                    } catch (_: Throwable) {
                    }
                }
            }
        } catch (_: Throwable) {
        }
    }

    @Volatile
    private var publicLeftoversCleaned = false

    private const val PROBE_MAX_ENTRIES = 300

    @Volatile
    private var documentsWatcher: FileObserver? = null

    /**
     * 在 Documents 上挂 inotify 观察者，文件一落地就把现场写进日志。
     *
     * 只记录，不删文件、不改任何路径。每条事件带 st_uid 和反查出来的包名，
     * 这是唯一能回答"这些 dcId_docId 到底是谁写的"的证据：
     * 包名是本应用说明本进程（或同 uid 的旧进程）写的，是别家包名就直接定案。
     */
    private fun startDocumentsWatcher(docs: File) {
        if (documentsWatcher != null) return
        if (!docs.isDirectory) {
            FileLog.d("DocumentsWatch: skip, ${docs.absolutePath} is not a directory")
            return
        }
        try {
            val mask = FileObserver.CREATE or FileObserver.CLOSE_WRITE or FileObserver.MOVED_TO or
                    FileObserver.MOVED_FROM or FileObserver.DELETE or FileObserver.ATTRIB
            val watcher = object : FileObserver(docs.absolutePath, mask) {
                override fun onEvent(event: Int, path: String?) {
                    recordDocumentsEvent(docs, event, path ?: return)
                }
            }
            watcher.startWatching()
            documentsWatcher = watcher
            FileLog.d(
                "DocumentsWatch: start dir=${docs.absolutePath} myUid=${Process.myUid()} myPid=${Process.myPid()}" +
                        " pkg=${ApplicationLoader.applicationContext.packageName}" +
                        " cachePath=${NekoConfig.cachePath.String()}" +
                        " cacheDir=${AndroidUtilities.getCacheDir()}" +
                        " mediaCache=${FileLoader.getDirectory(FileLoader.MEDIA_DIR_CACHE)}"
            )
        } catch (t: Throwable) {
            FileLog.d("DocumentsWatch: start failed $t")
        }
    }

    /** 把一次 inotify 事件展开成可判断的字段。 */
    private fun recordDocumentsEvent(docs: File, event: Int, path: String) {
        try {
            val isDir = (event and FileObserver.ISDIR) != 0
            val sb = StringBuilder("DocumentsWatch: ")
                .append(fileObserverEventName(event))
                .append(" path=").append(path)
                .append(" isDir=").append(isDir)
            val file = File(docs, path)
            try {
                val st = Os.stat(file.absolutePath)
                val uid = st.st_uid
                val pkg = try {
                    ApplicationLoader.applicationContext.packageManager
                        .getPackagesForUid(uid)?.joinToString(",") ?: "unknown"
                } catch (_: Throwable) {
                    "unknown"
                }
                sb.append(" size=").append(st.st_size)
                    .append(" mtime=").append(st.st_mtime)
                    .append(" uid=").append(uid)
                    .append(" pkg=").append(pkg)
                    .append(" mode=").append(Integer.toOctalString(st.st_mode))
                if (!isDir && st.st_size in 1L..4096L) {
                    sb.append(" hls=").append(sniffHls(file))
                }
            } catch (t: Throwable) {
                sb.append(" stat=").append(t.javaClass.simpleName)
            }
            sb.append(" byPid=").append(Process.myPid())
            FileLog.d(sb.toString())
        } catch (_: Throwable) {
        }
    }

    private fun fileObserverEventName(event: Int): String = when (event and FileObserver.ALL_EVENTS) {
        FileObserver.CREATE -> "CREATE"
        FileObserver.CLOSE_WRITE -> "CLOSE_WRITE"
        FileObserver.MOVED_TO -> "MOVED_TO"
        FileObserver.MOVED_FROM -> "MOVED_FROM"
        FileObserver.DELETE -> "DELETE"
        FileObserver.ATTRIB -> "ATTRIB"
        FileObserver.MODIFY -> "MODIFY"
        FileObserver.OPEN -> "OPEN"
        else -> "EVENT_" + (event and FileObserver.ALL_EVENTS)
    }

    /**
     * HLS manifest 首行固定是 #EXTM3U，正文里的 mtproto:NNN 指向真正的视频文档。
     * 返回 yes:NNN 就能和文件名里的 docId 对上，确认是哪个视频的播放列表。
     */
    private fun sniffHls(file: File): String {
        return try {
            val bytes = ByteArray(1024)
            val read = FileInputStream(file).use { it.read(bytes) }
            if (read <= 0) {
                "empty"
            } else {
                val head = String(bytes, 0, read)
                if (!head.startsWith("#EXTM3U")) {
                    "no"
                } else {
                    val idx = head.indexOf("mtproto:")
                    if (idx < 0) {
                        "yes"
                    } else {
                        var end = idx + 8
                        while (end < head.length && head[end].isDigit()) end++
                        "yes:" + head.substring(idx + 8, end)
                    }
                }
            }
        } catch (_: Throwable) {
            "readfail"
        }
    }

    /**
     * 把公共 Documents 目录里的内容记进应用日志。
     *
     * Telegram 自身的 Java 代码里没有任何 DIRECTORY_DOCUMENTS 写入点，
     * 但用户设备上仍会出现聊天缓存，所以这里把现场记录下来，
     * 下次导出日志就能看到文件名、大小与修改时间，直接定位来源。
     */
    private fun probePublicDocuments(docs: File) {
        try {
            FileLog.d("DocumentsProbe: root=${docs.absolutePath} exists=${docs.exists()} canRead=${docs.canRead()}")
            val files = docs.listFiles() ?: return
            FileLog.d("DocumentsProbe: entries=${files.size}")
            var budget = PROBE_MAX_ENTRIES
            for (file in files) {
                if (file.isDirectory) {
                    val children = file.listFiles()
                    FileLog.d("DocumentsProbe: dir ${file.name} children=${children?.size ?: -1}")
                    budget = probeDirectory(file, 1, budget)
                } else {
                    FileLog.d("DocumentsProbe: file ${file.name} size=${file.length()} mtime=${file.lastModified()}")
                    budget--
                }
                if (budget <= 0) {
                    FileLog.d("DocumentsProbe: truncated, more than $PROBE_MAX_ENTRIES entries")
                    break
                }
            }
        } catch (t: Throwable) {
            FileLog.e(t)
        }
    }

    /** 递归列出 Documents 子目录内容，最多 depth 层，打印完整路径便于判断归属。 */
    private fun probeDirectory(dir: File, depth: Int, budget: Int): Int {
        if (depth > 2 || budget <= 0) return budget
        var left = budget
        val files = dir.listFiles() ?: return left
        for (file in files) {
            if (left <= 0) break
            if (file.isDirectory) {
                FileLog.d("DocumentsProbe: dir2 ${file.absolutePath} children=${file.listFiles()?.size ?: -1}")
                left = probeDirectory(file, depth + 1, left - 1)
            } else {
                FileLog.d("DocumentsProbe: file2 ${file.absolutePath} size=${file.length()} mtime=${file.lastModified()}")
                left--
            }
        }
        return left
    }

    /**
     * 问系统：Documents 下这些文件到底是哪个包创建的。
     *
     * MediaStore 的 OWNER_PACKAGE_NAME 是唯一能一锤定音的证据：
     * 如果 owner 是 xyz.nextalone.nagram，说明本应用写过；否则就是别的应用或用户自己放进去的。
     * 需要 READ_EXTERNAL_STORAGE，权限还没给时会抛 SecurityException，直接忽略。
     */
    private fun probeDocumentsViaMediaStore() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            FileLog.d("DocumentsProbe: media skipped, sdk=${Build.VERSION.SDK_INT}")
            return
        }
        try {
            val resolver = ApplicationLoader.applicationContext.contentResolver
            val projection = arrayOf(
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.RELATIVE_PATH,
                MediaStore.MediaColumns.OWNER_PACKAGE_NAME,
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.DATE_ADDED
            )
            val owners = HashMap<String, Int>()
            var rows = 0
            resolver.query(
                MediaStore.Files.getContentUri("external"),
                projection,
                "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
                arrayOf("Documents%"),
                null
            )?.use { cursor ->
                while (cursor.moveToNext() && rows < PROBE_MAX_ENTRIES) {
                    val name = cursor.getString(0)
                    val rel = cursor.getString(1)
                    val owner = cursor.getString(2) ?: "null"
                    val size = cursor.getLong(3)
                    val added = cursor.getLong(4)
                    owners[owner] = (owners[owner] ?: 0) + 1
                    FileLog.d("DocumentsProbe: media name=$name rel=$rel owner=$owner size=$size added=$added")
                    rows++
                }
            }
            FileLog.d("DocumentsProbe: media rows=$rows owners=$owners own=${ApplicationLoader.applicationContext.packageName}")
        } catch (t: Throwable) {
            FileLog.e("DocumentsProbe: media query failed", t)
        }
    }

    /** 后台清理公共 Documents 里旧版本留下的缓存碎片。 */
    private fun cleanupPublicLeftoversAsync() {
        if (publicLeftoversCleaned) return
        publicLeftoversCleaned = true
        Thread {
            try {
                val docs = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
                probePublicDocuments(docs)
                startDocumentsWatcher(docs)
                cleanupLegacyCache(docs)
                for (name in arrayOf("Nagram", "Telegram")) {
                    val dir = File(docs, name)
                    if (dir.isDirectory) {
                        cleanupLegacyCache(dir)
                    }
                }
            } catch (_: Throwable) {
            }
            // 等权限弹窗处理完、MediaProvider 就绪之后再问一次归属
            try {
                AndroidUtilities.runOnUIThread({ probeDocumentsViaMediaStore() }, 12_000)
            } catch (_: Throwable) {
            }
        }.start()
    }

    // This is the only media path of NekoX, don't use other!
    @JvmStatic
    fun getTelegramPath(): File {

        cleanupPublicLeftoversAsync()

        if (NekoConfig.cachePath.String() == "") {
            // https://github.com/NekoX-Dev/NekoX/issues/284
            NekoConfig.cachePath.setConfigString(availableDirectories[2])
        } else if (isPublicCacheRoot(File(NekoConfig.cachePath.String()))) {
            // 缓存不能落到公共目录，清掉遗留缓存并切回应用专属目录
            cleanupLegacyCache(File(NekoConfig.cachePath.String()))
            NekoConfig.cachePath.setConfigString(availableDirectories[2])
        }
        var telegramPath = File(NekoConfig.cachePath.String())
        if (telegramPath.isDirectory || telegramPath.mkdirs()) {
            return telegramPath
        } else {
            NekoConfig.cachePath.setConfigString(availableDirectories[2])
        }

        // fallback

        telegramPath = ApplicationLoader.applicationContext.getExternalFilesDir(null) ?: File(availableDirectories[1])

        if (telegramPath.isDirectory || telegramPath.mkdirs()) {

            return telegramPath

        }

        telegramPath = File(availableDirectories[1])

        if (!telegramPath.isDirectory) telegramPath.mkdirs()

        return telegramPath

    }

    @JvmStatic
    fun getShareCachePath(): File {
        // fix SDK < A11
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return getTelegramPath()
        }
        return File(availableDirectories[3])
    }
}
