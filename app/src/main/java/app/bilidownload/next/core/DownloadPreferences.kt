package app.bilidownload.next.core

import android.content.Context

enum class BatchQualityPolicy { BEST_COMMON, KEEP_REQUESTED, SKIP_UNAVAILABLE }
enum class DownloadLocation { PUBLIC_DOWNLOADS, APP_PRIVATE }

class DownloadPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("download_preferences", Context.MODE_PRIVATE)
    var qualityPolicy: BatchQualityPolicy
        get() = runCatching { BatchQualityPolicy.valueOf(prefs.getString("quality_policy", null) ?: "") }.getOrDefault(BatchQualityPolicy.BEST_COMMON)
        set(value) { prefs.edit().putString("quality_policy", value.name).apply() }
    var location: DownloadLocation
        get() = runCatching { DownloadLocation.valueOf(prefs.getString("location", null) ?: "") }.getOrDefault(DownloadLocation.PUBLIC_DOWNLOADS)
        set(value) { prefs.edit().putString("location", value.name).apply() }
    var fileNameTemplate: String
        get() = prefs.getString("file_name_template", "{title}") ?: "{title}"
        set(value) { prefs.edit().putString("file_name_template", value.take(120)).apply() }
    var deleteSourceAfterMerge: Boolean
        get() = prefs.getBoolean("delete_source_after_merge", false)
        set(value) { prefs.edit().putBoolean("delete_source_after_merge", value).apply() }
}
