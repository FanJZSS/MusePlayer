# Muse Player

一款使用 Kotlin + Jetpack Compose + Media3（ExoPlayer）实现的 Android 本地 / 局域网视频播放器，界面与交互参考 MX Player。

[![Build & Release APK](https://github.com/vteovi/MusePlayer/actions/workflows/release.yml/badge.svg)](https://github.com/vteovi/MusePlayer/actions/workflows/release.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

## 功能

### 媒体库
- 扫描本地视频，支持视频 / 文件夹两种视图，网格 / 列表两种排列
- 按日期、名称、时长、大小、文件夹排序，支持搜索
- 隐藏文件夹管理，可显示系统隐藏目录（含 `.` 开头的目录）
- 网格 / 文件夹视图选择会持久化，下次打开保持上次状态

### 浏览
- 文件管理器式逐级浏览，返回键退回上一级
- 支持内部存储、SD / TF 卡以及 U 盘等外接存储，可在右上角切换存储卷
- 点击视频以当前目录为播放队列

### 网络与流媒体
- SMB（jcifs-ng）、FTP（commons-net）、WebDAV（OkHttp）服务器浏览与播放
- 局域网服务器自动发现，无需手动填写地址
- 支持 HTTP(S)、HLS（m3u8）、DASH、RTSP 等流媒体地址
- 可被其他应用以「打开方式 / 分享」调起，也可通过 `muse://play?url=<地址>` 深链播放
- SMB 等网络播放使用预读缓冲与磁盘缓存，减少卡顿

### 播放器
- 手势调节亮度（左半屏）、音量（右半屏）、进度（横向滑动），进度手势带画面预览
- 双击暂停 / 继续，长按 2 倍速播放
- 常见画面比例切换（含 9:16 竖屏比例）
- 自动匹配同目录字幕（SRT / ASS / SSA / VTT / TTML）
- 软解 / 硬解 / 自动解码切换
- 后台播放（MediaSessionService），通知栏与蓝牙耳机控制
- 状态栏、导航栏沉浸式显示，系统栏区域铺黑

### 其他
- 播放历史与断点续播，包含本地与网络播放记录
- 深色模式 OLED 纯黑背景

## 下载

前往 [Releases](https://github.com/vteovi/MusePlayer/releases) 下载最新的 `MusePlayer_vX.Y.Z.apk` 安装。

- 最低支持 Android 7.0（API 24）
- 首次启动需要授予视频读取权限；浏览 SD 卡 / U 盘或系统隐藏目录时，需要「所有文件访问权限」

## 从源码构建

环境要求：

- JDK 17
- Android SDK 34（compileSdk / targetSdk 34）
- Gradle 由仓库自带 wrapper 提供

```bash
# Windows
$env:JAVA_HOME="C:\Java\jdk-17"
.\gradlew.bat :app:assembleRelease

# macOS / Linux
./gradlew :app:assembleRelease
```

生成的 APK 位于 `app/build/outputs/apk/release/app-release.apk`。Windows 下也可以直接双击根目录的 `build.bat`，会自动构建并把 APK 复制到项目根目录、按 `MusePlayer_v<版本>.apk` 命名。

### 签名说明

本项目为个人开源项目，release 包使用仓库内的 `signing/debug.keystore`（Android 调试签名）签名，本机构建与 GitHub Actions 构建的签名一致，可直接覆盖安装。该签名仅用于分发便利，不适用于应用商店。

## 持续集成

`.github/workflows/release.yml` 会在推送 `v*` 标签（或手动触发）时自动：

1. 使用 JDK 17 构建 release APK
2. 按版本号重命名为 `MusePlayer_v<版本>.apk`
3. 上传构建产物，并创建对应的 GitHub Release 附带 APK

## 技术栈

- Kotlin 1.9 / AGP 8.5 / Gradle 8.7
- Jetpack Compose（Material 3）、Navigation Compose
- Media3 / ExoPlayer 1.4.1：MediaSessionService、自定义 DataSource、HLS / DASH / RTSP
- jcifs-ng（SMB）、commons-net（FTP）、OkHttp（WebDAV）

## 目录结构

```
app/src/main/java/com/muse/player
├── data/          媒体库扫描、历史记录、设置、网络客户端与局域网发现
├── playback/      Media3 播放服务与 SMB / FTP / WebDAV 自定义数据源
└── ui/            Compose 界面：媒体库、浏览、网络、播放器、设置
```

## 许可证

[MIT](LICENSE)

本项目仅用于学习与个人使用，请勿用于传播未经授权的内容。
