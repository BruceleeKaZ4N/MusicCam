# MusicCam 当前状态

更新时间：2026-10-09（Asia/Shanghai）。

## Phase 0.5：ADB 真机连接与启动验证

状态：ADB 连接、Debug 构建、真机安装与启动验证已通过。Logcat 读取已执行，但未取得 MusicCam 日志条目，日志诊断能力仍待确认。本阶段仅更新本文档并保存本地验证证据，没有修改应用代码、架构、Manifest 或权限。

### 设备信息

| 项目 | 实际结果 |
| --- | --- |
| 用户提供的设备名称 | vivo S50 Pro mini |
| ADB 授权状态 | `device`，USB 连接成功；序列号不写入本文档 |
| 制造商 / 品牌 | vivo / vivo |
| 系统上报型号 | V2527A；product/device 为 PD2527 |
| Android | 16 / API 36，已通过 getprop 核实 |
| 系统构建 | PD2527C_A_16.0.19.3.W10 |
| 安全补丁 | 2026-07-01 |
| 厂商系统属性 | ro.vivo.os.name=Funtouch，ro.vivo.os.version=16.0；这些属性不足以确认设置页面中的营销系统版本，OriginOS 6 仍来自用户描述 |
| 测试用户 | 当前前台 Android 用户 0 |

### 实际执行与结果

开始时 `adb devices -l` 为空。用户完成连接/手机授权后再次检查为 `device`；本轮没有观察到 `unauthorized`，没有绕过授权。

所有命令使用 `source .local/env.sh` 的现有 JDK、SDK、Gradle 缓存，ADB 明确选择唯一已授权设备。

| 验证 | 实际命令 / 结果 |
| --- | --- |
| 项目检查 | 已阅读 AGENTS.md、PROJECT.md、DECISIONS.md、STATUS.md，并检查构建配置、Manifest、MainActivity 和 Git 状态 |
| ADB | `adb devices -l`：唯一设备处于 device 状态；getprop 返回 vivo / V2527A / Android 16 / API 36 |
| Debug 构建 | `./gradlew assembleDebug --offline --no-daemon`：BUILD SUCCESSFUL，3 秒，34 个任务均为 UP-TO-DATE；已有 AGP/Gradle 弃用提示仍在，无构建错误 |
| 安装 | `adb -s <本地设备选择器> install -r app/build/outputs/apk/debug/app-debug.apk`：Performing Streamed Install / Success |
| 包信息 | `cmd package list packages --user 0 -U dev.musiccam.prototype`：安装包存在，App UID 10366 |
| 冷启动 | 先 force-stop 本应用，再 `am start -W --user 0 -n dev.musiccam.prototype/.MainActivity`：Status: ok / LaunchState: COLD / TotalTime: 195 ms / WaitTime: 197 ms |
| 观察 | 启动后持续观察 30 秒；PID 保持 16067，进程存活；dumpsys activity 显示 topResumedActivity 为 MusicCam，任务 visible=true |
| 前台窗口 | dumpsys window 的 mCurrentFocus / mFocusedApp 均指向 MusicCam MainActivity |
| 视觉验证 | 使用 ADB 截图并查看，确认 Phase 0 启动说明页正常显示，未出现崩溃或无响应弹窗 |
| 退出记录 | `dumpsys activity exit-info dev.musiccam.prototype` 未返回该应用退出记录 |

### Logcat 与诊断限制

- 在启动前开始读取 `main/system/crash` 缓冲区，按 App UID 过滤并覆盖 30 秒观察窗口；命令无报错，应用日志文件为 0 行。
- 随后按本次 PID 16067 读取 `main/system/crash`，包括显式 `*:V` 过滤设置；返回码为 0，stderr 为空，仍为 0 行。
- 另读 system/crash 缓冲区并在内存中过滤本应用包名，均未发现 MusicCam 条目。只保留本应用相关结果，没有保存其他应用日志。
- `logcat -g` 能读取缓冲区信息，系统确有日志数据。未取得本应用条目的原因没有查实，不能直接归因于厂商禁用日志，也不能把空日志当成“所有异常已排除”。
- 默认包查询曾输出 `Shell does not have permission to access user 666`，同时返回了本应用包信息。限定当前用户 `--user 0` 后查询正常；这属于其他用户空间访问提示，不是 MusicCam 崩溃。

