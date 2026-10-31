package app.bilidownload.next

import android.app.DownloadManager
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import app.bilidownload.next.data.VideoItem
import app.bilidownload.next.download.VideoDownloadManager
import app.bilidownload.next.network.BiliApi
import app.bilidownload.next.parser.VideoInputParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity:ComponentActivity() {
    private val api by lazy { BiliApi() }
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme=lightColorScheme(primary=androidx.compose.ui.graphics.Color(0xFF00A1D6))) {
                HomeScreen(
                    onSearch={q,done -> lifecycleScope.launch {
                        val result=withContext(Dispatchers.IO) { runCatching { val id=VideoInputParser.parse(q); if(id!=null) listOf(api.videoInfo(id)) else api.search(q) } }
                        result.onSuccess { done(it, null) }.onFailure { done(emptyList(), it.message ?: "搜索失败") }
                    }},
                    onDownload={item,quality,report -> lifecycleScope.launch {
                        val result=withContext(Dispatchers.IO){runCatching{api.downloadUrl(item,quality)}}
                        result.onSuccess {
                            VideoDownloadManager(this@MainActivity).enqueue(it,item.title)
                            report("已加入系统下载队列")
                        }.onFailure { report(it.message ?: "下载失败") }
                    }}
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun HomeScreen(onSearch:(String,(List<VideoItem>,String?)->Unit)->Unit,onDownload:(VideoItem,Int,(String)->Unit)->Unit) {
    var query by remember{mutableStateOf("")}
    var results by remember{mutableStateOf(emptyList<VideoItem>())}
    var loading by remember{mutableStateOf(false)}
    var quality by remember{mutableIntStateOf(80)}
    var message by remember{mutableStateOf("搜索公开视频，或粘贴 BV / AV 号及视频链接")}
    Scaffold(topBar={TopAppBar(title={Column{Text("BiliDownload",fontWeight=FontWeight.Bold);Text("NEXT · 视频下载工具",style=MaterialTheme.typography.labelSmall)}},actions={IconButton(onClick={message="设置与账号管理模块正在接入"}){Icon(Icons.Default.Settings,"设置")}})}) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(horizontal=16.dp)) {
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(value=query,onValueChange={query=it},modifier=Modifier.fillMaxWidth(),singleLine=true,placeholder={Text("搜索视频 / BV号 / AV号 / 链接")},trailingIcon={IconButton(enabled=query.isNotBlank()&&!loading,onClick={loading=true;onSearch(query.trim()){items,error -> results=items;loading=false;message=error ?: if(items.isEmpty())"没有找到结果，请检查网络或输入内容" else "找到 ${items.size} 个视频"}}){Icon(Icons.Default.Search,"搜索")}})
            Spacer(Modifier.height(8.dp))
            Text("免登录搜索公开内容 · 受限画质及内容遵循账号权限",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Text("目标画质",style=MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                listOf(32 to "清晰", 64 to "高清", 80 to "1080P", 112 to "1080P+", 120 to "4K").forEach { (q,label) ->
                    FilterChip(selected=quality==q,onClick={quality=q},label={Text(label)})
                }
            }
            Spacer(Modifier.height(8.dp)); Text(message,style=MaterialTheme.typography.bodySmall)
            if(loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            LazyColumn(verticalArrangement=Arrangement.spacedBy(10.dp),contentPadding=PaddingValues(vertical=12.dp)) {
                items(results,key={it.id}) { item ->
                    ElevatedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp)) {
                            Text(item.title,style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold)
                            Spacer(Modifier.height(5.dp)); Text("${item.owner} · ${item.duration}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                            if(item.description.isNotBlank()){Spacer(Modifier.height(5.dp));Text(item.description,maxLines=2,style=MaterialTheme.typography.bodySmall)}
                            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End){Button(onClick={onDownload(item,quality){message=it}}){Icon(Icons.Default.Download,null);Spacer(Modifier.width(6.dp));Text("下载")}}
                        }
                    }
                }
            }
        }
    }
}