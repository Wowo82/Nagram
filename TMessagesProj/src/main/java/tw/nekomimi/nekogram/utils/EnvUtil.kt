package tw.nekomimi.nekogram.utils

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.FileLog
import tw.nekomimi.nekogram.NekoConfig
import java.io.File
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
            for (file in files) {
                if (file.isDirectory) {
                    val children = file.listFiles()
                    FileLog.d("DocumentsProbe: dir ${file.name} children=${children?.size ?: -1}")
                } else {
                    FileLog.d("DocumentsProbe: file ${file.name} size=${file.length()} mtime=${file.lastModified()}")
                }
            }
        } catch (t: Throwable) {
            FileLog.e(t)
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
                cleanupLegacyCache(docs)
                for (name in arrayOf("Nagram", "Telegram")) {
                    val dir = File(docs, name)
                    if (dir.isDirectory) {
                        cleanupLegacyCache(dir)
                    }
                }
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
