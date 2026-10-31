# BiliDownload Next

独立实现的 Android Bilibili 下载工具，参考成熟下载器的模块边界与工程实践，不复制上游源码、品牌素材或二进制文件。

## 当前工程基线

- Kotlin + Android 原生工程，Jetpack Compose Material 3 界面
- 分层组织：网络/API、解析、下载、账号、媒体、数据库、设置
- 未登录可搜索公开视频并查看公开信息；需要登录或会员权限的资源遵循 Bilibili 服务端权限
- 支持 BV / AV / 视频链接 / 搜索关键词入口
- GitHub Actions 自动执行 Android Debug 构建并上传 APK
- GPL-3.0 项目许可证；依赖各自遵循其许可证

## 构建

需要 JDK 17、Android SDK Platform 35、Build Tools 35.0.0。

```bash
./gradlew --no-daemon test assembleDebug
```

APK：`app/build/outputs/apk/debug/app-debug.apk`

## 说明

本项目不是 Bilibili 官方产品。下载质量、可访问内容、音视频流地址和登录能力由 Bilibili 接口及账号权限决定。请仅下载你有权保存的内容，并遵守当地法律、平台条款及版权要求。

## 功能状态

首个工程提交提供可运行的应用骨架、搜索与视频信息 API 接入、下载任务基础设施、设置与构建工作流。弹幕、字幕、音频封装转换、账号授权、批量队列等模块会按可验证的接口逐步接通；不会把尚未实现的能力标成已完成。
