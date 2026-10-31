package app.bilidownload.next.media

data class MediaStream(val url:String,val quality:Int,val mimeType:String,val codecs:String)
enum class ExportFormat { MP4, FLV, MKV, WMV }
data class SubtitleTrack(val language:String,val url:String)
data class DanmakuItem(val progressMs:Long,val mode:Int,val fontSize:Int,val color:Int,val text:String)