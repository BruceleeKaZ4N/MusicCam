# MusicCam 当前状态

更新时间：2026-10-09（Asia/Shanghai）。

## Phase 1：系统音频捕获 PoC

状态：最小播放捕获、授权、前台服务、WAV 保存与回放已实现；Debug 构建、Lint、安装、启动和应用 Logcat 已通过。vivo 真机上的网易云日推歌曲经蓝牙耳机播放，用户确认 WAV 回放有声，独立文件检查确认非静音 PCM。自有测试音首版状态检查有误，已修复；修正版允许/拒绝音源对照均通过文件检测和用户回放确认，音频捕获核心 PoC 已通过。锁屏/解锁实测继续录音，回放正常；本机纯音频锁屏没有触发撤权回调。系统主动撤权及其他异常路径仍待用户实测，尚不能宣称全部通过。

### 已实现与实际修改文件

- `app/src/main/java/dev/musiccam/prototype/MainActivity.kt`：开始/停止、运行时权限、每次 MediaProjection 授权、状态/错误、允许与拒绝测试音、最近 WAV 回放、明确退出；配置重建保留待授权状态，重复开始/停止有状态保护。
- `app/src/main/java/dev/musiccam/prototype/PlaybackCaptureService.kt`（新增）：mediaProjection 前台服务、通知停止入口、撤权回调、独立非阻塞 PCM 读取线程、权限复查、格式与读取错误检查、停止与释放、结果保存；START_NOT_STICKY，不复用或保存授权 Intent。
- `app/src/main/java/dev/musiccam/prototype/WavFile.kt`（新增）：完整帧 PCM16 little-endian、44 字节标准 WAV 头、实际 RIFF/data 长度、文件同步、`.part` 定稿、零数据清理和 RIFF 上限检查。
- `app/src/main/java/dev/musiccam/prototype/PocLog.kt`（新增）：明确启动探针、Logcat 与私有会话事件补充记录，不记录授权内容。
- `app/src/main/AndroidManifest.xml`、`app/src/main/res/values/strings.xml`：仅添加 RECORD_AUDIO、前台服务及 mediaProjection 专用权限、通知权限、服务声明和实验页面文案。本应用 allowAudioPlaybackCapture 仅作用于自有测试音与回放。
- `tools/inspect_wav.py`（新增）：Python 标准库独立解析 RIFF/WAVE、实际 PCM 长度、逐声道峰值/非零/RMS，支持非静音和静音预期检查。
- `README.md`、`PROJECT.md`、`DECISIONS.md`、`STATUS.md`：更新使用方法、当前范围、官方依据（D008–D010）与本次结果。

没有改动 Gradle/AGP/Kotlin/SDK 版本或关闭 Lint；没有安装工具/SDK、修改全局配置、采集麦克风/相机、创建虚拟显示、修改其他 App、提交或推送 Git。

### 设备与兼容性记录

| 项目 | 实际结果与来源 |
| --- | --- |
| 设备 | ADB 唯一 USB `device`；vivo V2527A，Android 16 / API 36；序列号不写入本文档 |
| 系统构建 | 沿用 Phase 0.5 的 PD2527C_A_16.0.19.3.W10；本轮重新核实型号/Android/API，未重复核实系统构建属性 |
| MusicCam | dev.musiccam.prototype，Debug 0.0.1 / versionCode 1，minSdk 29 / targetSdk 36 |
| 第三方音源 | com.netease.cloudmusic，版本 9.6.05 / versionCode 9006005，targetSdk 33（dumpsys package 实测） |
| 内容 | 用户描述“随便点的日推”，在线播放；是否 VIP、DRM 或特定内容策略未确认，不记录曲名/账户 |
| 音频路由 | 用户确认听歌及回放使用蓝牙耳机；自有允许/拒绝测试音均记录 TEST_TONE_ROUTE type=8，即蓝牙 A2DP（官方 AudioDeviceInfo 定义）；未记录耳机型号/地址，不将此结论扩展到其他路由 |
| 结论范围 | 这次设备/系统/网易云版本/日推内容/蓝牙路由下，已有可回放非静音 WAV；不代表所有网易云歌曲或其他音乐 App 均可捕获 |

### 构建、安装与日志诊断

命令均从项目根目录 `source .local/env.sh` 复用已有工具，Gradle 完全离线，`android.builder.sdkDownload=false` 保持开启。`adb -d` 选择唯一 USB 设备；所有录音和完整证据保存在 Git 忽略的 `.local/phase1/`。

