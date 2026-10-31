package app.bilidownload.next.download
import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
class VideoDownloadManager(private val context:Context) {
    fun enqueue(url:String,title:String):Long {
        val safe=title.replace(Regex("[\\\\/:*?\"<>|]"),"_").take(80).ifBlank{"video"}
        val req=DownloadManager.Request(Uri.parse(url)).setTitle(safe).setDescription("BiliDownload Next").setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED).setAllowedOverMetered(true).setAllowedOverRoaming(false).setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS,"BiliDownload/$safe.mp4")
        return (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
    }
}