package app.bilidownload.next.network

import app.bilidownload.next.data.VideoItem
import app.bilidownload.next.media.DownloadSource
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class BiliApi(private val cookieProvider: () -> String? = { null }) {
    private val client=OkHttpClient.Builder().connectTimeout(15,TimeUnit.SECONDS).readTimeout(30,TimeUnit.SECONDS).build()
    private fun get(url:String):String {
        val req=Request.Builder().url(url).header("User-Agent","Mozilla/5.0 BiliDownloadNext/0.1").header("Referer","https://www.bilibili.com/").apply { cookieProvider()?.takeIf { it.isNotBlank() }?.let { header("Cookie", it) } }.build()
        client.newCall(req).execute().use{r->if(!r.isSuccessful)error("HTTP ${r.code}");return r.body?.string()?:error("空响应")}
    }
    private fun json(url:String)=JSONObject(get(url))
    fun search(keyword:String, page:Int=1):List<VideoItem> = searchPage(keyword,page).first

    fun searchPage(keyword:String,page:Int=1):Pair<List<VideoItem>,Int>{
        val url="https://api.bilibili.com/x/web-interface/search/type".toHttpUrl().newBuilder()
            .addQueryParameter("search_type","video")
            .addQueryParameter("keyword",keyword)
            .addQueryParameter("page",page.coerceAtLeast(1).toString())
            .build().toString()
        val root=json(url)
        if(root.optInt("code")!=0) error(root.optString("message","搜索失败"))
        val data=root.optJSONObject("data") ?: return emptyList<VideoItem>() to 1
        val arr=data.optJSONObject("result")?.optJSONArray("video")
            ?: return emptyList<VideoItem>() to data.optInt("numPages",1).coerceAtLeast(1)
        val items=(0 until arr.length()).mapNotNull{i->arr.optJSONObject(i)?.let{o->
            VideoItem(o.optString("bvid",o.optString("aid")),o.optString("bvid"),o.optLong("aid"),
                title=o.optString("title").replace(Regex("<[^>]*>"),""),
                owner=o.optString("author"),duration=o.optString("duration"),description=o.optString("description"))
        }}
        return items to data.optInt("numPages",1).coerceAtLeast(1)
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
    fun downloadSource(item:VideoItem,quality:Int=80):DownloadSource{
        val data=playUrl(item,quality)
        data.optJSONArray("durl")?.optJSONObject(0)?.optString("url")
            ?.takeIf{it.startsWith("http")}?.let{return DownloadSource.Direct(it)}
        val dash=data.optJSONObject("dash") ?: error("没有可下载的视频流，可能需要登录或内容受限")
        val videos=dash.optJSONArray("video") ?: error("视频轨道为空")
        val videoCandidates=(0 until videos.length()).mapNotNull { index ->
            videos.optJSONObject(index)?.let { obj ->
                val urls=streamUrls(obj)
                if(urls.isEmpty()) null else Triple(obj.optInt("id"),obj.optString("codecs"),urls)
            }
        }
        val selectedVideo=videoCandidates.filter { it.first<=quality }.maxByOrNull { it.first }
            ?: videoCandidates.maxByOrNull { it.first }
            ?: error("没有可用的视频轨道")
        val audioCandidates=mutableListOf<Pair<Long,List<String>>>()
        val audios=dash.optJSONArray("audio")
        if(audios!=null) for(index in 0 until audios.length()) {
            val obj=audios.optJSONObject(index) ?: continue
            val urls=streamUrls(obj)
            if(urls.isNotEmpty()) audioCandidates.add(obj.optLong("bandwidth") to urls)
        }
        val dolby=dash.optJSONObject("dolby")?.optJSONArray("audio")
        if(dolby!=null) for(index in 0 until dolby.length()) {
            val obj=dolby.optJSONObject(index) ?: continue
            val urls=streamUrls(obj)
            if(urls.isNotEmpty()) audioCandidates.add(obj.optLong("bandwidth") to urls)
        }
        val flac=dash.optJSONObject("flac")?.optJSONObject("audio")
        if(flac!=null) {
            val urls=streamUrls(flac)
            if(urls.isNotEmpty()) audioCandidates.add(flac.optLong("bandwidth") to urls)
        }
        val selectedAudio=audioCandidates.maxByOrNull { it.first }?.second
            ?: error("未返回音频轨道，已阻止下载无声视频")
        return DownloadSource.Dash(
            videoUrls=selectedVideo.third,
            audioUrls=selectedAudio,
            quality=selectedVideo.first,
            videoCodec=selectedVideo.second,
            audioCodec="DASH Audio"
        )
    }

    private fun streamUrls(obj:JSONObject):List<String>{
        val urls=mutableListOf<String>()
        obj.optString("baseUrl").takeIf{it.startsWith("http")}?.let{urls.add(it)}
        val backup=obj.optJSONArray("backupUrl")
        if(backup!=null) for(index in 0 until backup.length()) {
            backup.optString(index).takeIf{it.startsWith("http")}?.let{urls.add(it)}
        }
        return urls.distinct()
    }

    fun downloadUrl(item:VideoItem,quality:Int=80):String =
        when(val source=downloadSource(item,quality)) {
            is DownloadSource.Direct -> source.url
            is DownloadSource.Dash -> error("该视频需要下载视频与音频两条轨道并合并，请使用音视频合并下载流程")
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