结论：本次冷启动和 30 秒观察未发现崩溃或 ANR，页面可见、前台 Activity 与进程状态正常。基本真机安装/启动开发条件已具备；进入 Phase 1 时应先确认应用调试日志能被读取，再依赖日志诊断权限与音频会话。未进行任何音频捕获、耳机、相机或音视频同步验证。

### 本地验证证据

`.local/phase0.5/` 已被 Git 忽略，包含 build.log、install.log、device-info.json、launch.txt、launch-summary.json、activity-state.txt、window-focus.txt、exit-info.txt、Logcat 读取结果与 launch-screen.png。设备选择器仅保留在此本地目录，不提交或公开。

参考：[Android 官方真机连接与 USB 调试说明](https://developer.android.com/studio/run/device)、[ADB 官方命令说明](https://developer.android.com/tools/adb)。

## Phase 0（2026-10-08 历史记录）

状态：Phase 0 项目初始化已完成，Debug APK 构建和 Lint 已通过；真机启动未执行。尚未实现播放音频捕获或相机。

已创建四份工程文档、README、单模块 Kotlin 项目、平台启动说明页、基础资源、Gradle Wrapper、Git 忽略/换行规则和本地环境入口。初始化了本地 Git 仓库，文件尚未提交；无远程仓库或推送。

## Phase 0 环境检查（历史记录）

| 项目 | 实际结果 |
| --- | --- |
| 目录 | 开始时 `/Users/brucelee/MusicCam` 为空，无已有代码或 Git 仓库 |
| 主机 | macOS 27.0.1 / arm64；M4 型号来自用户描述 |
| 默认 Java | `/usr/bin/java` 为系统入口，找不到注册的运行时；JAVA_HOME 未设置 |
| 可用 JDK | `/opt/homebrew/opt/openjdk@17`，java/javac 17.0.20.1，实际执行成功 |
| SDK | `/Users/brucelee/android-toolchain/android-sdk`；sdkmanager --list_installed 成功 |
| 平台/工具 | API 36、37.0；Build Tools 35.0.0、36.0.0；命令行工具和模拟器已安装 |
| ADB | adb 1.0.41 / Platform Tools 37.0.1，实际执行成功；未加入默认 PATH |
| 设备 | `adb devices -l` 列表为空，没有可用于验证的已连接设备 |
| Gradle | 9.2.0、9.7.1 已缓存；使用现有 JDK 运行 9.7.1 --version 成功 |
| Android Studio | PATH 及 `/Applications`、`~/Applications` 常用位置未发现；不影响命令行构建 |
| 全局配置 | 未安装软件或 SDK 组件，未修改全局配置或接受新协议 |

vivo S50 Pro mini / Android 16 / OriginOS 6 目前只是用户提供的目标设备信息，尚未通过 ADB 核实。查找文件时部分不可访问目录导致 rg 返回非零；已找到的工具使用绝对路径另行执行验证。

## Phase 0 验证结果（历史记录）

全部命令在项目根目录执行，先用 `source .local/env.sh` 复用现有工具。构建完全离线，没有安装软件、SDK 组件或下载新构建依赖。

| 验证 | 实际结果 |
| --- | --- |
| Wrapper 生成 | 使用已有 Gradle 9.7.1，在临时独立工程执行 `wrapper`，成功 |
| `./gradlew --version` | Gradle 9.7.1 / JDK 17.0.20.1，可运行 |
| `./gradlew --offline --no-daemon :app:assembleDebug :app:lintDebug` | 首次 BUILD SUCCESSFUL，17 秒，44 个任务执行 |
| 首次 Lint | 0 错误 / 3 警告；包含 Android 12+ DataExtractionRules 提示 |
| 补充备份规则后的构建与 Lint | 同一构建任务，附加 `--warning-mode all -Dorg.gradle.deprecation.trace=true`；BUILD SUCCESSFUL，8 秒，24 个任务执行 / 20 个无需重跑 |
| 最终 Lint | 0 错误 / 2 警告：OldTargetApi、GradleDependency，均是 API 36 不是最新版本的提示 |
| `apksigner verify` | 最终 Debug APK 签名验证通过 |
| `aapt2 dump badging` | 最终 APK 包名 dev.musiccam.prototype，minSdk 29 / targetSdk 36，存在启动 Activity，无 uses-permission |
| Wrapper 校验 | JAR SHA-256 与 Gradle 官方 9.7.1 校验值一致；发行包 SHA-256 已固定在 Wrapper 配置 |
| 静态检查 | 源 XML 解析、`sh -n gradlew`、本地环境脚本语法、四份文档存在性通过 |
| Git 检查 | `git diff --check` 通过；另对全部未跟踪文本执行考虑 CRLF 的 `git diff --no-index --check`，通过 |
| 本地配置隔离 | `git check-ignore` 确认 local.properties、.local/env.sh、APK 被忽略 |
| 真机 / 模拟器运行 | 未执行；没有已连接 ADB 设备，未启动模拟器 |
| 音频捕获 / 耳机 / 相机 / 同步 | 未实现、未测试，不作兼容性结论 |

Whitespace 检查初次把官方生成的 Windows Wrapper CRLF 当成尾随空白；改用支持 CRLF 的检查后通过，未改写官方 Wrapper 脚本。未为没有业务逻辑的启动说明页新增形式化单元测试；本阶段验证为真实构建、Lint 和 APK 检查。

产物与报告：

- Debug APK：`app/build/outputs/apk/debug/app-debug.apk`（2,771,932 字节）。
- Lint：`app/build/reports/lint-results-debug.html` 与 `.xml` / `.txt`。
- 最终构建及弃用堆栈：`.local/phase0-build.log`，仅本地保留、不提交。

已知提示：保留 API 36 是本阶段针对 Android 16 验证的明确选择，没有关闭这两项 Lint 检查。Gradle 还有 Project 对象依赖写法的弃用提示；堆栈指向 AGP 的 `VariantDependenciesBuilder.build()` / 测试组件配置，项目自身没有该写法。当前不阻碍构建，升级到 Gradle 10 前需确认 AGP 已修复并重新验证。

## 下一步

1. Phase 1 开始时先确认 MusicCam 调试日志输出与读取路径，不将本轮空 Logcat 作为无异常证明。
2. 实现最小播放音频实验：授权、前台服务、PCM 读取、停止释放、可回放实验文件。
3. 用明确允许捕获的自有/授权测试音源建立基线，再测明确拒绝捕获的对照音源、目标音乐 App 和耳机路由。

真机已经完成 USB 调试授权与安装启动验证。当前无软件安装或全局配置修改请求。

## Phase 1 设备验收清单（尚未执行）

- 记录系统构建版本、应用版本、音源 App/版本、内容类别和音频路由，不公开设备序列号。
- 正常授权、拒绝 RECORD_AUDIO、取消系统授权、反复开始/停止、系统撤权与锁屏后释放。
- 允许捕获音源取得有效 PCM，并回放导出文件；无声/零样本/读取错误单独记录。
- 拒绝捕获音源保持不可捕获；不得尝试绕过或自动切换成麦克风。
- 无耳机、有线耳机（设备支持时）、蓝牙耳机、切换路由的实际表现。
- 不假设第三方 App 的静音原因；先排除会话失效、profile、usage、路由和音源本身静音。

## 发布准备

未确定开源许可证和正式包名，未创建远程仓库，未提交、推送或发布。