| 验证 | 实际命令与结果 |
| --- | --- |
| Wrapper | `./gradlew --version`：成功，固定 Gradle 9.7.1；输出 `.local/phase1/gradle-version.log` |
| 首次完整构建/Lint | `./gradlew --offline --no-daemon :app:assembleDebug :app:lintDebug`：APK 编译成功，Lint 失败，1 个 MissingPermission 错误；工作线程创建 AudioRecord 处需要直接检查 RECORD_AUDIO。已就地复查权限，无抑制或 baseline；初次失败保存在 build-lint.log |
| 修正后与最终构建 | 同一命令分别成功；最终 build-lint-final2.log：BUILD SUCCESSFUL，11 秒，44 个任务，13 执行 / 31 up-to-date；Lint 0 错误 / 2 原有 SDK 更新提示；AGP/Gradle 弃用提示仍存在 |
| 安装 | `adb -d install -r app/build/outputs/apk/debug/app-debug.apk`：Success；初版及修正版均成功，最终 install-final.log |
| 修正版冷启动 | `adb -d shell am start -W --user 0 -n dev.musiccam.prototype/.MainActivity`：Status ok / COLD / TotalTime 334 ms / WaitTime 336 ms，launch-final.txt |
| APK 检查 | 现有 SDK 36.0.0 `apksigner verify` 成功；`aapt2 dump badging` 确认包名/targetSdk 36/启动 Activity 与四项权限，无相机/网络/存储权限 |
| 最终 APK 标识 | SHA-256：26f39c3f595d7315528de51d954366a8889e8e231fdac3b97ca920e03a3ebc86；应用仍为 Debug 0.0.1 / versionCode 1，使用哈希区分本阶段构建 |
| 页面检查 | ADB 截图确认开始/停止、测试音、回放和退出入口正常显示（launch-screen.png）；页面显示不等于音频捕获成功 |
| Logcat 首轮 | INFO 探针无结果；加入明确标记的 ERROR 探针，按真实包名/PID 和 `*:V`、all/main/system/crash 缓冲区复查，起初仍无本应用消息，只能读到同 PID Activity events。不能据此判断无异常 |
| Logcat 复查 | 最小 PoC 安装后，`adb -d logcat -d -b all --pid=<本次实际PID> -v threadtime '*:V'` 已读到 INFO `PHASE1_LOG_PROBE` 与 ERROR `PHASE1_LOG_PROBE_ERROR intentional_probe_not_failure`；随后按 MusicCamPoC 标签读取会话事件成功。早先空日志的原因未查实，没有修改手机日志属性/全局配置 |
| 补充事件 | `adb -d exec-out run-as dev.musiccam.prototype cat files/phase1-events.log`：成功，可对照权限、会话顺序、录制、停止和回放。它是补充证据，不把读取私有文件当作 Logcat 验收 |
| WAV 写入器验证 | 用现有 JDK 和项目编译出的 WavFile 类运行 `.local/phase1/WavFileCheck.java`：多次写入、长度、空录音移除、拒绝不完整帧通过；独立 Python wave/struct 解码确认负数、字节序、帧数和 RIFF 大小。wav-unit.log；这是主机验证，不是真机捕获 |
| 代码检查 | `git diff --check` 通过；Python 脚本可编译，录音路径通过 git check-ignore 确认为忽略 |

### 已观察的真机路径

