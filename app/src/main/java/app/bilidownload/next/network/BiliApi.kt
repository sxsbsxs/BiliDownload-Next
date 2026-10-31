package app.bilidownload.next.network

import app.bilidownload.next.data.VideoItem
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class BiliApi {
    private val client = OkHttpClient.Builder().connectTimeout(15,TimeUnit.SECONDS).readTimeout(30,TimeUnit.SECONDS).build()
    private fun get(url:String):String {
        val req=Request.Builder().url(url).header("User-Agent","Mozilla/5.0 BiliDownloadNext/0.1").header("Referer","https://www.bilibili.com/").build()
        client.newCall(req).execute().use { r -> if(!r.isSuccessful) error("网络请求失败：HTTP ${r.code}"); return r.body?.string() ?: error("服务器返回空内容") }
    }
    fun search(keyword:String):List<VideoItem> {
        val url="https://api.bilibili.com/x/web-interface/search/type".toHttpUrl().newBuilder().addQueryParameter("search_type","video").addQueryParameter("keyword",keyword).addQueryParameter("page","1").build().toString()
        val root=JSONObject(get(url)); if(root.optInt("code")!=0) error(root.optString("message","搜索失败"))
        val arr=root.optJSONObject("data")?.optJSONObject("result")?.optJSONArray("video") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { o ->
            VideoItem(o.optString("bvid",o.optString("aid")),o.optString("bvid"),o.optLong("aid"),title=o.optString("title").replace(Regex("<[^>]*>"),""),owner=o.optString("author"),duration=o.optString("duration"),description=o.optString("description"))
        } }
    }
    fun videoInfo(id:String):VideoItem {
        val b="https://api.bilibili.com/x/web-interface/view".toHttpUrl().newBuilder()
        if(id.startsWith("BV",true)) b.addQueryParameter("bvid",id) else b.addQueryParameter("aid",id.removePrefix("av").removePrefix("AV"))
        val root=JSONObject(get(b.build().toString())); if(root.optInt("code")!=0) error(root.optString("message","视频解析失败"))
        val d=root.getJSONObject("data"); val pages=d.optJSONArray("pages"); val p=pages?.optJSONObject(0)
        val sec=d.optInt("duration")
        return VideoItem(d.optString("bvid",d.optString("aid")),d.optString("bvid"),d.optLong("aid"),p?.optLong("cid") ?: d.optLong("cid"),d.optString("title"),d.optJSONObject("owner")?.optString("name") ?: "未知UP主","${sec/60}:${(sec%60).toString().padStart(2,'0')}",d.optString("desc"))
    }
    fun downloadUrl(item:VideoItem):String {
        val v=if(item.cid==0L) videoInfo(item.bvid.ifBlank{item.aid.toString()}) else item
        val url="https://api.bilibili.com/x/player/playurl".toHttpUrl().newBuilder().addQueryParameter("avid",v.aid.toString()).addQueryParameter("cid",v.cid.toString()).addQueryParameter("qn","80").addQueryParameter("fnval","0").addQueryParameter("fourk","1").build().toString()
        val root=JSONObject(get(url)); if(root.optInt("code")!=0) error(root.optString("message","视频流获取失败"))
        return root.optJSONObject("data")?.optJSONArray("durl")?.optJSONObject(0)?.optString("url")?.takeIf{it.startsWith("http")} ?: error("当前视频未返回可下载的直链")
    }
}