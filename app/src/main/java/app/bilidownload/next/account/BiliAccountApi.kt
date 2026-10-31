package app.bilidownload.next.account

import app.bilidownload.next.data.VideoItem
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class BiliUserProfile(val mid: Long, val name: String, val face: String, val sign: String, val level: Int, val vipLabel: String)
data class BiliFavoriteFolder(val id: Long, val title: String, val mediaCount: Int, val cover: String)
data class BiliSeasonInfo(val seasonId: Long, val title: String, val cover: String, val episodes: List<VideoItem>)

/** Authenticated account endpoints used by the account hub. Cookie values never enter UI models. */
class BiliAccountApi(private val cookieProvider: () -> String?) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()

    private fun get(url: String): JSONObject {
        val request = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 BiliDownloadNext/0.2")
            .header("Referer", "https://www.bilibili.com/")
            .apply { cookieProvider()?.takeIf(String::isNotBlank)?.let { header("Cookie", it) } }
            .build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("网络请求失败：HTTP ${response.code}")
            JSONObject(response.body?.string() ?: error("服务器返回空内容"))
        }
    }

    private fun checked(url: String): JSONObject {
        val root = get(url)
        if (root.optInt("code") != 0) error(root.optString("message).ifBlank { "B站接口请求失败" })
        return root.optJSONObject("data") ?: JSONObject()
    }

    fun profile(): BiliUserProfile {
        val d = checked("https://api.bilibili.com/x/web-interface/nav")
        val vip = d.optJSONObject("vip")
        return BiliUserProfile(
            d.optLong("mid"), d.optString("uname"), d.optString("face"),
            d.optString("sign"), d.optJSONObject("level_info")?.optInt("current_level") ?: 0,
            vip?.optString("label")?.let { JSONObject(it).optString("text") }.orEmpty()
        )
    }

    fun history(page: Int = 1): List<VideoItem> {
        val url = "https://api.bilibili.com/x/web-interface/history/cursor".toHttpUrl().newBuilder()
            .addQueryParameter("ps", "20").addQueryParameter("max", if (page <= 1) "0" else Long.MAX_VALUE.toString())
            .addQueryParameter("view_at", "0").addQueryParameter("business", "").build().toString()
        val data = checked(url)
        val list = data.optJSONArray("list") ?: return emptyList()
        return (0 until list.length()).mapNotNull { i ->
            val item = list.optJSONObject(i) ?: return@mapNotNull null
            val h = item.optJSONObject("history") ?: JSONObject()
            val o = item.optJSONObject("owner") ?: JSONObject()
            val p = item.optJSONObject("page") ?: JSONObject()
            val bvid = h.optString("bvid")
            val aid = h.optLong("oid")
            VideoItem(bvid.ifBlank { aid.toString() }, bvid, aid, h.optLong("cid"),
                item.optString("title"), o.optString("name"), p.optString("duration"), item.optString("view_at"))
        }
    }

    fun watchLater(): List<VideoItem> {
        val data = checked("https://api.bilibili.com/x/v2/history/toview")
        val list = data.optJSONArray("list") ?: return emptyList()
        return (0 until list.length()).mapNotNull { i ->
            val v = list.optJSONObject(i) ?: return@mapNotNull null
            val owner = v.optJSONObject("owner") ?: JSONObject()
            val bvid = v.optString("bvid")
            val aid = v.optLong("aid")
            VideoItem(bvid.ifBlank { aid.toString() }, bvid, aid, v.optLong("cid"),
                v.optString("title"), owner.optString("name"), v.optString("duration"), v.optString("desc"))
        }
    }

    fun favoriteFolders(): List<BiliFavoriteFolder> {
        val mid = profile().mid
        val url = "https://api.bilibili.com/x/v3/fav/folder/created/list-all".toHttpUrl().newBuilder()
            .addQueryParameter("up_mid", mid.toString()).addQueryParameter("type", "2").build().toString()
        val data = checked(url)
        val list = data.optJSONArray("list") ?: return emptyList()
        return (0 until list.length()).mapNotNull { i ->
            list.optJSONObject(i)?.let { BiliFavoriteFolder(it.optLong("id"), it.optString("title"),
                it.optInt("media_count"), it.optString("cover")) }
        }
    }

    fun favoriteVideos(folderId: Long, page: Int = 1): List<VideoItem> {
        val url = "https://api.bilibili.com/x/v3/fav/resource/list".toHttpUrl().newBuilder()
            .addQueryParameter("media_id", folderId.toString()).addQueryParameter("pn", page.toString())
            .addQueryParameter("ps", "20").addQueryParameter("keyword", "").build().toString()
        val data = checked(url)
        val list = data.optJSONArray("medias") ?: return emptyList()
        return (0 until list.length()).mapNotNull { i ->
            val v = list.optJSONObject(i) ?: return@mapNotNull null
            val upper = v.optJSONObject("upper") ?: JSONObject()
            val bvid = v.optString("bvid")
            val aid = v.optLong("id")
            VideoItem(bvid.ifBlank { aid.toString() }, bvid, aid, v.optLong("cid"),
                v.optString("title"), upper.optString("name"), v.optString("duration"), v.optString("intro"))
        }
    }

    fun season(input: String): BiliSeasonInfo {
        val clean = input.trim()
        val url = "https://api.bilibili.com/pgc/view/web/season".toHttpUrl().newBuilder().apply {
            if (clean.startsWith("ep", true)) addQueryParameter("ep_id", clean.drop(2))
            else addQueryParameter("season_id", clean.removePrefix("ss").removePrefix("SS"))
        }.build().toString()
        val data = checked(url)
        val episodes = data.optJSONArray("episodes") ?: data.optJSONObject("season")?.optJSONArray("episodes") ?: org.json.JSONArray()
        val items = (0 until episodes.length()).mapNotNull { i ->
            val ep = episodes.optJSONObject(i) ?: return@mapNotNull null
            val aid = ep.optLong("aid")
            val bvid = ep.optString("bvid")
            VideoItem(bvid.ifBlank { aid.toString() }, bvid, aid, ep.optLong("cid"),
                ep.optString("long_title").ifBlank { ep.optString("title") },
                data.optString("title"), ep.optString("duration"), ep.optString("share_copy"))
        }
        return BiliSeasonInfo(data.optLong("season_id"), data.optString("title"),
            data.optString("cover"), items)
    }
}