- 用户主动点击开始，RECORD_AUDIO 与通知权限已由用户允许。事件记录为 RECORD_AUDIO_GRANTED；每轮均记录新的 PROJECTION_CONSENT_REQUEST / GRANTED。
- 真机事件顺序为 FGS_STARTED → PROJECTION_READY（已注册 callback；无 virtual display）→ FORMAT → RECORDING_STARTED。AudioRecord 实测 48000 Hz / 2 声道 / PCM16；getMinBufferSize=7680，使用 buffer=19200 字节。
- 捕获期间可切换到网易云播放，回到应用停止并保存 WAV；用户确认第一次网易云录音回放能听见歌曲。该听感来自用户反馈，PCM 数值来自独立文件检测。
- 观察到一轮 PROJECTION_CONSENT_DENIED，未在该拒绝与下一次重新请求之间启动录音服务；随后重新授权成功，未复用旧结果。
- 多轮独立开始/停止均产生结果；停止后 `dumpsys activity services dev.musiccam.prototype` 为 nothing；`dumpsys media_projection` 只保留无活动投影的布尔摘要，避免保存 token。
- 第三份 WAV 的 MediaPlayer 记录 WAV_PLAYBACK_STARTED / COMPLETED，实际 durationMs=11925，无该轮播放器错误记录；回放听感只对用户已确认的网易云测试下结论。
- 修正版自有音源对照分别使用新的授权会话，AudioTrack usage=MEDIA，允许音 policy=ALL、拒绝音 policy=NONE；路由记录均为 type=8（蓝牙 A2DP）。两个 WAV 都已完整执行 MediaPlayer 回放，用户明确确认“允许捕获的测试音回放能听到，拒绝捕获的对照音回放听不到”。独立 PCM 检测分别为非静音/全静音，允许音频率还确认左 440 Hz / 右 880 Hz。系统/播放方策略未被绕过。
- 锁屏再解锁：用户确认“不影响录制音频和回放音频”。对应事件未出现 PROJECTION_REVOKED/onStop，最终为 STOP_REQUEST reason=用户停止；输出 16.341333 秒的非静音 WAV，MediaPlayer 回放完成。此为本机纯音频会话的实际行为，不能把锁屏当作已撤权或把它记成撤权回调测试通过。

### WAV 文件检测结果

实际执行：`adb -d exec-out run-as dev.musiccam.prototype cat files/recordings/<文件名> > .local/phase1/recordings/<文件名>`；`python3 tools/inspect_wav.py .local/phase1/recordings/<文件名>`。首份另用已有 `ffprobe -v error -show_entries stream=codec_name,sample_rate,channels,bits_per_sample,duration -show_entries format=duration,size -of json <文件>` 独立交叉验证，确认为 pcm_s16le / 48000 / 2 / 16，时长与大小一致。

| 文件（仅本地，不提交） | 时长 / 大小 | PCM 数据 | 结果 |
| --- | --- | --- | --- |
| capture-1791478790303.wav | 142.186667 秒 / 27,299,884 字节 | PCM 27,299,840 字节；6,824,960 帧；非零样本 L/R 4,169,456 / 4,169,436；峰值均 32768；RMS 7024.21 / 6910.25 | RIFF/data 与实际帧数一致，非静音。用户确认网易云日推/蓝牙耳机测试回放有声 |
| capture-1791479096519.wav | 25.28 秒 / 4,853,804 字节 | PCM 4,853,760 字节；1,213,440 帧；两个声道非零/峰值/RMS 均为 0 | 格式有效但全静音，不能判定捕获成功。事件显示测试播放器初始化检查报错，音源未正常启动；不能归因于 DRM 或网易云策略 |
| capture-1791479306513.wav | 11.925333 秒 / 2,289,708 字节 | PCM 2,289,664 字节；572,416 帧；非零样本每声道 433,134；峰值 32018 / 32406；RMS 8748.81 / 8214.44 | 独立检查非静音，MediaPlayer 完整回放。此轮具体音源未单独确认，不新增兼容性推断 |
| capture-1791479536372.wav（允许基线） | 12.928 秒 / 2,482,220 字节 | PCM 2,482,176 字节；620,544 帧；非零样本 L/R 453,898 / 453,141；峰值均 5000；RMS 3025.87 / 3025.88 | `--expect non-silent` 通过；独立正弦频率分析确认左 440 Hz / 右 880 Hz，与测试源吻合；MediaPlayer 回放完成，用户确认有声 |
| capture-1791479576243.wav（拒绝对照） | 12.138667 秒 / 2,330,668 字节 | PCM 2,330,624 字节；582,656 帧；两个声道非零/峰值/RMS 均为 0 | `--expect silent` 通过；NONE 对照音实际播放、蓝牙路由就绪；MediaPlayer 回放完成，用户确认无声。该可控对照的静音符合拒绝策略预期，不作为普通捕获成功的证据 |
| capture-1791479800318.wav（锁屏/解锁） | 16.341333 秒 / 3,137,580 字节 | PCM 3,137,536 字节；784,384 帧；非零样本 L/R 708,449 / 707,266；峰值均 5000；RMS 3362.43 / 3362.39 | `--expect non-silent` 通过；允许测试音，蓝牙 A2DP 路由，手动停止；完整回放，用户确认锁屏/解锁不影响录制和回放 |

