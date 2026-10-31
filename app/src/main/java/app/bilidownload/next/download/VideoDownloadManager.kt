package app.bilidownload.next.download

import android.app.DownloadManager
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Environment

data class DownloadTask(
    val id: Long,
    val title: String,
    val status: Int,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val reason: Int
) {
    val progress: Int
        get() = if (totalBytes > 0L) ((downloadedBytes * 100L) / totalBytes).toInt().coerceIn(0, 100) else 0
}

class VideoDownloadManager(private val context: Context, private val cookieProvider: () -> String? = { null }) {
    private val manager get() = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

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
            .apply { cookieProvider()?.takeIf { it.isNotBlank() }?.let { addRequestHeader("Cookie", it) } }
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "BiliDownload/$safe.$ext")
        return manager.enqueue(request)
    }

    fun tasks(): List<DownloadTask> {
        val columns = arrayOf(
            DownloadManager.COLUMN_ID,
            DownloadManager.COLUMN_TITLE,
            DownloadManager.COLUMN_STATUS,
            DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR,
            DownloadManager.COLUMN_TOTAL_SIZE_BYTES,
            DownloadManager.COLUMN_REASON
        )
        val cursor = manager.query(DownloadManager.Query().setFilterByStatus(
            DownloadManager.STATUS_PENDING or DownloadManager.STATUS_RUNNING or
                DownloadManager.STATUS_PAUSED or DownloadManager.STATUS_SUCCESSFUL or
                DownloadManager.STATUS_FAILED
        )) ?: return emptyList()
        return cursor.use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        DownloadTask(
                            id = c.long(columns, DownloadManager.COLUMN_ID),
                            title = c.string(columns, DownloadManager.COLUMN_TITLE),
                            status = c.int(columns, DownloadManager.COLUMN_STATUS),
                            downloadedBytes = c.long(columns, DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR),
                            totalBytes = c.long(columns, DownloadManager.COLUMN_TOTAL_SIZE_BYTES),
                            reason = c.int(columns, DownloadManager.COLUMN_REASON)
                        )
                    )
                }
            }.sortedByDescending { it.id }
        }
    }

    fun cancel(id: Long): Boolean = manager.remove(id) > 0

    private fun Cursor.long(columns: Array<String>, column: String): Long = getLong(getColumnIndexOrThrow(column))
    private fun Cursor.int(columns: Array<String>, column: String): Int = getInt(getColumnIndexOrThrow(column))
    private fun Cursor.string(columns: Array<String>, column: String): String = getString(getColumnIndexOrThrow(column)).orEmpty()
}
