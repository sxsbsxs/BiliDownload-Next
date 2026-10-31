# BiliDownload Next

面向 Android 的 Kotlin Bilibili 下载器。项目以 [KafuuNeko/BiliDownload](https://github.com/KafuuNeko/BiliDownload) 作为功能与模块架构参考，独立实现本项目代码，不复制上游品牌素材或二进制文件。

## 已接入功能

- Kotlin + Jetpack Compose Material 3 中文界面
- 关键词搜索公开视频、BV / AV 号与链接解析
- 搜索结果分页
- 多P视频列表与分P选择
- 画质请求档位：清晰、高清、1080P、1080P+、4K（最终取决于账号权限及视频源）
- WBI 播放流解析，识别 DASH 视频与音频轨道及备用地址
- DASH 视频/音频分别下载后尝试通过 Android MediaExtractor + MediaMuxer 合并 MP4
- B站网页扫码登录二维码生成与状态轮询
- Cookie 使用 Android Keystore AES-GCM 加密保存；登录状态读取与退出登录
- 字幕轨道提交下载、弹幕 XML 导出
- 登录账号中心：观看历史、收藏夹（首个收藏夹视频）、稍后再看列表
- EP / SS 番剧入口解析并列出剧集
- 本地媒体库扫描与删除接口（Downloads/BiliDownload）
- 下载偏好设置模型：批量画质策略、保存位置、文件名模板、合并后清理源文件开关
- 系统下载队列历史、进度、状态与取消
- GitHub Actions 自动测试、构建 APK 并上传工件

## 验证状态

| 模块 | 代码状态 | 验证说明 |
|---|---|---|
| 关键词搜索与翻页 | 已接入 | 需以实际 B站接口响应验证 |
| BV / AV 解析、详情与分P | 已接入 | 需以实际视频验证 |
| 画质与 WBI 播放流 | 已接入 | 高画质受账号权限及视频限制 |
| DASH 音视频合并 | 已接入初版 | 依赖设备解码器和源文件封装，仍需真机验证 |
| 扫码登录与 Cookie 加密 | 已接入初版 | 需扫码确认并验证 Cookie 返回情况 |
| 字幕、弹幕导出 | 已接入 | 字幕按 B站 JSON 文件保存，弹幕保存 XML |
| 账号历史/收藏/稍后再看 | 已接入接口及基础页面 | 需要登录；接口权限和账号数据需真机验证 |
| EP / SS 番剧解析 | 已接入初版 | 剧集流下载及番剧特殊权限仍需真机验证 |
| 本地资源库 | 已接入媒体库查询/删除层 | UI 管理、批量导出仍未完成 |
| 下载偏好 | 已接入本地设置模型 | 设置页面尚未接入所有选项 |
| 下载任务进度与取消 | 已接入 | 基于 Android DownloadManager；DASH 合并任务暂不纳入系统队列 |
| 自动发布 Release | 已配置 | 待首次标签发布验证 |

## 构建

需要 JDK 17、Android SDK Platform 35 和 Android Build Tools 35.0.0。

```bash
gradle --no-daemon test assembleDebug
```

Debug APK 输出：`app/build/outputs/apk/debug/app-debug.apk`。

## 许可证

本项目采用 GNU GPL v3.0。功能与架构参考项目：[KafuuNeko/BiliDownload](https://github.com/KafuuNeko/BiliDownload)。

## 免责声明

本项目不是 Bilibili 官方产品。请仅下载你有权保存的内容，并遵守当地法律、平台条款和版权要求。接口可用性、下载权限与画质由 Bilibili 服务端决定。