以上文件均为 48000 Hz / 16-bit / 立体声。首份峰值达到 PCM16 满幅，未进行音质、削顶、丢帧或长时同步分析。

基线检测命令：`python3 tools/inspect_wav.py .local/phase1/recordings/capture-1791479536372.wav --expect non-silent`；拒绝对照为同一命令使用 capture-1791479576243.wav 与 `--expect silent`。报告在 wav-inspection-allowed.json / wav-inspection-denied.json；另用 Python 标准库 wave/array/math 对允许文件有效音段的 1 秒数据做双频率相关分析，结果在 baseline-frequency.json，目标频率幅度约 4999，对侧频率幅度小于 0.001。

### 已修正问题与待用户实测

1. 首版自有 AudioTrack 使用 MODE_STATIC，在首次 write 前应处于 STATE_NO_STATIC_DATA，却被错误要求为 INITIALIZED，导致“测试播放器初始化失败”。已按官方状态定义修正为先排除 UNINITIALIZED，完整写入后再验证 INITIALIZED。该错误与网易云捕获路径独立；保留失败测试记录，修正版重新构建与安装成功，允许/拒绝测试音均已在设备上通过。
2. 保存结果会清除先前测试播放器错误提示，避免旧错误遮盖本轮录音结果；停止原因先写入再发布停止标志，避免工作线程读取旧原因。
3. RECORD_AUDIO 拒绝/永久拒绝、通知权限拒绝、录音中系统主动撤权、通知栏停止、重复快速点击、任务划除/明确退出、录制期间权限撤回、磁盘满/读取失败、不支持采样格式：代码已有保护，仍待逐项用户实测；不得以编译通过代替这些验收。系统投影停止入口的操作未完成确认，改做锁屏测试后发现纯音频会话继续录音；因此自动停止回调的设备验收仍待用户实测，不把它判作已通过。
4. 扬声器、有线耳机、切换路由、其他曲目/播放器、跨 profile、其他 Android 版本尚未验证；蓝牙结果仅限本次。
5. 强制停止/进程被系统杀死不能保证回调和定稿；残留 .part 在页面明确标记未完成，不自动恢复或视为成功录音。保存到应用私有目录，卸载将删除；公开导出/分享暂未实现。
6. Logcat 初期空结果原因未确认，但本轮后续已取得本应用探针与会话日志。调试 ERROR 探针不是异常；保留私有事件作为补充诊断。

### 下一步

可控允许/拒绝音源对照与锁屏/解锁观察已完成。接着完成系统主动撤权/退出及权限拒绝测试，再按 App 版本、内容与路由扩大兼容性记录。音频 PoC 通过不等于相机/编码/同步通过，下一阶段另行验证真实相机及时间戳，本轮未开发相机。

本阶段证据位于 `.local/phase1/`，录音与截图不进入 Git；代码尚未提交，没有推送/发布或新工具安装请求。

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

## Phase 0.5 下一步（历史记录）

1. Phase 1 开始时先确认 MusicCam 调试日志输出与读取路径，不将本轮空 Logcat 作为无异常证明。
2. 实现最小播放音频实验：授权、前台服务、PCM 读取、停止释放、可回放实验文件。
3. 用明确允许捕获的自有/授权测试音源建立基线，再测明确拒绝捕获的对照音源、目标音乐 App 和耳机路由。

真机已经完成 USB 调试授权与安装启动验证。当前无软件安装或全局配置修改请求。

## Phase 1 设备验收清单（Phase 0.5 时的计划；当前结果见上文）

- 记录系统构建版本、应用版本、音源 App/版本、内容类别和音频路由，不公开设备序列号。
- 正常授权、拒绝 RECORD_AUDIO、取消系统授权、反复开始/停止、系统撤权与锁屏后释放。
- 允许捕获音源取得有效 PCM，并回放导出文件；无声/零样本/读取错误单独记录。
- 拒绝捕获音源保持不可捕获；不得尝试绕过或自动切换成麦克风。
- 无耳机、有线耳机（设备支持时）、蓝牙耳机、切换路由的实际表现。
- 不假设第三方 App 的静音原因；先排除会话失效、profile、usage、路由和音源本身静音。

## 发布准备

未确定开源许可证和正式包名，未创建远程仓库，未提交、推送或发布。
