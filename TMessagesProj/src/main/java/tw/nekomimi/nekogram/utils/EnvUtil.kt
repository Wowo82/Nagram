package tw.nekomimi.nekogram.utils

import android.os.Build
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ApplicationLoader
import java.io.File

object EnvUtil {

    /**
     * 媒体根目录，固定为 <外部私有目录>/files/Telegram。
     *
     * 与官方一致：cache 只放可再生缓存（缩略图、视频分片、m3u8 清单），
     * 图片/视频/文档/语音等按分类放在 files/Telegram 下。
     * 不再提供“存储位置”切换，避免把分类目录写进 cache 或 /data/data。
     */
    @JvmStatic
    fun getTelegramPath(): File {
        var base = ApplicationLoader.applicationContext.getExternalFilesDir(null)
        if (base == null) {
            base = ApplicationLoader.getFilesDirFixed()
        }
        var telegramPath = File(base, "Telegram")
        if (!telegramPath.isDirectory && !telegramPath.mkdirs()) {
            base = ApplicationLoader.getFilesDirFixed()
            telegramPath = File(base, "Telegram")
            if (!telegramPath.isDirectory) {
                telegramPath.mkdirs()
            }
        }
        return telegramPath
    }

    @JvmStatic
    fun getShareCachePath(): File {
        // fix SDK < A11
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return getTelegramPath()
        }
        return AndroidUtilities.getCacheDir()
    }
}
