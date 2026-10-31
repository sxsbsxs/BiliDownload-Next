package app.bilidownload.next.download

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import app.bilidownload.next.media.DownloadSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit

/** Downloads both DASH tracks and muxes them into one MP4 without silently dropping audio. */
class DashMuxerDownloadManager(
    private val context: Context,
    private val cookieProvider: () -> String? = { null }
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    suspend fun downloadAndMerge(
        source: DownloadSource.Dash,
        title: String
    ): Uri = withContext(Dispatchers.IO) {
        require(source.videoUrls.isNotEmpty()) { "没有可用的视频轨道" }
        require(source.audioUrls.isNotEmpty()) { "没有可用的音频轨道，已阻止生成无声视频" }
        val videoFile = File(context.cacheDir, "dash_video_${System.nanoTime()}.m4s")
        val audioFile = File(context.cacheDir, "dash_audio_${System.nanoTime()}.m4s")
        val outputFile = File(context.cacheDir, "dash_merged_${System.nanoTime()}.mp4")
        try {
            downloadFirstAvailable(source.videoUrls, videoFile)
            downloadFirstAvailable(source.audioUrls, audioFile)
            mux(videoFile, audioFile, outputFile)
            publish(outputFile, title)
        } finally {
            videoFile.delete()
            audioFile.delete()
            outputFile.delete()
        }
    }

    private fun downloadFirstAvailable(urls: List<String>, target: File) {
        var lastError: Throwable? = null
        for (url in urls.distinct()) {
            try {
                val request = Request.Builder()
                    .url(url)
                    .header("Referer", "https://www.bilibili.com/")
                    .header("User-Agent", "Mozilla/5.0")
                    .apply { cookieProvider()?.takeIf { it.isNotBlank() }?.let { header("Cookie", it) } }
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) error("轨道下载失败：HTTP ${response.code}")
                    val body = response.body ?: error("下载响应为空")
                    FileOutputStream(target).use { output -> body.byteStream().use { it.copyTo(output) } }
                }
                if (target.length() > 0L) return
                error("下载内容为空")
            } catch (error: Throwable) {
                lastError = error
                target.delete()
            }
        }
        throw IllegalStateException(lastError?.message ?: "所有备用下载地址均失败", lastError)
    }

    private fun mux(videoFile: File, audioFile: File, outputFile: File) {
        val video = MediaExtractor()
        val audio = MediaExtractor()
        var muxer: MediaMuxer? = null
        try {
            video.setDataSource(videoFile.absolutePath)
            audio.setDataSource(audioFile.absolutePath)
            val videoTrack = findTrack(video, "video/")
            val audioTrack = findTrack(audio, "audio/")
            val videoFormat = video.getTrackFormat(videoTrack)
            val audioFormat = audio.getTrackFormat(audioTrack)
            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val outVideo = muxer.addTrack(videoFormat)
            val outAudio = muxer.addTrack(audioFormat)
            muxer.start()
            copyInterleavedTracks(
                video, videoTrack, videoFormat, outVideo,
                audio, audioTrack, audioFormat, outAudio,
                muxer
            )
            muxer.stop()
        } catch (error: Throwable) {
            runCatching { muxer?.stop() }
            throw IllegalStateException("音视频合并失败：${error.message ?: "当前编码格式可能不受设备支持"}", error)
        } finally {
            runCatching { muxer?.release() }
            video.release()
            audio.release()
        }
        check(outputFile.exists() && outputFile.length() > 0L) { "合并文件为空" }
    }

    private fun findTrack(extractor: MediaExtractor, prefix: String): Int {
        for (index in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME).orEmpty()
            if (mime.startsWith(prefix)) return index
        }
        error("下载文件中未找到 ${prefix}轨道")
    }

    private fun copyInterleavedTracks(
        video: MediaExtractor,
        videoTrack: Int,
        videoFormat: MediaFormat,
        outVideo: Int,
        audio: MediaExtractor,
        audioTrack: Int,
        audioFormat: MediaFormat,
        outAudio: Int,
        muxer: MediaMuxer
    ) {
        video.selectTrack(videoTrack)
        audio.selectTrack(audioTrack)
        fun bufferSize(format: MediaFormat): Int =
            runCatching { format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) }
                .getOrDefault(1024 * 1024).coerceIn(64 * 1024, 8 * 1024 * 1024)
        val videoBuffer = ByteBuffer.allocateDirect(bufferSize(videoFormat))
        val audioBuffer = ByteBuffer.allocateDirect(bufferSize(audioFormat))
        val info = MediaCodec.BufferInfo()
        while (video.sampleTime >= 0L || audio.sampleTime >= 0L) {
            val takeVideo = audio.sampleTime < 0L ||
                (video.sampleTime >= 0L && video.sampleTime <= audio.sampleTime)
            val extractor = if (takeVideo) video else audio
            val buffer = if (takeVideo) videoBuffer else audioBuffer
            val outputTrack = if (takeVideo) outVideo else outAudio
            buffer.clear()
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) {
                extractor.advance()
                continue
            }
            info.set(0, size, extractor.sampleTime, extractor.sampleFlags)
            muxer.writeSampleData(outputTrack, buffer, info)
            extractor.advance()
        }
    }

    private fun publish(source: File, title: String): Uri {
        val safe = title.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(80).ifBlank { "video" }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "$safe.mp4")
                put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/BiliDownload")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("无法创建下载文件")
            try {
                resolver.openOutputStream(uri)?.use { output -> source.inputStream().use { it.copyTo(output) } }
                    ?: error("无法写入下载文件")
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                return uri
            } catch (error: Throwable) {
                resolver.delete(uri, null, null)
                throw error
            }
        } else {
            val directory = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "BiliDownload")
            if (!directory.exists() && !directory.mkdirs()) error("无法创建下载目录")
            val target = File(directory, "$safe.mp4")
            source.copyTo(target, overwrite = true)
            return Uri.fromFile(target)
        }
    }
}
