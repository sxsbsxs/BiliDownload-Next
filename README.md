# BiliDownload Next

独立实现的 Android Bilibili 视频下载工具。工程组织参考成熟 Android 下载器的模块边界、媒体处理分层和自动化构建实践，不复制上游源代码、品牌素材或二进制文件。

## 工程特性

- Kotlin + Android 原生工程，Jetpack Compose Material 3 界面
- Gradle Kotlin DSL，JDK 17，Android SDK 35
- 分层模块：网络接口、输入解析、视频数据、下载、账号边界、媒体模型、存储模型、应用设置
- BV / AV / 视频链接与关键词搜索入口
- 未登录搜索公开内容；受限内容和高画质由平台接口及账号权限决定
- 使用 Android DownloadManager 提交下载并由系统通知显示进度
- GitHub Actions 自动运行测试、构建 Debug APK 并上传工件
- 推送 v* 标签时构建 Release APK 并发布 GitHub Release

## 当前实现状态

| 功能 | 状态 |
|---|---|
| Kotlin Android 工程与 Compose 首页 | 已接入 |
| 关键词搜索公开视频 | 已接入基础接口 |
| BV / AV 输入识别与视频详情 | 已接入基础接口 |
| 基础直链解析与系统下载任务 | 已接入，受接口返回和权限限制 |
| GitHub Actions Debug 构建 | 已配置 |
| Release 自动发布工作流 | 已配置，待首次标签验证 |
| 扫码登录与加密 Cookie | 接口边界已预留，尚未接通 |
| DASH 音视频分轨与高画质 WBI 签名 | 尚未接通 |
| 多分P、番剧、批量任务、断点续传 | 尚未接通 |
| 弹幕、字幕、音频提取与格式转换 | 数据模型/架构预留，尚未接通 |
| 历史、收藏夹、播放器、资源管理 | 后续实现 |

不把尚未实现的能力伪装成可用功能。后续开发按模块逐项接通并通过实际接口与构建验证。

## 构建

需要 JDK 17、Android SDK Platform 35 和 Android Build Tools 35.0.0。

```bash
gradle --no-daemon test assembleDebug
```

APK 输出：`app/build/outputs/apk/debug/app-debug.apk`。

## 免责声明

本项目不是 Bilibili 官方产品。请仅下载你有权保存的内容，并遵守当地法律、平台条款和版权要求。接口可用性、下载权限与画质由 Bilibili 服务端决定。
