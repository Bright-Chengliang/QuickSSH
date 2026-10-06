# QuickSSH 架构重构与需求设计规范文档

本文档记录并规范 QuickSSH 近期的一系列重构需求与核心架构方案，涵盖硬编码行为解耦、原生前置隧道能力建设、本地终端与 Termux 跨环境互通设计，以及模拟器多开测试隔离策略。

---

## 目录
1. [硬编码设计移除与行为解耦](#一硬编码设计移除与行为解耦)
2. [方案二：QuickSSH 原生“前置隧道（Pre-connect Tunnel）”架构](#二方案二quickssh-原生前置隧道pre-connect-tunnel架构)
3. [QuickSSH 与 Termux 跨环境互通与 Agent 协同方案](#三quickssh-与-termux-跨环境互通与-agent-协同方案)
4. [多实例模拟器（MuMu）测试隔离规范](#四多实例模拟器mumu测试隔离规范)
5. [落地步骤与任务路线图](#五落地步骤与任务路线图)

---

## 一、硬编码设计移除与行为解耦

### 1.1 现状与问题回顾
在原实现中存在特定的个性化逻辑：
* **现象**：当用户通过 SSH 连接本地回环地址（`127.0.0.1` / `localhost`）且端口拒绝连接（`Connection refused`）时，`SshClientHelper` 会强制调用 `TermuxWake`，通过 Android `startActivity` 启动第三方应用 Termux，并等待最多 12 秒探测端口开放，随后尝试把 QuickSSH 拉回前台。
* **根因**：原作者本身在手机端有一个依赖 Termux 启动云服务器隧道的私人脚本，需要先唤醒 Termux 才能连通对应机器。
* **缺陷**：
  1. **侵入性过高，违背通用客户端预期**：普通用户连接失败时突然闪退/切屏至 Termux，体验极差甚至被误认为应用异常；
  2. **Android 系统拦截风险**：国内主流 ROM（MIUI/HyperOS、ColorOS、OriginOS 等）对跨应用链式启动（Chain-start）会弹窗警告或直接静默拦截；
  3. **职责越界**：SSH 连接引擎层不应绑定特定第三方应用（`com.termux`）的启动生命周期。

### 1.2 改造决议
* **彻底移除 `TermuxWake`**：
  * 移除 `com.quickssh.app.service.TermuxWake.kt` 及其单元测试 `TermuxWakeTest.kt`；
  * 从 `SshClientHelper.kt` 中移除 `attemptLocalTermuxWake`、`termuxWakeAttempted` 相关的分支；
  * 恢复纯粹、标准的 SSH 错误处理与重连退避日志。当端口拒绝连接时，正常抛出并显示连接被拒绝的提示，不再做任何隐式跳转。

---

## 二、方案二：QuickSSH 原生“前置隧道（Pre-connect Tunnel）”架构

为了彻底摆脱外部 Termux 隧道脚本，将“连接目标前必须先建立端口转发隧道”的诉求内化为 QuickSSH 的通用原生能力。

### 2.1 业务场景
很多开发机、内网虚拟机或云上服务不能直接公网直连，必须通过跳板机（Bastion / Jump Host）进行端口转发：
* 例如：目标服务器位于内网（`127.0.0.1:2222`），需先通过跳板机 `jump.example.com` 建立 SSH 隧道将远程 `10.0.0.5:22` 映射到本地 `2222`。
* 过去需要用户在外部手动或借脚本跑隧道；现在由 QuickSSH 一体化托管。

### 2.2 实体与数据层扩展
1. **数据表与模型扩展**：
   * 在 `SshWorkspaceProfile` 与 `SshConfig` 中增加可选字段：
     ```kotlin
     @ColumnInfo(defaultValue = "NULL")
     val preConnectTunnelPresetId: Long? = null
     ```
   * 关联已有的 `SshTunnelPreset`（隧道预设）。
2. **Room 数据库迁移（v10 -> v11）**：
   * 编写 `MIGRATION_10_11`：为 `ssh_workspaces` 增加 `preConnectTunnelPresetId INTEGER DEFAULT NULL` 字段；
   * 编写相应的迁移单元测试，确保升级不丢失既有配置。
3. **备份与恢复（`SshConfigBackup`）**：
   * 在备份数据结构中包含 `preConnectTunnelPresetName`（优先按预设名称匹配恢复，若导入到不同环境亦可安全重新绑定）。

### 2.3 运行期生命周期编排（Orchestration）
1. **连接前钩子（Pre-connect Execution）**：
   * 当用户点击连接某个工作区/服务器时，检测是否配置了 `preConnectTunnelPresetId`；
   * 若配置了前置隧道：
     1. 检查对应的 `SshTunnelPreset` 是否已经在运行（通过 `TunnelForegroundService.tunnelStates` 匹配）；
     2. 若未运行，调用 `TunnelForegroundService.startTunnel(...)` 在后台启动该隧道；
     3. 协程等待探测本地端口就绪（使用轻量 Socket 轮询，带合理超时时间如 5s~8s）；
     4. 端口就绪后，启动目标 SSH 会话；
     5. 若隧道启动失败或超时，在终端或界面明确提示：“前置隧道启动超时，是否继续直连或取消”。
2. **断开与销毁联动**：
   * 可配置“会话结束时是否自动停止前置隧道”（或保持运行以供其他会话复用）。

### 2.4 UI 交互
* 在 `SshAddScreen` / `EditServerDialog` 中增加“高级网络设置”模块；
* 提供【前置关联隧道】下拉选择框，列出当前可用的所有隧道预设（或“无”）。

---

## 三、QuickSSH 与 Termux 跨环境互通与 Agent 协同方案

### 3.1 跨环境痛点分析
Android 系统基于 Linux UID 和 SELinux 实施严格的应用沙箱：
* QuickSSH 运行于自身的 UID（如 `u0_a350`），内置环境为自身 native 目录下的精简 BusyBox；
* Termux 运行于独立的 UID（如 `u0_a210`），私有数据区在 `/data/data/com.termux/files/`，拥有完整的 APT 生态、Python、Node.js 及 AI Agent（如 Claude Code / OpenCode / Aider 等）；
* **现状矛盾**：两个环境各自为政。QuickSSH 缺少 Termux 的完整工具链，而 Termux 又缺少 QuickSSH 精致的移动端触控终端界面与长连接后台服务；若在其中一个环境安装了 Agent，很难直接控制或配置另一个环境。

### 3.2 推荐的互通架构方案

#### 方案 A：Loopback SSH 标配通道（首选方案，已选定落地方案）
* **原理**：
  * Termux 本身是标准的 Linux 环境，安装 `openssh` 后可以在 `127.0.0.1:8023`（选定端口 8023）启动 `sshd`。
  * QuickSSH 是成熟的 SSH 客户端。
* **设计实现**：
  1. **QuickSSH 预置“Termux 专属节点”**：QuickSSH 内置一键添加“本机 Termux”连接向导/快捷模板，自动填充 `127.0.0.1`、端口 `8023`，并提供开箱即用的指令说明；
  2. **密钥与启动命令辅助**：界面提供一键复制 Termux 启动指令（`pkg install openssh && sshd -p 8023`），并支持通过密码或密钥快速互信；
  3. **Agent 互通体验**：
     * **在 QuickSSH 中运行/操作**：用户与 QuickSSH 侧的 Agent 直接通过标准 SSH 协议（`127.0.0.1:8023`）连入 Termux，享有完整的 Python、Node.js、Git 与外部 Agent 环境；
     * **在 Termux 中运行的 Agent**：可通过标准 SSH/Socket 反向连入 QuickSSH 或共享服务，实现完全无缝的跨环境双向协同。

#### 方案 B：Termux `RUN_COMMAND` 标准 IPC 广播
* **原理**：
  * Termux 官方提供了跨进程命令调用标准（`com.termux.RUN_COMMAND` Intent）。
* **设计实现**：
  1. 用户在 Termux 的 `~/.termux/termux.properties` 开启 `allow-external-apps = true`；
  2. QuickSSH 增加 `TermuxCommandBridge`：支持向 Termux 发送 Intent 执行命令，并将 stdout 写入临时文件或广播返回；
  3. 适合静默后台任务触发与脚本热启动。

#### 方案 C：共享存储交换区（Shared Storage Relay）
* **原理**：
  * 两者均可读写 Android 外部存储（如 `/sdcard/Download/QuickSSH-Relay/` 或 `/sdcard/Android/media/`）。
* **设计实现**：
  * 约定配置交换格式（JSON / YAML）与工作目录；
  * Agent 可在共享区读取 QuickSSH 导出的服务器列表、密钥、日志；
  * 支持跨进程文件流水线操作。

#### 方案 D：本地 Agent 协同中心（Agent Hub Protocol）
* 在 QuickSSH 内部开放一个仅限本地回环监听的轻量 REST/WebSocket API（例如 `http://127.0.0.1:37890`）；
* 暴露基础能力：
  * `/api/sessions`：查询、新建、关闭 SSH 会话；
  * `/api/tunnels`：查询、启动、停止隧道；
  * `/api/terminal/exec`：在指定会话中执行命令；
* 无论是 Termux 中的 Python Agent 还是外部脚本，都可以通过标准 HTTP 接口调用 QuickSSH 的全部网络与会话能力。

---

## 四、多实例模拟器（MuMu）测试隔离规范

由于开发机器上存在多个模拟器实例且有常驻业务，执行自动化测试时必须严格遵守隔离防线：

1. **实例画像**：
   * **实例 0（Index 0，`Android Device`）**：
     * PID: 61764 (Headless PID: 38012)
     * ADB 端口: **`16384`**
     * **当前状态**：正在前台运行自动化游戏挂机脚本（`com.dksgames.survive`）。
     * **安全红线**：**严禁任何终止、重启、安装、清理或向 16384 端口发送命令的操作！**
   * **实例 1（Index 1，`Android Device-1`）**：
     * ADB 端口: **`16416`**
     * **当前状态**：专用于 QuickSSH 的调试与自动化测试。
2. **执行原则**：
   * 所有针对模拟器的 ADB、Monkey、uiautomator 脚本，**必须强制带参数 `-s 127.0.0.1:16416`**；
   * 严禁执行全局 `adb devices | foreach` 类型的批量广播命令。

---

## 五、落地步骤与任务路线图

1. **第一阶段：清理与解耦**
   - [x] 完成架构分析与需求规范文档编写；
   - [ ] 移除 `TermuxWake.kt` 与 `TermuxWakeTest.kt`；
   - [ ] 清理 `SshClientHelper.kt` 中的唤醒分支与硬编码提示。
2. **第二阶段：数据模型与迁移**
   - [ ] 在 `SshWorkspaceProfile` / `SshConfig` 增加 `preConnectTunnelPresetId`；
   - [ ] 编写 Room 数据库 v10 -> v11 迁移代码与单元测试；
   - [ ] 在 `SshConfigBackup` 编解码中增加对该字段的支持。
3. **第三阶段：服务编排与 UI 联动**
   - [ ] 实现连接会话前自动拉起前置隧道并等待端口就绪的编排调度逻辑；
   - [ ] 在 `SshAddScreen` 添加“前置关联隧道”选择组件。
4. **第四阶段：验证与认知对齐**
   - [ ] 运行全部单元测试（`gradlew testDebugUnitTest`）；
   - [ ] 维护并对齐 AOCI 认知索引（`aoci_maintain` 与 `aoci_update_entry`）。
