# 工程架构

当前工程按功能边界拆分，避免把所有网络、解析与下载逻辑堆在 Activity 中。

- `network`：HTTP 客户端与 Bilibili Web API 适配。
- `parser`：BV / AV / URL 输入识别。
- `data`：视频元数据模型。
- `download`：Android DownloadManager 下载适配。
- `account`：账号会话边界；Cookie 不允许写入日志。
- `media`：音视频流、字幕、弹幕和封装格式领域模型。
- `storage`：下载记录与任务状态模型。
- `core`：应用设置模型。

## 后续模块接入顺序

1. 持久化下载队列、前台服务、重试与断点续传。
2. Bilibili WBI 签名、DASH 音视频流选择与多分P解析。
3. 扫码登录状态机与加密 Cookie 存储。
4. 番剧、字幕、弹幕、音频提取和批量任务。
5. 通过可替换的原生媒体处理适配器实现封装转换。

未接通的能力不会在界面或文档中标记为已完成。
