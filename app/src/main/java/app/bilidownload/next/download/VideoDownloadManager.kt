package app.bilidownload.next.download

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment

class VideoDownloadManager(private val context: Context) {
    fun enqueue(url: String, title: String, extension: String = "mp4"): Long {
        val safe = title.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(80).ifBlank { "video" }
        val ext = extension.lowercase().filter { it.isLetterOrDigit() }.take(8).ifBlank { "bin" }
        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle(safe)
            .setDescription("BiliDownload Next")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(false)
            .addRequestHeader("Referer", "https://www.bilibili.com/")
            .addRequestHeader("User-Agent", "Mozilla/5.0")
            .setDestinationInExternalPublicDir(
                Environment.DIRECTORY_DOWNLOADS,
                "BiliDownload/$safe.$ext"
            )
        return (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
    }
}
