package app.bilidownload.next

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import app.bilidownload.next.account.BiliQrLoginRepository
import app.bilidownload.next.account.CookieVault
import app.bilidownload.next.account.QrCodeBitmap
import app.bilidownload.next.account.QrLoginCode
import app.bilidownload.next.account.QrLoginPoll
import app.bilidownload.next.data.VideoItem
import app.bilidownload.next.download.DashMuxerDownloadManager
import app.bilidownload.next.download.DownloadTask
import app.bilidownload.next.media.DownloadSource
import app.bilidownload.next.download.VideoDownloadManager
import app.bilidownload.next.network.BiliApi
import app.bilidownload.next.parser.VideoInputParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val cookieVault by lazy { CookieVault(this) }
    private val api by lazy { BiliApi { cookieVault.read() } }
    private val login by lazy { BiliQrLoginRepository(this) }
    private val downloads by lazy { VideoDownloadManager(this) }
    private val dashDownloads by lazy { DashMuxerDownloadManager(this) { cookieVault.read() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = androidx.compose.ui.graphics.Color(0xFF00A1D6))) {
                HomeScreen(
                    onSearch = { q, page, done ->
                        lifecycleScope.launch {
                            val result = withContext(Dispatchers.IO) {
                                runCatching {
                                    val id = VideoInputParser.parse(q)
                                    if (id != null) listOf(api.videoInfo(id)) to 1 else api.searchPage(q, page)
                                }
                            }
                            result.onSuccess { done(it.first, it.second, null) }
                                .onFailure { done(emptyList(), 1, it.message ?: "搜索失败") }
                        }
                    },
                    onLoadPages = { item, done ->
                        lifecycleScope.launch {
                            val result = withContext(Dispatchers.IO) { runCatching { api.videoPages(item) } }
                            result.onSuccess { done(it, null) }
                                .onFailure { done(emptyList(), it.message ?: "分P读取失败") }
                        }
                    },
                    onDownload = { item, quality, report ->
                        lifecycleScope.launch {
                            val result = withContext(Dispatchers.IO) { runCatching { api.downloadSource(item, quality) } }
                            result.onSuccess { source ->
                                runCatching {
                                    when (source) {
                                        is DownloadSource.Direct -> {
                                            downloads.enqueue(source.url, item.title)
                                            "已加入下载队列"
                                        }
                                        is DownloadSource.Dash -> {
                                            dashDownloads.downloadAndMerge(source, item.title)
                                            "音视频已合并，保存至 Download/BiliDownload"
                                        }
                                    }
                                }.onSuccess(report).onFailure { report(it.message ?: "下载失败") }
                            }.onFailure { report(it.message ?: "下载失败") }
                        }
                    },
                    onExtras = { item, report ->
                        lifecycleScope.launch {
                            val result = withContext(Dispatchers.IO) { runCatching { api.subtitleTracks(item) } }
                            result.onSuccess { tracks ->
                                tracks.forEachIndexed { index, track ->
                                    downloads.enqueue(track.second, "${item.title}_${track.first.ifBlank { "字幕" }}_${index + 1}", "json")
                                }
                                if (item.cid > 0L) {
                                    downloads.enqueue(
                                        "https://api.bilibili.com/x/v1/dm/list.so?oid=${item.cid}",
                                        "${item.title}_弹幕",
                                        "xml"
                                    )
                                }
                                report("已提交 ${tracks.size} 条字幕和弹幕文件")
                            }.onFailure { report(it.message ?: "字幕/弹幕获取失败") }
                        }
                    },
                    onCreateQr = { withContext(Dispatchers.IO) { login.createQrCode() } },
                    onPollQr = { key -> withContext(Dispatchers.IO) { login.poll(key) } },
                    onAccount = { withContext(Dispatchers.IO) { login.accountName() } },
                    onLogout = { withContext(Dispatchers.IO) { login.logout() } },
                    onTasks = { withContext(Dispatchers.IO) { downloads.tasks() } },
                    onCancelTask = { id -> withContext(Dispatchers.IO) { downloads.cancel(id) } }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeScreen(
    onSearch: (String, Int, (List<VideoItem>, Int, String?) -> Unit) -> Unit,
    onLoadPages: (VideoItem, (List<VideoItem>, String?) -> Unit) -> Unit,
    onDownload: (VideoItem, Int, (String) -> Unit) -> Unit,
    onExtras: (VideoItem, (String) -> Unit) -> Unit,
    onCreateQr: suspend () -> QrLoginCode,
    onPollQr: suspend (String) -> QrLoginPoll,
    onAccount: suspend () -> String?,
    onLogout: suspend () -> Unit,
    onTasks: suspend () -> List<DownloadTask>,
    onCancelTask: suspend (Long) -> Boolean
) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf(emptyList<VideoItem>()) }
    var loading by remember { mutableStateOf(false) }
    var quality by remember { mutableIntStateOf(80) }
    var page by remember { mutableIntStateOf(1) }
    var totalPages by remember { mutableIntStateOf(1) }
    var pageChoices by remember { mutableStateOf(emptyList<VideoItem>()) }
    var showPages by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("搜索公开视频，或粘贴 BV / AV 号及视频链接") }
    var accountName by remember { mutableStateOf<String?>(null) }
    var showAccount by remember { mutableStateOf(false) }
    var qrCode by remember { mutableStateOf<QrLoginCode?>(null) }
    var qrStatus by remember { mutableStateOf("准备二维码…") }
    var qrError by remember { mutableStateOf<String?>(null) }
    var showTasks by remember { mutableStateOf(false) }
    var tasks by remember { mutableStateOf(emptyList<DownloadTask>()) }

    LaunchedEffect(Unit) { accountName = runCatching { onAccount() }.getOrNull() }
    LaunchedEffect(qrCode?.key) {
        val key = qrCode?.key ?: return@LaunchedEffect
        qrStatus = "等待扫码"
        while (true) {
            delay(2000)
            val poll = try {
                onPollQr(key)
            } catch (error: Throwable) {
                qrStatus = error.message ?: "登录状态读取失败"
                continue
            }
            qrStatus = poll.message
            when (poll.state) {
                QrLoginPoll.State.SUCCESS -> {
                    accountName = runCatching { onAccount() }.getOrNull() ?: "已登录"
                    break
                }
                QrLoginPoll.State.EXPIRED -> break
                else -> Unit
            }
        }
    }
    LaunchedEffect(showTasks) {
        while (showTasks) {
            tasks = runCatching { onTasks() }.getOrDefault(emptyList())
            delay(1200)
        }
    }

    fun searchAt(targetPage: Int) {
        if (query.isBlank() || loading) return
        loading = true
        page = targetPage.coerceAtLeast(1)
        onSearch(query.trim(), page) { items, pages, error ->
            results = items
            totalPages = pages.coerceAtLeast(1)
            loading = false
            message = error ?: if (items.isEmpty()) "没有找到结果" else "第 ${page} 页 · ${items.size} 个视频"
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("BiliDownload", fontWeight = FontWeight.Bold)
                        Text("NEXT · 视频下载工具", style = MaterialTheme.typography.labelSmall)
                    }
                },
                actions = {
                    IconButton(onClick = { showTasks = true }) { Icon(Icons.Default.History, "下载任务") }
                    IconButton(onClick = {
                        showAccount = true
                        qrCode = null
                        qrError = null
                        qrStatus = if (accountName != null) "已登录：${accountName}" else "尚未登录"
                    }) { Icon(Icons.Default.Settings, "账号") }
                }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("搜索视频 / BV号 / AV号 / 链接") },
                trailingIcon = {
                    IconButton(enabled = query.isNotBlank() && !loading, onClick = { searchAt(1) }) {
                        Icon(Icons.Default.Search, "搜索")
                    }
                }
            )
            Spacer(Modifier.height(8.dp))
            Text(
                if (accountName == null) "免登录搜索公开内容 · 登录后按账号权限获取更高画质" else "已登录：${accountName} · 画质仍受视频授权限制",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            Text("目标画质", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(32 to "清晰", 64 to "高清", 80 to "1080P", 112 to "1080P+", 120 to "4K").forEach { (value, label) ->
                    FilterChip(selected = quality == value, onClick = { quality = value }, label = { Text(label) })
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(message, style = MaterialTheme.typography.bodySmall)
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
                items(results, key = { it.id }) { item ->
                    ElevatedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp)) {
                            Text(item.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(5.dp))
                            Text("${item.owner} · ${item.duration}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (item.description.isNotBlank()) {
                                Spacer(Modifier.height(5.dp))
                                Text(item.description, maxLines = 2, style = MaterialTheme.typography.bodySmall)
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                TextButton(onClick = { onExtras(item) { message = it } }) { Text("字幕/弹幕") }
                                TextButton(onClick = {
                                    onLoadPages(item) { pages, error ->
                                        pageChoices = pages
                                        showPages = pages.isNotEmpty()
                                        message = error ?: "读取到 ${pages.size} 个分P"
                                    }
                                }) { Text("分P") }
                                Button(onClick = { onDownload(item, quality) { message = it } }) {
                                    Icon(Icons.Default.Download, null)
                                    Spacer(Modifier.width(6.dp))
                                    Text("下载")
                                }
                            }
                        }
                    }
                }
                if (results.isNotEmpty()) {
                    item {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            TextButton(enabled = page > 1 && !loading, onClick = { searchAt(page - 1) }) { Text("上一页") }
                            Text("第 ${page} / ${totalPages} 页", modifier = Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodySmall)
                            TextButton(enabled = page < totalPages && !loading, onClick = { searchAt(page + 1) }) { Text("下一页") }
                        }
                    }
                }
            }
        }
    }

    if (showPages) {
        AlertDialog(
            onDismissRequest = { showPages = false },
            title = { Text("选择分P") },
            text = {
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(pageChoices, key = { it.id }) { part ->
                        TextButton(
                            onClick = {
                                showPages = false
                                onDownload(part, quality) { message = it }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(Modifier.fillMaxWidth()) {
                                Text(part.title, maxLines = 2)
                                Text(part.duration, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showPages = false }) { Text("关闭") } }
        )
    }

    if (showAccount) {
        AlertDialog(
            onDismissRequest = { showAccount = false; qrCode = null },
            title = { Text("哔哩哔哩账号") },
            text = {
                Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                    if (accountName != null) {
                        Text("当前账号：${accountName}")
                        Text("Cookie 使用 Android Keystore + AES-GCM 加密保存", style = MaterialTheme.typography.bodySmall)
                    } else if (qrCode != null) {
                        Image(
                            bitmap = remember(qrCode?.url) { QrCodeBitmap.encode(qrCode!!.url).asImageBitmap() },
                            contentDescription = "B站登录二维码",
                            modifier = Modifier.size(240.dp)
                        )
                        Text("请使用哔哩哔哩 APP 扫描二维码并确认")
                        Text(qrStatus, style = MaterialTheme.typography.bodySmall)
                    } else {
                        Text("登录后可按账号权限请求更高画质")
                        Text("使用 B 站 APP 扫码，不需要手机号登录", style = MaterialTheme.typography.bodySmall)
                    }
                    qrError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                Row {
                    if (accountName != null) {
                        TextButton(onClick = {
                            scope.launch {
                                onLogout()
                                accountName = null
                                qrCode = null
                                qrStatus = "已退出登录"
                            }
                        }) { Text("退出登录") }
                    } else {
                        TextButton(onClick = {
                            scope.launch {
                                qrError = null
                                qrStatus = "正在获取二维码…"
                                runCatching { onCreateQr() }
                                    .onSuccess { qrCode = it }
                                    .onFailure { qrError = it.message ?: "二维码获取失败" }
                            }
                        }) { Text(if (qrCode == null) "获取二维码" else "刷新二维码") }
                    }
                    TextButton(onClick = { showAccount = false; qrCode = null }) { Text("关闭") }
                }
            }
        )
    }

    if (showTasks) {
        AlertDialog(
            onDismissRequest = { showTasks = false },
            title = { Text("下载任务") },
            text = {
                if (tasks.isEmpty()) {
                    Text("暂无下载任务")
                } else {
                    LazyColumn(Modifier.heightIn(max = 480.dp)) {
                        items(tasks, key = { it.id }) { task ->
                            Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                                Text(task.title, maxLines = 2, style = MaterialTheme.typography.bodyMedium)
                                Text(taskStatus(task), style = MaterialTheme.typography.labelSmall)
                                if (task.status == android.app.DownloadManager.STATUS_RUNNING || task.status == android.app.DownloadManager.STATUS_PENDING) {
                                    LinearProgressIndicator(progress = { task.progress / 100f }, modifier = Modifier.fillMaxWidth())
                                    Text("${task.progress}% · ${task.downloadedBytes / 1024} KB / ${task.totalBytes / 1024} KB", style = MaterialTheme.typography.labelSmall)
                                }
                                if (task.status != android.app.DownloadManager.STATUS_SUCCESSFUL) {
                                    TextButton(onClick = {
                                        scope.launch {
                                            onCancelTask(task.id)
                                            tasks = onTasks()
                                        }
                                    }) { Text("取消任务") }
                                }
                                Divider()
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showTasks = false }) { Text("完成") } }
        )
    }
}

private fun taskStatus(task: DownloadTask): String = when (task.status) {
    android.app.DownloadManager.STATUS_PENDING -> "等待中"
    android.app.DownloadManager.STATUS_RUNNING -> "下载中"
    android.app.DownloadManager.STATUS_PAUSED -> "已暂停"
    android.app.DownloadManager.STATUS_SUCCESSFUL -> "已完成"
    android.app.DownloadManager.STATUS_FAILED -> "失败（代码 ${task.reason}）"
    else -> "未知状态"
}
