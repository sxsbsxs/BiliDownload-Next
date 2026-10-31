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
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
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
import app.bilidownload.next.storage.MediaLibrary
import app.bilidownload.next.storage.LocalMedia
import app.bilidownload.next.core.DownloadPreferences
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
    private val accountApi by lazy { app.bilidownload.next.account.BiliAccountApi { cookieVault.read() } }
    private val downloads by lazy { VideoDownloadManager(this) { cookieVault.read() } }
    private val mediaLibrary by lazy { MediaLibrary(this) }
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
                                    val seasonId = VideoInputParser.parseSeason(q)
                                    val id = VideoInputParser.parse(q)
                                    when {
                                        seasonId != null -> accountApi.season(seasonId).episodes to 1
                                        id != null -> listOf(api.videoInfo(id)) to 1
                                        else -> api.searchPage(q, page)
                                    }
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
                    onDownloadBatch = { items, quality, report ->
                        lifecycleScope.launch {
                            var success = 0
                            var failed = 0
                            for (item in items) {
                                val result = withContext(Dispatchers.IO) { runCatching { api.downloadSource(item, quality) } }
                                result.onSuccess { source ->
                                    runCatching {
                                        when (source) {
                                            is DownloadSource.Direct -> downloads.enqueue(source.url, formatDownloadName(downloadPreferences.fileNameTemplate, item))
                                            is DownloadSource.Dash -> dashDownloads.downloadAndMerge(source, formatDownloadName(downloadPreferences.fileNameTemplate, item))
                                        }
                                    }.onSuccess { success++ }.onFailure { failed++ }
                                }.onFailure { failed++ }
                            }
                            report("批量任务处理完成：成功 $success 个，失败 $failed 个")
                        }
                    },
                    onDownload = { item, quality, report ->
                        lifecycleScope.launch {
                            val result = withContext(Dispatchers.IO) { runCatching { api.downloadSource(item, quality) } }
                            result.onSuccess { source ->
                                runCatching {
                                    when (source) {
                                        is DownloadSource.Direct -> {
                                            downloads.enqueue(source.url, formatDownloadName(downloadPreferences.fileNameTemplate, item))
                                            "已加入下载队列"
                                        }
                                        is DownloadSource.Dash -> {
                                            dashDownloads.downloadAndMerge(source, formatDownloadName(downloadPreferences.fileNameTemplate, item))
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
                    onLocalResources = { withContext(Dispatchers.IO) { mediaLibrary.list() } },
                    onDeleteLocalResource = { uri -> withContext(Dispatchers.IO) { mediaLibrary.delete(uri) } },
                    onAccountContent = { type -> withContext(Dispatchers.IO) {
                        when (type) {
                            "history" -> accountApi.history()
                            "watchlater" -> accountApi.watchLater()
                            "favorites" -> accountApi.favoriteFolders().firstOrNull()?.let { accountApi.favoriteVideos(it.id) }.orEmpty()
                            else -> emptyList()
                        }
                    } },
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
    onDownloadBatch: (List<VideoItem>, Int, (String) -> Unit) -> Unit,
    onExtras: (VideoItem, (String) -> Unit) -> Unit,
    onCreateQr: suspend () -> QrLoginCode,
    onPollQr: suspend (String) -> QrLoginPoll,
    onAccount: suspend () -> String?,
    onLogout: suspend () -> Unit,
    onTasks: suspend () -> List<DownloadTask>,
    onLocalResources: suspend () -> List<LocalMedia>,
    onDeleteLocalResource: suspend (android.net.Uri) -> Boolean,
    onAccountContent: suspend (String) -> List<VideoItem>,
    onCancelTask: suspend (Long) -> Boolean
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val downloadPreferences = remember(context) { DownloadPreferences(context) }
    var showDownloadSettings by remember { mutableStateOf(false) }
    var fileNameTemplate by remember { mutableStateOf(downloadPreferences.fileNameTemplate) }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf(emptyList<VideoItem>()) }
    var loading by remember { mutableStateOf(false) }
    var quality by remember { mutableIntStateOf(80) }
    var page by remember { mutableIntStateOf(1) }
    var totalPages by remember { mutableIntStateOf(1) }
    var pageChoices by remember { mutableStateOf(emptyList<VideoItem>()) }
    var selectedPartIds by remember { mutableStateOf(emptySet<String>()) }
    var showPages by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("搜索公开视频，或粘贴 BV / AV 号及视频链接") }
    var accountName by remember { mutableStateOf<String?>(null) }
    var showAccount by remember { mutableStateOf(false) }
    var qrCode by remember { mutableStateOf<QrLoginCode?>(null) }
    var qrStatus by remember { mutableStateOf("准备二维码…") }
    var qrError by remember { mutableStateOf<String?>(null) }
    var showTasks by remember { mutableStateOf(false) }
    var showAccountHub by remember { mutableStateOf(false) }
    var showLocalLibrary by remember { mutableStateOf(false) }
    var localResources by remember { mutableStateOf(emptyList<LocalMedia>()) }
    var accountTab by remember { mutableStateOf("history") }
    var accountItems by remember { mutableStateOf(emptyList<VideoItem>()) }
    var accountLoading by remember { mutableStateOf(false) }
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
                    IconButton(onClick = { showLocalLibrary = true; scope.launch { localResources = runCatching { onLocalResources() }.getOrDefault(emptyList()) } }) { Icon(Icons.Default.Folder, "本地资源") }
                    IconButton(onClick = { showTasks = true }) { Icon(Icons.Default.History, "下载任务") }
                    IconButton(onClick = { showAccountHub = true; accountTab = "history"; accountLoading = true; scope.launch { accountItems = runCatching { onAccountContent("history") }.getOrDefault(emptyList()); accountLoading = false } }) { Icon(Icons.Default.Person, "个人中心") }
                    IconButton(onClick = { showDownloadSettings = true }) { Icon(Icons.Default.Tune, "下载设置") }
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
                                        selectedPartIds = pages.map { it.id }.toSet()
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

    if (showDownloadSettings) {
        AlertDialog(
            onDismissRequest = { showDownloadSettings = false },
            title = { Text("下载设置") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = fileNameTemplate,
                        onValueChange = { fileNameTemplate = it; downloadPreferences.fileNameTemplate = it },
                        label = { Text("文件名模板") },
                        supportingText = { Text("支持 {title} 和 {part}，默认只使用视频标题") },
                        singleLine = true
                    )
                    Text("文件名会自动清理系统不允许的字符。", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = { showDownloadSettings = false }) { Text("完成") } }
        )
    }

    if (showLocalLibrary) {
        AlertDialog(
            onDismissRequest = { showLocalLibrary = false },
            title = { Text("本地资源 · Downloads/BiliDownload") },
            text = {
                if (localResources.isEmpty()) Text("还没有找到已保存的资源")
                else LazyColumn(Modifier.heightIn(max = 460.dp)) {
                    items(localResources, key = { it.id }) { media ->
                        Column(Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
                            Text(media.displayName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            Text("${media.mimeType} · ${(media.sizeBytes / 1024 / 1024)} MB", style = MaterialTheme.typography.labelSmall)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                TextButton(onClick = {
                                    scope.launch {
                                        runCatching { onDeleteLocalResource(media.uri) }
                                        localResources = runCatching { onLocalResources() }.getOrDefault(emptyList())
                                    }
                                }) { Text("删除") }
                            }
                            Divider()
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showLocalLibrary = false }) { Text("关闭") } }
        )
    }

    if (showAccountHub) {
        AlertDialog(
            onDismissRequest = { showAccountHub = false },
            title = { Text("个人中心") },
            text = {
                Column {
                    if (accountName == null) Text("登录后可查看账号数据", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf("history" to "观看历史", "favorites" to "收藏夹", "watchlater" to "稍后再看").forEach { (key, label) ->
                            FilterChip(selected = accountTab == key, onClick = {
                                accountTab = key; accountLoading = true
                                scope.launch { accountItems = runCatching { onAccountContent(key) }.getOrDefault(emptyList()); accountLoading = false }
                            }, label = { Text(label) }, enabled = accountName != null)
                        }
                    }
                    if (accountLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (!accountLoading && accountItems.isEmpty()) Text("暂无内容或接口暂不可用", modifier = Modifier.padding(vertical = 18.dp))
                    LazyColumn(Modifier.heightIn(max = 360.dp)) {
                        items(accountItems, key = { it.id }) { v ->
                            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                                Text(v.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                Text(v.owner + " · " + v.duration, style = MaterialTheme.typography.labelSmall)
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                    TextButton(onClick = { query = v.bvid.ifBlank { "av" + v.aid }; showAccountHub = false; searchAt(1) }) { Text("查看") }
                                    TextButton(onClick = { onDownload(v, quality) { message = it; showAccountHub = false } }) { Text("下载") }
                                }
                                Divider()
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showAccountHub = false }) { Text("关闭") } }
        )
    }

    if (showPages) {
        AlertDialog(
            onDismissRequest = { showPages = false },
            title = { Text("选择分P") },
            text = {
                Column {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(onClick = { selectedPartIds = if (selectedPartIds.size == pageChoices.size) emptySet() else pageChoices.map { it.id }.toSet() }) {
                            Text(if (selectedPartIds.size == pageChoices.size) "取消全选" else "全选")
                        }
                        Text("${selectedPartIds.size} / ${pageChoices.size} 已选", modifier = Modifier.padding(top = 12.dp), style = MaterialTheme.typography.labelSmall)
                    }
                    LazyColumn(Modifier.heightIn(max = 360.dp)) {
                        items(pageChoices, key = { it.id }) { part ->
                            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                Checkbox(checked = part.id in selectedPartIds, onCheckedChange = { checked ->
                                    selectedPartIds = if (checked) selectedPartIds + part.id else selectedPartIds - part.id
                                })
                                Column(Modifier.weight(1f)) {
                                    Text(part.title, maxLines = 2)
                                    Text(part.duration, style = MaterialTheme.typography.labelSmall)
                                }
                                TextButton(onClick = { showPages = false; onDownload(part, quality) { message = it } }) { Text("下载") }
                            }
                            Divider()
                        }
                    }
                }
            },
            confirmButton = {
                Row {
                    TextButton(enabled = selectedPartIds.isNotEmpty(), onClick = {
                        val selected = pageChoices.filter { it.id in selectedPartIds }
                        showPages = false
                        onDownloadBatch(selected, quality) { message = it }
                    }) { Text("下载选中") }
                    TextButton(onClick = { showPages = false }) { Text("关闭") }
                }
            }
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

private fun formatDownloadName(template: String, item: VideoItem): String {
    val part = item.id.substringAfterLast("_", "1")
    return template.replace("{title}", item.title).replace("{part}", part).ifBlank { item.title }
}
