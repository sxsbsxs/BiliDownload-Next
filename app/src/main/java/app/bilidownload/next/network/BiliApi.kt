package app.bilidownload.next.network

import app.bilidownload.next.data.VideoItem
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class BiliApi {
    private val client=OkHttpClient.Builder().connectTimeout(15,TimeUnit.SECONDS).readTimeout(30,TimeUnit.SECONDS).build()
    private fun get(url:String):String {
        val req=Request.Builder().url(url).header("User-Agent","Mozilla/5.0 BiliDownloadNext/0.1").header("Referer","https://www.bilibili.com/").build()
        client.newCall(req).execute().use{r->if(!r.isSuccessful)error("HTTP ${r.code}");return r.body?.string()?:error("空响应")}
    }
    private fun json(url:String)=JSONObject(get(url))
    fun search(keyword:String):List<VideoItem>{
        val url="https://api.bilibili.com/x/web-interface/search/type".toHttpUrl().newBuilder().addQueryParameter("search_type","video").addQueryParameter("keyword",keyword).addQueryParameter("page","1").build().toString()
        val root=json(url);if(root.optInt("code")!=0)error(root.optString("message","搜索失败"))
        val arr=root.optJSONObject("data")?.optJSONObject("result")?.optJSONArray("video")?:return emptyList()
        return (0 until arr.length()).mapNotNull{i->arr.optJSONObject(i)?.let{o->VideoItem(o.optString("bvid",o.optString("aid")),o.optString("bvid"),o.optLong("aid"),title=o.optString("title").replace(Regex("<[^>]*>"),""),owner=o.optString("author"),duration=o.optString("duration"),description=o.optString("description"))}}
    }
    fun videoInfo(id:String):VideoItem{
        val b="https://api.bilibili.com/x/web-interface/view".toHttpUrl().newBuilder()
        if(id.startsWith("BV",true))b.addQueryParameter("bvid",id)else b.addQueryParameter("aid",id.replace(Regex("(?i)^av"), ""))
        val root=json(b.build().toString());if(root.optInt("code")!=0)error(root.optString("message","解析失败"))
        val d=root.getJSONObject("data");val p=d.optJSONArray("pages")?.optJSONObject(0);val sec=d.optInt("duration")
        return VideoItem(d.optString("bvid",d.optString("aid")),d.optString("bvid"),d.optLong("aid"),p?.optLong("cid")?:d.optLong("cid"),d.optString("title"),d.optJSONObject("owner")?.optString("name")?:"未知UP主","${sec/60}:${(sec%60).toString().padStart(2,'0')}",d.optString("desc"))
    }
    fun videoPages(item:VideoItem):List<VideoItem>{
        val id=item.bvid.ifBlank{item.aid.toString()}
        val b="https://api.bilibili.com/x/web-interface/view".toHttpUrl().newBuilder()
        if(id.startsWith("BV",true))b.addQueryParameter("bvid",id)else b.addQueryParameter("aid",id.replace(Regex("(?i)^av"), ""))
        val root=json(b.build().toString());if(root.optInt("code")!=0)error(root.optString("message","分P解析失败"))
        val data=root.getJSONObject("data");val arr=data.optJSONArray("pages")?:return emptyList()
        return (0 until arr.length()).mapNotNull{i->arr.optJSONObject(i)?.let{p->val sec=p.optInt("duration");item.copy(id="${item.id}_${p.optInt("page")}",cid=p.optLong("cid"),title="${data.optString("title")} - P${p.optInt("page")} ${p.optString("part")}",duration="${sec/60}:${(sec%60).toString().padStart(2,'0')}")}}
    }
    private fun wbiKeys():Pair<String,String>{
        val wbi=json("https://api.bilibili.com/x/web-interface/nav").optJSONObject("data")?.optJSONObject("wbi_img")?:error("WBI 信息缺失")
        fun key(k:String)=wbi.optString(k).substringAfterLast('/').substringBefore('.')
        return key("img_url") to key("sub_url")
    }
    fun playUrl(item:VideoItem,quality:Int=80):JSONObject{
        val v=if(item.cid==0L)videoInfo(item.bvid.ifBlank{item.aid.toString()})else item
        val (img,sub)=wbiKeys()
        val signed=WbiSigner.sign(mapOf("avid" to v.aid.toString(),"cid" to v.cid.toString(),"qn" to quality.toString(),"fnval" to "4048","fourk" to "1"),img,sub)
        val b="https://api.bilibili.com/x/player/wbi/playurl".toHttpUrl().newBuilder()
        signed.forEach{(k,value)->b.addQueryParameter(k,value)}
        val root=json(b.build().toString());if(root.optInt("code")!=0)error(root.optString("message","视频流获取失败"))
        return root.optJSONObject("data")?:error("视频流为空")
    }
    fun downloadUrl(item:VideoItem,quality:Int=80):String{
        val data=playUrl(item,quality)
        data.optJSONArray("durl")?.optJSONObject(0)?.optString("url")?.takeIf{it.startsWith("http")}?.let{return it}
        if(data.optJSONObject("dash")!=null) error("该视频为 DASH 音视频分离格式；当前版本尚未完成音视频合并，已阻止下载无声视频")
        error("没有可下载的视频流，可能需要登录或内容受限")
    }
    fun subtitleTracks(item:VideoItem):List<Pair<String,String>>{
        val v=if(item.cid==0L)videoInfo(item.bvid.ifBlank{item.aid.toString()})else item
        val url="https://api.bilibili.com/x/player/v2".toHttpUrl().newBuilder().addQueryParameter("aid",v.aid.toString()).addQueryParameter("cid",v.cid.toString()).build().toString()
        val root=json(url);if(root.optInt("code")!=0)return emptyList()
        val arr=root.optJSONObject("data")?.optJSONObject("subtitle")?.optJSONArray("subtitles")?:return emptyList()
        return (0 until arr.length()).mapNotNull{i->arr.optJSONObject(i)?.let{s->s.optString("lan_doc") to s.optString("subtitle_url").let{if(it.startsWith("//"))"https:$it" else it}}}
    }
    fun danmakuXml(cid:Long)=get("https://api.bilibili.com/x/v1/dm/list.so?oid=$cid")
}