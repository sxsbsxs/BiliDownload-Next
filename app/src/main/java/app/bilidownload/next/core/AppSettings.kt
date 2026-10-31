package app.bilidownload.next.core

data class AppSettings(val preferredQuality:Int=80,val parallelDownloads:Int=2,val wifiOnly:Boolean=false,val saveDirectory:String="Download/BiliDownload")