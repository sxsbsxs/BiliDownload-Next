package app.bilidownload.next.media

sealed interface DownloadSource {
    data class Direct(val url: String) : DownloadSource
    data class Dash(
        val videoUrls: List<String>,
        val audioUrls: List<String>,
        val quality: Int,
        val videoCodec: String,
        val audioCodec: String
    ) : DownloadSource
}
