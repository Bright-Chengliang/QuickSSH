# QuickSSH

> 面向真实远程开发工作流的 Android SSH 工作台：持久终端、SFTP 文件传输与 SSH 隧道。

[![Android](https://img.shields.io/badge/Android-API%2026%2B-3DDC84?logo=android&logoColor=white)](app/src/main/AndroidManifest.xml)
[![Kotlin](https://img.shields.io/badge/Kotlin-1.9.22-7F52FF?logo=kotlin&logoColor=white)](gradle/libs.versions.toml)
[![License](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)

[English README](README.en.md)

QuickSSH 是一款面向 Android 的 SSH 客户端，将服务器配置、多工作区、终端、文件传输和 SSH 隧道整合在一个应用中。它使用 Jetpack Compose 构建界面，通过 SSHJ 建立 SSH/SFTP 连接，并用 Android Keystore 加密保存在设备本地的密码和私钥。

Developed by [Bright-Chengliang](https://github.com/Bright-Chengliang) · © 2026 Chengliang Liu · [MIT License](LICENSE)

## 项目定位

手机上的 SSH 工具通常只能解决“连上服务器并输入命令”，但真实工作流还包括多套环境切换、长连接保活、文件往返和访问内网服务。QuickSSH 的设计目标，是把这些高频但容易割裂的操作收敛到一个工作区，同时把凭据、后台任务和传输状态放在明确的边界内。

## 核心亮点与设计动机

### 1. 服务器配置与多工作区

**设计动机：** 同一台服务器往往对应多个项目或部署环境；如果只保存一条 SSH 连接，用户每次都要重新输入目录、终端偏好和连接后命令，容易连错环境。

**具体实现：** QuickSSH 按 `host / port / username / authType` 聚合服务器，并允许每个工作区独立保存工作目录、连接后命令、终端字号、自动换行、`TERM` 和快捷命令。服务器、工作区、隧道预设和传输历史都通过 Room 持久化，并通过数据库迁移保留升级后的历史数据。

### 2. 可长期运行的 SSH 终端

**设计动机：** 移动端最容易丢失的是长连接。切到后台、锁屏或网络短暂变化后，终端会话如果直接绑定 UI，就会中断正在运行的任务。

**具体实现：** SSH 会话由前台服务托管，支持网络恢复后的自动重连；终端层处理 ANSI 颜色、光标控制、TUI 应用、滚轮回退、行列选择、复制，以及 UTF-8 和 GB18030 输出解码。密码认证与 OpenSSH 私钥认证走统一的连接生命周期。

### 3. 可观察、可恢复的 SFTP 传输

**设计动机：** 手机上进行文件传输时，用户最关心的不是“请求是否发出”，而是任务是否排队、进度到哪里、失败后能否恢复，以及目录递归操作是否可控。

**具体实现：** 文件浏览器支持多选、分页、目录递归下载和下载计划确认；上传支持自动改名、覆盖和失败三种冲突策略。传输任务由前台服务执行，提供队列、暂停/恢复、取消等待、断线重试和实时进度，并记录最近 300 条传输历史。项目对 SSHJ 的传输层做了小范围补丁，使进度回调能够进入应用自己的任务队列。

### 4. SSH 隧道与移动端内网访问

**设计动机：** SSH 的价值不只在终端；很多开发服务只绑定在远端回环地址或内网中，手机需要通过端口转发访问，而不是把服务暴露到公网。

**具体实现：** 支持本地端口转发、自动分配本地端口、多隧道并行和断线重连；隧道由独立前台服务托管，并提供 WebView 访问转发后的本地地址和 HTTP Basic Auth 交互。

### 5. 凭据保护与主机密钥验证

**设计动机：** SSH 客户端保存的是高价值凭据；仅仅把密码放进本地数据库，或在服务 Intent 中传递明文，都无法形成可信的安全边界。

**具体实现：** 密码和私钥使用 Android Keystore 中的 AES-GCM 密钥加密后入库，不通过前台服务 Intent 明文传递；可选生物识别解锁，连接或编辑凭据前要求验证。主机密钥验证默认记录首次观察到的指纹，开启严格模式后拒绝未知或变化的指纹，并将待确认指纹交给用户处理。服务器配置支持导出/导入，导出时可以用用户提供的密码进行 PBKDF2 + AES-GCM 加密。

## 架构概览

```mermaid
flowchart LR
    UI[Jetpack Compose 界面] --> DB[Room 数据库]
    UI --> Services[前台服务]
    Services --> SSH[SSHJ SSH / SFTP]
    Services --> Tunnel[本地端口转发]
    Credentials[Android Keystore] --> Services
    SSH --> Host[远程服务器]
    Tunnel --> WebView[本地 WebView]
```

项目按职责拆分为：`data/` 负责持久化、DAO 和迁移，`security/` 负责 Keystore 加密，`service/` 负责 SSH/SFTP/隧道生命周期，`ui/screens/` 负责 Compose 工作流；`net/schmizz/sshj/` 只保留针对传输进度的局部补丁，没有另起一套传输实现。

## 测试与验证

仓库包含终端渲染与输入、SSH 认证和主机密钥决策、Room 迁移、加密备份、传输队列、隧道参数以及关键 UI 流程测试。

```powershell
.\gradlew.bat testDebugUnitTest
```

连接 Android 模拟器或真机后，可运行 instrumentation tests：

```powershell
.\gradlew.bat connectedDebugAndroidTest
```

## 技术栈

- Android：`minSdk 26`，`targetSdk 34`
- UI：Jetpack Compose + Material 3
- 数据库：Room
- SSH/SFTP：SSHJ + Bouncy Castle
- 安全：Android Keystore、AndroidX Biometric

## 项目结构

```text
app/src/main/java/com/quickssh/app/
├── MainActivity.kt              # 导航、连接编排、传输队列
├── data/                        # Room 实体、DAO、数据库迁移、备份编解码
├── security/                    # Android Keystore 加密
├── service/                     # SSH、SFTP、传输和隧道前台服务
├── ui/screens/                  # 列表、添加/编辑、终端、传输、隧道、设置
└── utils/                       # ANSI 渲染、终端缓冲
net/schmizz/sshj/                # 针对 SFTP 进度与传输行为的本地补丁
scripts/                         # 本地构建与调试辅助脚本
```

## 构建

环境要求：

- Android Studio（或支持 Gradle 的命令行环境）
- JDK 17
- Android SDK 34
- Gradle 8.5（使用仓库内的 wrapper）

Windows：

```powershell
.\gradlew.bat assembleDebug
```

macOS / Linux：

```bash
./gradlew assembleDebug
```

APK 输出到 `app/build/outputs/apk/debug/app-debug.apk`。

安装到已连接的设备或模拟器：

```powershell
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

运行单元测试：

```powershell
.\gradlew.bat testDebugUnitTest
```

## 数据与隐私说明

- 仓库不包含任何真实服务器地址、账号、密码、私钥、本机路径或调试截图。
- `local.properties`、`.workbuddy/`、`.QuickSSH/`、`outputs/`、`artifacts/`、构建产物和日志文件均已加入 `.gitignore`，不会进入版本库。
- App 的服务器配置、传输历史和隧道预设仍保存在设备本地的 Room 数据库中，凭据使用 Android Keystore 加密；本次清理不修改数据库版本、表结构或本地数据文件。
- 本地调试脚本 `scripts/Get-QuickSshDebugServer.ps1` 从被忽略的 `.workbuddy/quickssh-debug-server.local.ps1` 读取主机和工作目录，从 `.workbuddy/secrets/` 读取凭据，未配置时会明确报错，不会把隐私值写回源码。

## 开发脚本

`scripts/install-mumu-debug.ps1` 用于在 MuMu 模拟器上安装并启动 Debug APK。它只依赖 ADB，不包含任何服务器或账号信息。

## License

本项目使用 [MIT License](LICENSE)，版权归 Chengliang Liu 所有。

## Author

- GitHub: [Bright-Chengliang](https://github.com/Bright-Chengliang)
- Copyright: © 2026 Chengliang Liu
- Project notice: [NOTICE](NOTICE)
