# 构建说明

CI 固定使用 JDK 17 和 Gradle 8.9，执行单元测试及 Android Debug 构建。

本地环境：
- JDK 17
- Android SDK Platform 35
- Android Build Tools 35.0.0

构建命令：

```sh
gradle test assembleDebug
```

GitHub Actions 构建完成后会上传 APK 工件。Release 工作流在推送 `v*` 标签时创建 GitHub Release。
