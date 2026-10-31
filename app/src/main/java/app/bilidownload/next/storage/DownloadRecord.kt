package app.bilidownload.next.storage

data class DownloadRecord(val id:Long,val title:String,val resourceUrl:String,val status:DownloadStatus,val progress:Int=0)
enum class DownloadStatus { QUEUED, DOWNLOADING, COMPLETED, FAILED, PAUSED }