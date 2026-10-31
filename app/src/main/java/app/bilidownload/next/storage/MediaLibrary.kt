package app.bilidownload.next.storage

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore

data class LocalMedia(
    val id: Long,
    val uri: Uri,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val modifiedSeconds: Long
)

/** Reads only media published in the app's Downloads/BiliDownload collection. */
class MediaLibrary(private val context: Context) {
    fun list(): List<LocalMedia> {
        val collection = if (Build.VERSION.SDK_INT >= 29)
            MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL)
        else MediaStore.Files.getContentUri("external")
        val projection = buildList {
            add(MediaStore.MediaColumns._ID)
            add(MediaStore.MediaColumns.DISPLAY_NAME)
            add(MediaStore.MediaColumns.MIME_TYPE)
            add(MediaStore.MediaColumns.SIZE)
            add(MediaStore.MediaColumns.DATE_MODIFIED)
            if (Build.VERSION.SDK_INT >= 29) add(MediaStore.MediaColumns.RELATIVE_PATH)
        }.toTypedArray()
        val selection = if (Build.VERSION.SDK_INT >= 29) "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?" else null
        val args = if (selection != null) arrayOf("${Environment.DIRECTORY_DOWNLOADS}/BiliDownload/%") else null
        return context.contentResolver.query(collection, projection, selection, args,
            "${MediaStore.MediaColumns.DATE_MODIFIED} DESC")?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
            buildList {
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    add(LocalMedia(id, ContentUris.withAppendedId(collection, id),
                        cursor.getString(nameCol).orEmpty(), cursor.getString(mimeCol).orEmpty(),
                        cursor.getLong(sizeCol), cursor.getLong(dateCol)))
                }
            }
        }.orEmpty()
    }

    fun delete(uri: Uri): Boolean = context.contentResolver.delete(uri, null, null) > 0
}
