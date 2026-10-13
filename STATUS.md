# MusicCam 当前状态

更新时间：2026-10-13（Asia/Shanghai）。

## Phase 4A-2：一次授权，连续拍摄（核心验收通过，本阶段完成）

2026-10-13最终验收：用户对最新安装版回复“可以了，问题解决了，我觉得这阶段完成了”。按此确认收尾，不再追加测试。一次有效授权内连续独立MP4、串行合成、后台音乐录像、开始/返回黑屏修正已完成；构建/Lint、PCM边界与原生合成对照、实际MP4双轨检查通过。独立音频与独立摄像头本轮回归结果见下；独立摄像头切后台仍按原设计停止。最新片段78d16d12录制/返回及保存完成，用户结束音乐模式后日志MUSIC_MODE_CLOSED→MUSIC_RESOURCES_RELEASED，最终dumpsys本应用服务=nothing / media_projection=null，`.local/phase4a2/start-preview-release-final.json`。

覆盖边界：系统主动MediaProjection.Callback.onStop撤权、最新版本原单段合成入口手动回归、部分权限/磁盘/强杀异常、10/30分钟上限及长期功耗未逐项实测，留作后续覆盖，不把阶段验收写成所有异常/设备均通过。原Phase 3约100ms局部同步偏差及测量歧义不变，无新补偿、声光测试、SDK/Gradle/依赖升级；未Commit/Push。

2026-10-10：维护者明确批准按 D018 实施。起点 HEAD 为 `6aae4a0`（Phase 4A 授权优化已提交），初始 `git status --short` 干净；本轮不 Commit/Push，不覆盖已有改动。完整检查文档与实际授权/服务/控制器/PCM/存储/合成源码；原同步补偿和合成算法未改。

### 实际修改

- MainActivity/CameraActivity/strings 新增独立“音乐拍摄模式”入口、先进入授权再拍片、结束入口与持续采集说明；原独立音频、独立摄像头和单段自动合成入口保留。
- PlaybackCaptureService 一次有效模式持有一个 MediaProjection/AudioRecord；片段命令由音频线程串行执行，开始时先排空间隔缓冲，片段外 PCM 只读不存；模式 UUID 与片段 UUID 回执隔离。注册系统撤权及锁屏处理，主动退出/通知停止/异常结束模式；首版后台结束规则随后按用户最新需求由 D020 修订，禁用新片段，释放 AudioRecord、projection、前台服务，不重用授权。
- 新 MusicPcmSession 逐段独立 WAV/报告；AudioCaptureTiming 按全局 [S,E) 转换局部 BOOTTIME 起点，普通单段 S=0 保持原算法；每段报告冻结。CombinedSessionController 等待本段 WAV 定稿回执，CameraX Finalize 后才关片段音频，再按原算法合成/发布/清理，仅处理该 UUID。
- 串行录制/合成，快速重复开始/停止受状态限制。空闲 3 分钟、单段 10 分钟、音乐模式 30 分钟上限；启动 10 秒、定稿 20 秒/音频最终等待 30 秒超时保护。系统撤权/读取错误给当前段记错并保留；合成失败沿用原文件保护/重试。recoverableId 仍只展示最近一个未完成会话，旧失败数据保留。
- debug MusicPcmCheck 和 DeviceMediaInstrumentation 的 music_pcm 分支验证实际写入器，不新增依赖/权限。SessionStorage、AudioVideoComposer、AudioAlignment、WavFile、Gradle/SDK 未改；后续后台录像只新增相关的主 Manifest camera 前台服务声明；D019 记录实际差异与公开 API 连续性检查局限。

### 构建与自动化

已执行（既有工具/缓存，无 SDK 安装）：

```sh
source .local/env.sh
./gradlew --offline --no-daemon :app:assembleDebug :app:lintDebug
adb devices -l
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am instrument -w -e suite music_pcm dev.musiccam.prototype/.DeviceMediaInstrumentation
python3 tools/check_native_composer.py --output .local/phase4a2/native-checks
adb shell am start -n dev.musiccam.prototype/.MainActivity
git diff --check
```

- 首轮构建 19 秒通过；补充片段检查和保护后构建 16 秒，最后控制器错误定稿路径修正后 15 秒通过。Lint 0 errors / 20 warnings（原 19，加一个相同类型 UseKtx 建议），没有降低 targetSdk、关闭检查或升级工具。
- 已安装 Debug APK。设备为 vivo V2527A / Android16 / API36（USB 已授权；最初未连接，用户重新连接）。新 PCM 检查 passed：A=[4800,14400)、B=[20200,34600)、C=[41400,60600)，不同间隔标记全部丢弃；WAV 大小/内容、帧数、局部首非零位置、BOOTTIME 换算、后段不改写前段报告通过。部分录制错误保留、拒绝覆盖已有 WAV、空段不能冒充成功通过。这里只使用合成输入，不是实际 MediaProjection 验收。
- 原生合成设备/主机对照 passed：aligned/audio_early/audio_late、invalid_timing 拒绝与原文件保护、pending 发布失败清理；三段 H.264/AAC 解码通过，旋转保持，视频逐帧哈希相同，视频 PTS 最大误差 0µs。本次设备合成音 AAC 起声仍晚约 40ms，仅对照观察，未新增补偿，也未追加声光实验。
- 测试媒体/截图/设备证据只在忽略目录 `.local/phase4a2/` 及 `.local/phase4a2-*.log/json`；合成 PCM 测试只删除本轮生成的 synthetic UUID 目录。首轮安装和两套 instrumentation 发起时间重叠，已按顺序复跑两套并确认报告，不采用交叠执行作为唯一依据。

### 实际音乐模式与回归

首版（页面离开即结束）已读取 5 段实际音乐 MP4，时长 2.374/7.231/2.533/2.699/2.833 秒，均 DONE、H.264 + AAC 48kHz 双声道，非零 PCM，ffmpeg 双轨完整解码通过。所有 JSON 帧数 = E-S = composition.sourceAudioFrames，主机按原始锚点重算 BOOTTIME 中位起点一致；后续片段/模式完成后再读取 5 个时间报告，字节未变。

日志证明两个模式各连续两段；一个模式中间 gap 为 254976 帧（5.312 秒），另一个为 70656 帧（1.472 秒），均没有计入下一段 frames。首段报告离散度约 5µs，另一次短片有约 64ms 离散度，其余约 20–35µs；这是系统时间戳估计变化，保持记录、不新增同步补偿。用户反馈“保存成功，蓝牙音乐没中断”，回放/再次授权“没有问题”；初始将进入模式误认为开始录像，说明后已能操作。源码/日志尚未证明同模式连续三段，最终版本继续补 A/B/C。

主动结束和切出页面在首版均记录 MUSIC_MODE_CLOSED；设备 dumpsys 确认本应用捕获服务与投影均不存在。用户反馈切出去选歌必须重新进入模式，进一步明确希望后台也继续视频与内录，本轮将此作为新增需求继续实施，并记 D020。

### 最新范围：有明确通知的后台录像（D020）

- 新 MusicCameraSession 用现有 LifecycleRegistry 实现服务持有的 CameraX 生命周期；仅音乐片段开始时迁移同一 Preview/VideoCapture，开始必须来自可见页面。
- 服务在开始片段前提升为 mediaProjection|camera（camera 类型 API30+），主 Manifest 添加公开 FOREGROUND_SERVICE_CAMERA 与 camera 类型；无新增依赖、运行时权限类别、SDK/Gradle 升级。音乐模式在后台录像继续；预览初版断开/恢复，后按 D021 保留同一页面暂时切后台的连接，销毁才断开。每段 Finalize 后解绑服务相机并降回纯投影类型。
- 通知明确区分音频捕获和摄像头+系统音频录制，含停止本段/结束模式；通知权限或频道不可见则禁止后台保留，后台每秒检查。锁屏、退出、撤权、任务移除和超时仍结束模式；不支持永久常驻、自动后台启动或进程强杀后续录。
- 增加服务等待相机定稿的有界释放步骤（最多20秒）、陈旧通知动作的模式/片段ID检查、返回当前模式入口和服务拥有相机的预览恢复。音频时间戳只允许沿用获取后 1.5 秒内的附近锚点，无有效锚点时保留原带标记 fallback。
- 本轮后台改动构建/Lint 通过（最新15秒，0 errors / 20 warnings）；曾增加一个 API29 的 camera 类型 InlinedApi 警告，补 API30 版本分支后消除。最新 APK 已安装并打开首页，设备 base.apk 与本机当前构建 SHA-256 一致；两套 debug 检查顺序复跑通过，最后 git diff --check 通过。用户已确认后台录像持续，但返回预览黑屏、最终视频有短暂停顿；修正与实际文件核对见下节。锁屏释放随后已观察；系统撤权与本版原独立功能回归仍待实际执行。
- UIAutomator 在实时预览页面不能取得 idle，dump 未成功（“could not get idle state”）；改用应用日志、JSON、截图核对，不把未取得的 XML 当作 UI 成功证据。

当前风险/待验证：公开 AudioRecord API 不提供每次 read 的精确起始帧，连续性保护只检测明显异常，不能证明任意小丢帧均可检测；长时功耗/热量和 10/30 分钟上限尚未跑完整设备测试，3 分钟空闲超时已实际观察；厂商纯音频系统主动撤权必须单独观察。Phase 3 约 100ms 局部同步偏差与周期配对歧义保持，不在本轮修复。

### 2026-10-11：后台片段证据及返回页面的小修正

用户确认切出应用摄像不会被打断，但返回预览黑屏、MP4 有短暂停顿；明确只允许小改动，较大调整后置。已按 D021 核对正式 1.6.2 源码，去掉音乐模式暂时 onPause 的 setSurfaceProvider(null)，同一页面 onResume 避免重复设置，真正 onDestroy 仍断开。仅 CameraActivity/MusicCameraSession 注释及文档变化，没有重写录像/合成/同步。

原后台版实际读取同一模式的三段（另有更早片段，不把它们当新版复测）：

| 段 UUID 前缀 | 视频时长 | 全局 PCM [S,E) / 帧数 | 非零样本 / 结果 |
| --- | --- | --- | --- |
| de2092e3 | 8.4746s | [5961728,6392832) / 431104 | 0；文件有效、音频全静音，不算有声捕获通过 |
| 62a04cfe | 6.3497s | [7886848,8217600) / 330752 | 0；文件有效、音频全静音，不算有声捕获通过 |
| 410f5843 | 13.0775s | [9916416,10566656) / 650240 | 782095；非静音，结束音乐模式时停止并合成成功 |

三段同 musicModeId，无中途重新授权记录；全为 DONE/videoError=0、H.264 + AAC 48kHz立体声，ffmpeg 双轨完整解码通过；帧数=E-S=sourceAudioFrames，按原始全局锚点重算 BOOTTIME 起点一致。两个间隔分别 31.125333s、35.392s，未计入下一段。用户最新反馈明确后台视频持续及返回停顿，未明确各段音乐听感；有声音的单段不能替代三段全有声验收。第三段 stopReason 为“用户结束音乐模式”，不能记成通知停止本段已验证。

另一个模式 ef62c779 短片 8.7513s、[64512,509952) / 445440 帧、全静音但双轨解码通过。日志在其 WAV 定稿后约180.606s 记录“空闲 3 分钟，音乐模式已结束”，随后 MUSIC_RESOURCES_RELEASED；主动结束的三段模式也已记录完整释放。当前 dumpsys services 为 nothing / media_projection 为 null。3分钟空闲释放已观察，不代表10/30分钟或长期功耗验收。

实际命令：`adb -d exec-out run-as dev.musiccam.prototype cat files/phase1-events.log`；按 UUID 读取 session/audio-timing/composition JSON；`adb -d pull /sdcard/Movies/MusicCam/MusicCam-AV-<UUID>.mp4 <本地忽略目录>`；ffprobe -show_streams/-show_format；ffmpeg -v error -xerror 双轨解码；`python3 .local/phase4a2/audit_background.py`。证据仅在 `.local/phase4a2/background/`；首轮审计误要求所有 PCM 非零，遇到静音断言失败；去掉不成立的前提后逐段保留实际静音结果，未把静音改判成功。

小修正版 `./gradlew --offline --no-daemon :app:assembleDebug :app:lintDebug` 18s 成功，0 errors / 20 warnings；`adb -d install -r` Success，首页冷启动 Status ok / 326ms。git diff --check 通过。已请求少量真机复测返回停顿、有实际播放音源的 A/B/C、通知停止和重新授权取消；尚未反馈的项目保持待验证。

小修正版本轮已读取同一模式 A/B/C：5f3fde91（19.3366s，[1495040,2452480)，957440帧，1914809非零样本）、4922dc03（11.1233s，[2650112,3211264)，561152帧，1122263非零样本）、d5311438（5.7649s，[4661248,4963328)，302080帧，全静音）。均 DONE/error=0、H.264/AAC48k立体声，完整双轨解码；BOOTTIME换算和独立区间通过，间隔4.117333s/30.208s未写入下一段。A返回时新建页面（日志有新的 MainActivity/CameraActivity onCreate），preview=replaced；B/C同一页面返回为preview=reused。日志证明小修正生效，不替代画面是否改善和音乐内容的人工观察；C静音不能算三段全有声验收。证据 `.local/phase4a2/preview-fix/`。

补充第四段 a57d1f77：同一模式12.3395s，[5791744,6409216)，617472帧、1234901非零样本；DONE/H.264+AAC、全段双轨解码通过。至此同一模式已确认三份非静音实际输出及一份静音输出，不把静音当有声结果；该额外段证据在 `.local/phase4a2/preview-extra/`，其后 dumpsys services=nothing / media_projection=null。听感、返回画面改善、通知停止本段、系统主动撤权和本版独立入口回归仍等待用户反馈。

另有本轮锁屏操作：STOP_REQUEST“锁屏结束音乐模式”→MUSIC_MODE_CLOSED→MUSIC_RESOURCES_RELEASED（约136ms内）；此为应用锁屏处理，不冒充系统 MediaProjection.Callback.onStop 撤权验收。安装后 APK 签名验证通过，设备已安装 base.apk SHA-256 与构建相同。测试指引曾建议首页测试音再进入音乐页，但该入口 stopPlayback 会主动停止首页测试音，已当场补正为音乐 App 实际播放；未为测试改动原音源生命周期。

### 2026-10-11 用户验收反馈与启动优化（10-13 补齐记录）

- 用户明确“可以，解决了”“改善了”：D021 返回页面时的预览黑屏/MP4停顿已通过人工反馈；页面重建和其他厂商仍不作泛化保证。
- d5311438 静音的实际原因由用户确认“我只是没开音乐”；记录为符合当时无音源情况，不判捕获失败，也不把它算有声捕获成功。
- 通知“停止本段”用户确认能正常停止、日常优先级较低，不追加此交互改造。日志实际 e1c45e80 stopReason=通知栏停止本段，DONE/error=0，27.7515s，[87040,1446912) / 1359872帧，2719636非零样本；H.264/AAC48k立体声、完整双轨解码、区间/时间换算通过。后续退出模式完整释放；证据 `.local/phase4a2/notification-stop/`。
- 独立录音用户确认正常：capture-1791704615070.wav，10.026667s、481280帧、PCM16/48k立体声、949939非零样本；允许测试音 ALL，路由type=8，tools/inspect_wav.py --expect non-silent通过，MediaPlayer回放完成。证据 `.local/phase4a2/regression/audio-inspection.json`。
- 用户反馈独立摄像头切出会中断，属于原 Activity 生命周期的预期（D011），不是音乐模式后台录像回归。两段离开停止的实际文件 MusicCam-1791704650142-455be5a5.mp4 / MusicCam-1791704659604-a27e0288.mp4 分别1.899411s/2.032711s，Finalize error=0、单H.264、零音轨、完整解码通过；未擅自扩展独立录像为后台录像。
- 原单段合成入口本版手动回归、系统主动 MediaProjection.Callback.onStop 撤权仍未取得确切结果；保留待验证，不把应用锁屏处理当系统撤权。取消授权历史音乐模式事件已存在，最新启动优化版本仍需回归。

用户进一步报告点击开始先短暂黑屏，已按 D022 最小调整 CameraActivity/PlaybackCaptureService/MusicCameraSession：音乐页预览即使用已有自定义 owner，开始时服务接管同一个绑定，去掉按钮路径的解绑重绑。空闲页面 onPause 显式销毁自己的 owner，避免后台空闲相机；活动录像仍由服务持有。编码/合成、PCM、时间戳、同步补偿及独立模式行为未改。

`./gradlew --offline --no-daemon :app:assembleDebug :app:lintDebug` 18s成功，Lint 0 errors / 20 warnings，日志 `.local/phase4a2-build-start-preview.log`；git diff --check通过。确认无活动服务后备份设备上一已验证APK至 `.local/phase4a2/preview-return-working.apk`，执行 `adb -d install -r app/build/outputs/apk/debug/app-debug.apk` 返回 INSTALL_FAILED_ABORTED / User rejected permissions。后续 am start -W 的 HOT/ok 只启动旧版本，不算新版本安装/体验验收。已请求用户解锁并允许手机安装确认，未绕过。

2026-10-13继续：工作区保留以上修改，ADB重新检查为vivo V2527A的已授权device。当时启动小修仍等待安装准备回复及真机验证；不因设备连接或日期变化推定用户已允许手机安装，也不重复高成本声光测试。建议只录两小段确认开始无黑屏、同模式连续/后台返回不回归，并检查空闲切后台不继续摄像头。

2026-10-13用户明确“可以安装了”：先检查USB设备已授权、dumpsys本应用服务为nothing，`adb -d install -r app/build/outputs/apk/debug/app-debug.apk` Success。`am start -W` WARM / Status ok / TotalTime41ms / WaitTime43ms；设备base.apk SHA-256与本机构建完全相同，`.local/phase4a2/start-preview-apk-state.json`。D022启动小修现已安装，尚不把安装/启动等同于黑屏消除；已请求仅两段短片确认空闲后台相机释放、开始黑屏、后台返回、同模式连续保存/音乐回放，无声光或通知重复测试。

D022已安装版短片检查：a8315a0a（14.4622s，[119808,831488)，711680帧）和7d49875a（1.2663s，[290816,368640)，77824帧）分别在两个新模式录制，均DONE/videoError=0、H.264+AAC48k立体声、完整双轨解码，均全静音；不能由双轨文件判定有声捕获通过，也不把不同模式两段当同一授权连续两段。两个模式均有MUSIC_MODE_CLOSED/RESOURCES_RELEASED；另有空闲模式锁屏释放记录。

另一个新模式的78d16d12：9.9303s，[163840,652288)，488448帧、976835非零样本；DONE/error=0，双轨完整解码与BOOTTIME换算通过。录制期间有MUSIC_UI_BACKGROUND→FOREGROUND preview=reused，开始记录binding=reused，支持相机交接和后台返回路径已运行；当时开始/返回视觉流畅和音乐听感仍等待人工反馈；随后用户确认问题解决并结束阶段，见顶部最终验收。证据仅在忽略目录start-preview/、start-preview-first/、start-preview-background/。未新增声光实验或修改同步补偿。

## Phase 4A：MediaProjection 授权体验优化（已完成，连续拍摄仅分析）

2026-10-09～10：基于 Phase 3 提交 c559a6a 开始，初始 `git status --short` 为空。完整阅读 AGENTS/PROJECT/DECISIONS/STATUS 和实际授权、服务、控制器、时间记录、存储/合成代码。只修改两个授权请求入口，连续拍摄完成分析后暂停，等待维护者决定 Phase 4A-2；未 Commit/Push。约 100ms 局部同步估计与周期配对歧义保持 Phase 3 记录，本次不追加声光测试。

### 已修改与源码确认

- CameraActivity.continueCombinedStart() 与 MainActivity.requestProjection()：API 34+ 调用 `createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())`，API 29–33 保留无参数调用；日志增加请求 scope。系统授权仍需用户确认，每个新录制会话仍重新授权。没有改变 result callback、授权消费/释放或录制状态机。
- PlaybackCaptureService / CombinedSessionController / AudioCaptureTiming / SessionStorage / AudioVideoComposer / AudioAlignment / CameraX 配置均未修改；没有新增依赖、权限、VirtualDisplay 或 SDK/Gradle 变更。
- DECISIONS.md D017 记录官方行为和 API 范围；D018 记录源码调用链、生命周期耦合、按全局 PCM 帧边界切片/BOOTTIME 换算、独立定稿/合成、撤权/空闲释放、预计文件及风险和验证计划。PROJECT.md 进度更新到 Phase 4A。
- 当前每段停止都会停止/释放 AudioRecord 和 MediaProjection；服务 `started/stopping` 与 UUID/WAV、控制器 `active` 均按单段管理，不能通过复用旧 Intent 实现连续拍摄。建议音乐模式持有一次有效授权与持续 AudioRecord、每段独立 WAV/时间报告、片段间 PCM 读取后丢弃、首版串行合成；属于实质生命周期调整，仅分析、未实施。

### 当前环境与实际验证

复用 `source .local/env.sh`；`android.builder.sdkDownload=false` 保持不变。证据和媒体仅保存在被忽略的 `.local/phase4a/`，不登记设备序列号、账户、曲目和授权 token。

| 验证 | 实际命令与结果 |
| --- | --- |
| 工程与工具 | Git 状态干净；`./gradlew --version`：Gradle 9.7.1 / JDK 17.0.20.1；版本及全局配置未改 |
| 构建/Lint | `./gradlew --offline --no-daemon :app:assembleDebug :app:lintDebug`：BUILD SUCCESSFUL in 17s，46 任务（13 executed / 33 up-to-date）；build.log |
| Lint 报告 | 0 errors / 19 warnings，与 Phase 3 数量一致；没有新增 suppression/baseline。既有 Gradle 10 弃用提示仍在 |
| APK 检查 | 已有 Build Tools 36.0.0 `apksigner verify` 通过；`aapt2 dump badging` 确认 targetSdk 36 和既有权限。APK SHA-256 cce7c339afd4f1a2aa76575a78b7267c1201f1ea007ae7811c3046d3316967dd，apk-sha256.txt |
| ADB | 首次 `adb devices -l` 为唯一 USB `device`；后续核实时短暂 `no devices found`，重新连接后恢复 `device`；没有已授权设备期间未执行真机检查 |
| 设备/音源 | getprop 实测 vivo V2527A / Android 16 / API 36 / PD2527C_A_16.0.19.3.W10；网易云 `dumpsys package`：9.6.05 / 9006005 / targetSdk33。内容与实际路由由本轮人工测试另行确认，不能沿用历史听感 |
| 安装启动 | 无旧活动捕获服务（`dumpsys activity services dev.musiccam.prototype` 为 nothing），`adb -d install -r <Debug APK>` Success，install.log；`am start -W --user 0 -n dev.musiccam.prototype/.MainActivity` COLD / Status ok / TotalTime277ms，launch.txt；截图确认音频页显示 |
| UI 辅助检查 | `uiautomator dump` 未取得 idle state，未生成 XML；不算授权 UI 验证，不操作系统同意按钮。改用只读截图与人工反馈；ui-dump.log |
| 截图尺寸辅助读取 | 主机尝试 Python PIL 读取尺寸因 ModuleNotFoundError 失败；没有安装库，改用现有 `sips -g pixelWidth -g pixelHeight <截图>` 得到1216×2640；不是音视频功能验证 |
| 代码/文档检查 | `git diff --check` 通过；仅两处增加 API34 分支，未修改时间戳/合成逻辑。API29–33 无对应设备，fallback 未做运行验收 |

### 本次真机回归清单（与历史结果分开）

| 项目 | 本阶段结果 |
| --- | --- |
| 授权弹窗默认整个显示屏 | 已观察：当前 APK 合成入口的系统弹窗范围显示「共享整个屏幕」，projection-dialog.png；仍有系统「开始」确认按钮 |
| 是否免手动切换共享范围 | 已观察：范围项灰色，展开显示「共享整个屏幕」及「MusicCam 已停用此选项」；无需先从「共享一个应用」切换，本机未覆盖默认显示屏配置 |
| 网易云可捕获内容/音频数据 | 用户按蓝牙网易云歌曲指引测试后回复「正常」；原 PCM 142,336 帧 / 272,359 非零样本 / 峰值25,296、error=null；不推定所有曲目 |
| 真实摄像头录像 | 后置短片定稿，CameraX error=0，用户反馈正常；本轮未新增前置短片，Phase 3 前置结果仅为历史依据 |
| 停止后 H.264 + AAC MP4 | 4c0f1895 会话 DONE / 两路 finalized=true / 用户停止；独立 ffprobe 单 H.264 High 1920×1080 / 单 AAC-LC 48kHz立体声，ffmpeg 双轨完整解码 exit0，combined/inspection.json、decode.log |
| 输出音乐可正常回放 | 用户针对取消/相机/合成/音乐回放/蓝牙是否中断的指引回复「正常」；另做输出 AAC 解码/非零检查，不以容器通过代替听感 |
| 蓝牙耳机音乐不中断 | 用户按本轮蓝牙网易云指引反馈「正常」；此为本轮人工结果，未读取耳机地址或声称覆盖其他路由 |
| 用户拒绝授权安全退出 | 事件 PHASE3_PROJECTION_DENIED no_capture=true，拒绝后无 PHASE3_BEGIN/FGS；直到下一次 fresh 请求/授权才启动；人工回复正常 |
| 独立音频测试/WAV 回放 | 已通过：新授权、允许测试音 ALL、独立 WAV 非静音预期/完整 MediaPlayer 回放；用户回复「已完成，回放有声正常」。本轮实际测试音路由type=2（扬声器），与合成短片的人工蓝牙结果分开 |

人工确认「倒是没有选择了，可以点‘开始’。点开始后，默认就是共享整个屏幕」，与弹窗截图一致；针对其余合成路径回复「正常」。系统授权与取消均由用户本人完成；应用内入口可通过 ADB 打开，未通过 ADB/无障碍点击系统确认。Phase 3 已通过项仅保留为下方历史记录，本阶段结果以上述当前 APK 实际观察为准。

本轮实际 MP4 为 MusicCam-AV-4c0f1895-79e5-4b0f-a661-716410edb350.mp4：8,740,015 字节，视频78帧 / 2.625833s，AAC124包 / 2.625896s；两轨 start_time=0，完整双轨解码通过。指引建议约10秒，实际仅2.626秒，结论限于此短片，不冒充10秒或长时回归。原 PCM 2.965333s 非静音；最终发布后只留下 session/audio-timing/composition JSON，cleanupRemaining=[]；停止后服务为 nothing。实际命令：`adb -d pull <本轮MP4> .local/phase4a/combined/output.mp4`；`ffprobe -v error -show_streams -show_format -of json <文件>`；`ffmpeg -v error -xerror -i <文件> -map 0:v:0 -map 0:a:0 -f null -`。本阶段没有测同步，时长/双轨回放不是精准同步证据。

输出 AAC 独立解码为 PCM 后有252,086个样本、237,789个非零、峰值25,079、RMS4034.75，combined/decoded-audio-check.json；它证明输出非静音，实际网易云内容正确与蓝牙不中断仍依赖本轮人工确认。

独立音频 capture-1791641277824.wav：1,433,644字节、358,400帧 / 7.466667s / 48kHz / PCM16 / 2声道，左右非零230,016 / 229,632、峰值均5,000，RIFF/data一致，`--expect non-silent` 通过。实际命令：`adb -d exec-out run-as dev.musiccam.prototype cat files/recordings/capture-1791641277824.wav > .local/phase4a/audio/capture-1791641277824.wav`，`python3 tools/inspect_wav.py <文件> --expect non-silent`，报告audio/inspection.json。事件独立入口 `fresh=true scope=default_display` → 用户批准 → FGS/PROJECTION_READY/RECORDING_STARTED；允许测试音在授权前已播放，实际路由type=2；停止测试音后通过「用户退出应用」结束录音，再重新打开回放，WAV_PLAYBACK_STARTED durationMs=7467 / COMPLETED，error=none。本轮停止方式与建议的「停止录音」按钮不同，如实记录，不重测凑操作顺序。最终捕获服务nothing，service-final.txt。拒绝策略基线、前置独立无音轨录像、失败保留对照与同步测量未新增运行，沿用 Phase 3 历史范围，不冒充本阶段复测。

### 下一步与边界

Phase 4A 按本轮弹窗、取消授权、后置短片合成/音乐回放/蓝牙及独立音频结果收尾；最后 `git diff --check` 通过。系统主动撤权、其他 API/厂商、长时漂移与更多异常并非本轮已验证范围；连续拍摄必须待维护者决定 Phase 4A-2 后实施。任何失效 projection、Intent/token 或进程重启都不能跨会话免授权。

## Phase 3：系统音频与摄像头录后自动合成 PoC（最小原型完成，同步有局限）

已实现最小原型；自动构建、实际前后置合成/回放、发布成功/失败清理对照与独立功能回归已通过。镜面测试可见区间得到局部音频滞后约 100ms 的估计，但开头未完整入镜，重复脉冲不能排除整周期配对歧义，绝对同步仍未验证。用户明确「正常，不用再过度检测测了」，本阶段按现有证据收尾，不追加录制或边界测试，不把局部估计写成精准同步通过。基于既有 Phase 2 提交 e45da32 开始，初始 Git 状态干净；无自动 Commit/Push。

### 已完成实现与修改文件

- 新增 CombinedSessionController.kt：统一 UUID 会话状态、两路启动/停止/异常协调、启动/定稿等待、工作线程合成、失败备份/重试；使用应用 Context，页面退出停止采集，定稿合成继续完成。
- 新增 AudioCaptureTiming.kt、AudioAlignment.kt：BOOTTIME 时间记录、AudioRecord framePosition/nanoTime 锚点、起点和离散度；按实际起点差裁剪或补静音，首份零值 PCM 与首次非零样本分开。
- 新增 AudioVideoComposer.kt：原生 MediaCodec AAC-LC 192kbps/48kHz/立体声，MediaExtractor 无损读取 H.264，MediaMuxer 双轨 MP4，保存方向、源 PTS 间隔与视频范围；失败保留原始数据，无 FFmpeg Android 依赖。
- 新增 SessionStorage.kt：私有会话目录、AtomicFile JSON、MediaStore pending 发布与输出检查、安全清理。成功后只清理本轮媒体，JSON 留存；失败不删除原始视频/WAV/.part。
- 修改 CameraActivity.kt/MainActivity.kt/strings.xml：合成入口、一次开始/停止、每轮人工投影授权、重试、声光测试；保留原独立摄像头和音频页面。PlaybackCaptureService.kt 只添加可选 sessionId、时间采样与状态/停止协调，既有独立 PCM/WAV 流程和回放偏好保留。Mp4Inspection.kt 保留零音轨默认检查，增加单 AAC 检查。
- 新增 SyncProbe.kt、tools/measure_sync.py：物理镜面色块 + 1kHz 脉冲及主机事件分析。新增 src/debug 框架 Instrumentation 与 tools/check_native_composer.py：只测试合成文件、无新库、不请求采集授权，不在 Release 暴露测试组件。
- PROJECT/README/DECISIONS（D013–D016）更新范围、操作和官方依据；.gitignore 忽略 Python 缓存。Manifest/main 和 Gradle 依赖、min/targetSdk、SDK 版本不变；没有全局工具安装。

### 自动化实际结果

环境复用 source .local/env.sh。证据仅在忽略目录 .local/phase3；媒体、设备序列号和授权内容不提交。

| 验证 | 实际命令/结果 |
| --- | --- |
| 核心构建 | ./gradlew --offline --no-daemon :app:assembleDebug :app:lintDebug：首次核心 BUILD SUCCESSFUL，18s，build-initial.log |
| UI 首版 | 同一命令 Lint 失败：CameraActivity 条件分支缩进 SuspiciousIndentation；已加明确括号。新 API encoder delay/padding 访问补 API30 检查，没有关闭检查，build-ui.log |
| 修正版 | build-ready.log 16s、build-native-runner.log 15s、build-final-prototype.log 17s 均 BUILD SUCCESSFUL；build-review.log 16s、build-final.log 15s 均成功；最终 Lint 0 errors / 19 warnings（版本提示、UseKtx、中文状态文案），无 suppression/baseline。早先最新 APK 安装被手机拒绝；用户再次要求推送后 adb -d install -r 成功，当前已安装最新 Debug APK |
| 首次安装 | adb -d install -r <Debug APK>：INSTALL_FAILED_ABORTED/User rejected permissions；用户说明误操作后重新推送，Success，没有绕过手机确认 |
| 原生 Shell 尝试 | Debug APK 推到 /data/local/tmp 后 app_process 执行合成测试被设备终止（137/Killed）；没有编码结果，不把它判成测试通过。改用公开 Instrumentation，公开 Instrumentation 已执行 4 组编码/封装测试及 1 组发布/检查失败清理测试，全部通过 |
| 真实首轮合成 | CameraX 与 AudioRecord 均启动、用户停止、两路定稿，PHASE3_COMPOSITION_DONE；发布 H.264 + AAC MP4，并清理本轮所有中间媒体，只剩 session/audio-timing/composition JSON |
| 首轮独立检查 | adb -d pull <最终MP4>；ffprobe -v error -show_streams -show_format -of json；ffmpeg -v error -xerror -i <文件> -map 0:v:0 -map 0:a:0 -f null -：通过，双轨完整解码，报告 first-mp4-inspection.json / first-decode.log |
| 前置镜面文件 | 同样独立拉取、ffprobe 与双轨完整解码通过，mirror-test/decode.log；会话有 sync-probe.json，14 次绿色/暗色周期，记录路由 type=8（Bluetooth A2DP） |
| 第一轮实际同步测量未通过 | python3 tools/measure_sync.py <前置MP4> --output <sync-default.json>；另加 --roi 0.73,0.46,0.23,0.22 重测：均返回 No clear green-flash contrast。镜中屏幕较小且过曝，无法可靠区分色块起点；没有可报告的实际偏移。报告 mirror-test/sync-default.json / sync-screen.json，未调低阈值凑通过 |
| 第二轮镜面测量 | 4e6f7369 会话整段默认/屏幕 ROI 对比检测失败；只裁取前 21s 后检测因脉冲计数不等失败（开头/末尾没有屏幕，部分周期移动遮挡）。最终直接对原始 MP4 使用 --roi 0.35,0.45,0.6,0.35 --interval 9,17：8 对脉冲、4 对稳定样本，局部中位 +101.178ms，范围 +96.689～+105.667ms；sync-visible-original.json。数量/对比判据未修改，绝对周期身份未验证，不宣称完整同步通过 |
| 分析区间验证 | 修改主机工具增加原始 PTS 区间筛选后，用已有 known-aligned.mp4 --interval 1,7：6 对脉冲、3 对稳定样本均 0ms，host-probe-check/interval-check.json；Python 编译和 git diff --check 通过。只改主机分析/文档，Android APK 未变，不重复安装/构建 |
| 最终版独立功能回归 | adb 读取 capture-1791548845587.wav，tools/inspect_wav.py --expect non-silent：13.12s、48kHz/PCM16/2 声道、非零 1,011,225 样本，通过；随后 policy=NONE 的 capture-1791548868839.wav 为 8.213s 全静音，--expect silent 通过。最初把最新 NONE 文件用于 non-silent 检查返回 expectation_met=false，核对事件策略后区分允许/拒绝对照，没有把静音当成功捕获 |
| 最终版独立视频回归 | 拉取 MusicCam-1791549188558-833dfdc7.mp4（后置）/MusicCam-1791549191570-5bfd0cab.mp4（前置），ffprobe 单 H.264/零 audio，双文件完整解码通过；实际 27 帧/0.899733s、19 帧/0.635233s，比请求的 5 秒短，只证明本轮短录像回归。regression/rear.json / front.json / 各 decode.log，停止后服务 nothing |
| 分析器基线 | 用现有主机工具生成已知对齐的 8s 合成声光文件；tools/measure_sync.py 测到 8 对脉冲、5 对稳定样本、0ms 偏移，host-probe-check/sync-report.json；这是分析器基线，不是真机采集同步结果 |
| 原生对照 | python3 tools/check_native_composer.py：aligned / audio_early / audio_late / invalid_timing 四组在 API36 实际 MediaCodec/Extractor/Muxer 通过；源视频/WAV 哈希不变，主机视频帧哈希、PTS（误差 0us）、方向、时长、双轨解码通过，native-checks-run2.log / native-checks-final.log。最新版另跑 native-checks-publication.log：五组通过；发布失败自动删除本轮 pending 媒体项、保留源文件，正常发布/检查通过，并删除仅本测试刚创建的合成媒体（不删除用户视频） |
| 对照首轮未通过 | 主机 audio_early 旋转断言失败：测试视频用旧 rotate tag 未实际带旋转矩阵；输入 ffprobe 也报告无旋转。修正为现有 ffmpeg display_rotation 输入选项并先校验源矩阵，重跑后通过，native-checks-run.log 保留 |
| 对齐算法 | 主机现有 JDK 与项目编译类运行 AudioAlignmentCheck：500 组独立逐帧映射（提前/滞后/无交集/尾部不足）通过，并拒绝空 PCM；alignment-check.log |
| 最终 APK 检查 | apksigner verify 通过；aapt2 dump badging 确认 min29 / target36、既有权限不变。SHA-256 见 apk-sha256-final.txt；用户再次要求推送后手机确认安装成功，MainActivity 冷启动 Status ok / 205ms，停止后捕获服务为 nothing |
| 静态检查 | git diff --check 通过，Python 脚本可编译；无新依赖/SDK 安装 |

真实首轮会话 40eaa719-9f98-41e3-85be-cada4925f584：Movies/MusicCam/MusicCam-AV-40eaa719-9f98-41e3-85be-cada4925f584.mp4，41,086,216 字节；视频 H.264 / 1920×1080 / 442 帧 / 14.755478s / 旋转 -90°（ffprobe）；AAC-LC / 48000Hz / 2 声道 / 692 包 / 14.755500s。两轨起点均为 0，完整解码通过；时长相近不能证明同步。

原 PCM 724,992 帧 / 15.104s / 1,441,612 个非零样本。audioOriginEstimateNs=487482392010416，videoOriginEstimateNs=487482903420407，相差约 511.410ms；裁剪开头 24,548 帧、尾部补 7,820 帧（约 162.917ms），不是根据总时长配平。15 个 AudioTimestamp 锚点起点离散约 64.068ms，视频候选离散约 107.642ms；包含初始化与管线延迟，不是精度/误差上界。实际 AAC 编码器 c2.android.aac.encoder 首 PTS=0，未报告 encoder delay/padding；未应用固定 priming 补偿。

前置镜面会话 324a02e3-22f1-4022-b911-275d7fa146c3：最终 MP4 62,233,665 字节；H.264 / 1920×1080 / 624 帧 / 20.895800s / 旋转 +90°（ffprobe，Android 270°）；单 AAC-LC / 48000Hz / 2 声道 / 980 包 / 20.895792s，双轨从 0 开始且完整解码通过。原 PCM 1,011,712 帧 / 21.08s，按观测起点差 390.651ms 裁剪开头 18,751 帧、尾部补 10,038 帧；这仍只是近似对齐计算，不是实际同步测量。成功后中间媒体已清理，四份会话/时间/合成/声光 JSON 留存。

声光播放约 13.713s，14 次绿色事件均记录蓝牙 A2DP。测试音开始前及停止后的解码音频仍有持续声音（0.5–1.5s RMS 2268.91、19–20s RMS 3042.5；mirror-test/partial-analysis.json）；不能视为纯测试音基线，也不能据此推断麦克风输入。该文件测量失败不影响其轨道与解码通过结论，主观正常播放不能替代同步偏移验收。

第二轮前置镜面会话 4e6f7369-5dd8-4aab-bdcf-d4fe6b2798cc：106,067,237 字节，单 H.264 1920×1080 / 1008 帧 / 33.734100s / +90°，单 AAC-LC 48kHz/2 声道 / 1582 包 / 33.734104s；完整双轨解码通过，中间媒体已清理。stopReason=离开录像页面，两路均定稿且自动合成完成，说明实际离开页停止/保存路径通过；不能推断具体按的是 Home。SyncProbe 约 16.443s、17 次亮/暗周期、蓝牙 type=8；原 PCM 1,631,232 帧，其中非零 312,064 样本，与间歇测试音相符。

可见区间直接用原始文件 9–17s PTS 分析，没有以转码/裁取副本作为最终证据。8 对脉冲中，稳定统计取第一个可见脉冲后至少 2s、区间末尾至少 1s 的 4 对：中位声音晚于绿色 +101.178ms，范围 +96.689～+105.667ms，首末变化 -8.978ms。量化约视频 33.433ms + 音频窗口 10ms，短时变化不能解释成长时漂移。整段首尾色块缺失，每秒重复脉冲没有唯一编号，结果有整周期歧义，是包含播放头/显示/蓝牙/捕获/AAC 的局部估计，未证明绝对音画偏移；未据此硬编码补偿。所有实际失败与原始 PTS 分析报告保留在 mirror-test-2。

### 人工实际结果与尚未验证

目标仍为 vivo V2527A / Android 16 / API36，此前核实系统 PD2527C_A_16.0.19.3.W10；本轮 USB device 与无活动旧音频服务已核实。用户本轮明确音源为网易云歌曲、蓝牙耳机；此前包版本为 9.6.05/9006005，本轮重新核实仍为 9.6.05/9006005/targetSdk33；具体曲目与耳机地址不登记、不推定所有内容兼容。

- 人工通过：针对取消投影、重新授权、后置录制、自动合成、画面/方向/时长/音乐回放及蓝牙连续性的测试提问，用户回复「可以测试完全正常，用的蓝牙耳机听的网易云上面的歌，没问题」。事件也显示 PHASE3_PROJECTION_DENIED no_capture=true，之后新一次请求/授权才启动服务和录像。
- 人工通过：前置镜面合成片段，针对手机回放、画面方向/时长/测试音及音乐是否暂停的追问，用户回复「正常啊，音乐没被打断。」记录为前置主观回放正常及蓝牙音乐未中断；不据此认定音源已暂停或音画偏移合格。
- 人工通过：第二轮明确要求暂停网易云、蓝牙声光测试和手机回放，用户回复「完成了，正常」。针对独立音频与无音轨录像回归回复「正常，不用再过度检测测了」。独立回放主观正常与自动文件结果分别记录；蓝牙路由另有事件依据。
- 收尾：第一轮无有效色块、第二轮整段计数失败及局部区间估计已如实记录。USB 曾只读检查 no devices found，用户完成测试后重新连接 device，已读取本轮文件。安装确认此前被拒绝后已正常重新安装最新 APK，未自动操作确认；没有再安装或打断人工录像。
- 未验证：绝对同步（局部相位估计有整周期歧义）、Home 的具体手势（离开页面双轨定稿已实际观察）、RECORD_AUDIO/CAMERA 拒绝/永久拒绝、系统主动撤权、强杀、磁盘满、实际 provider 故障（合成测试的检查失败清理已通过）、多个失败会话、长时漂移与其他路由/App。
- 原 Phase 1/2 的人工通过保留为历史结果，不能冒充当前新增合成版本的回归结果。

### 已知局限与下一步

1. 高层 Recorder 没有公开绝对采集首帧时刻；Status 起点是包含编码/事件交付延迟的近似。AudioTimestamp 也是系统最佳估计。不能宣布精准同步，声光测试会单独报告方法、偏移、范围和测量分辨率。
2. 保留真实开头静音；音频不足补零会显示全静音警告，但非静音也不自动证明内容/同步正确。native 合成文件的三组已知 PCM 脉冲分别在输入 0.500/0.250/0.750s 之后解码到 0.540/0.290/0.790s（5ms RMS 窗口），观测约 +40ms 起音延迟。8s 声光合成文件另测 8 对脉冲、5 对稳定样本均约 +40ms、短时漂移 0ms，native-checks/synthetic-sync-report.json；这仅测编码/合成环节，不是 CameraX 与系统音频的实际总偏移。没有据此硬编码同步补偿；未报告 AAC priming 与长时漂移仍是局限。
3. 仅接受本设备已观察的 H.264 递增 PTS；HEVC/B 帧/加密来源明确失败并保留数据，不声称多机型支持。
4. 强杀无法保证 WAV/MP4 定稿；未完成数据原样保留，重试需要完整来源/时间记录。私有数据卸载会丢失；失败的可解析视频尽可能另行公开备份。
5. 下一阶段优先给声光事件增加唯一启动标记/非周期编码以消除整周期歧义，再确认采集时基和 AAC priming；之后按需测长时漂移、路由切换和异常释放。本次用户要求停止追加测试，现有局限保留，不扩展产品 UI。未提交或推送 Git。

## Phase 2：CameraX 独立摄像头录像 PoC

状态：Phase 2 八项核心验收已通过，本阶段实现完成；Debug 构建/Lint 与 vivo V2527A / Android 16 安装均成功。用户手动批准 CAMERA，后置取景画面已通过 ADB 截图观察。前后置两段约 10 秒视频及一段 Home 后停止的视频均定稿成功，独立检查为 H.264 / 1920×1080 / 单视频轨 / 零音轨，全帧解码通过。用户确认「前置后置录像正常」「home 返回，录像会被停止，但是正常保存」「音乐不会被打断，摄像并不会影响音乐」「音频实验正常」，并明确补充「两段均已播放，方向和时长正常」。新版 Phase 1 又取得非静音 WAV、完成 MediaPlayer 回放并释放服务。核心结果同时依据设备事件、独立文件检查和用户反馈；权限拒绝等异常路径及更多兼容性尚未逐项完成，不宣称全部设备行为通过。

### 修改文件与本阶段范围

- 新增 `app/src/main/java/dev/musiccam/prototype/CameraActivity.kt`：独立 CameraX PreviewView/Preview/VideoCapture<Recorder> 页面、CAMERA 用户授权、默认后置/前后切换、分开的开始/停止、定稿前防重复、前后台/销毁停止与解绑、录像异常展示、未确认定稿提示、MP4 播放入口。按屏幕高度限制控制区，横屏仍给预览留空间。
- 新增 `app/src/main/java/dev/musiccam/prototype/Mp4Inspection.kt`：后台解析定稿输出，要求一个视频轨、零音轨、有效样本与正时长；显示实际 codec、宽高、旋转与帧率元数据，不把解析成功等同于用户观感确认。
- `MainActivity.kt`：只添加独立录像页入口。`PlaybackCaptureService.kt`、`WavFile.kt`、`PocLog.kt` 无修改，原音频授权/服务/WAV/测试音/回放路径保留。诊断仍用既有日志，相机事件加 PHASE2 前缀。
- `app/build.gradle.kts`：固定 CameraX 1.6.2 的 core/camera2/lifecycle/video/view 与 Activity 1.13.0。`gradle.properties`：启用 AndroidX，SDK 自动下载仍关闭；不改 Wrapper/AGP/Kotlin/SDK 版本。
- `AndroidManifest.xml`：新增 CAMERA、可选 camera features、非导出 CameraActivity；移除 Media3 common 传递附带的 ACCESS_NETWORK_STATE。保留 AndroidX Core 的本应用 signature 动态接收器权限，无 INTERNET/存储权限、无相机前台服务或 microphone 服务。
- `strings.xml`：新增相机页面入口与控件文案；`PROJECT.md`、`README.md`、`DECISIONS.md`：更新本阶段独立录像范围、测试步骤与 D011/D012 官方依据。

录像不调用 withAudioEnabled，不采集麦克风，不读取 WAV、不合成系统音频，不创建相机 MediaProjection，不请求音频焦点或控制音乐音量。输出使用 MediaStore 公共视频集合 Movies/MusicCam，Android 29+ 写入本应用媒体不需要存储权限。默认 SDR；优先 FHD/目标 30fps，按能力和绑定结果尝试默认帧率、HD/SD。没有创建时间戳同步接口；后续外部 PCM 与公共时基另作 PoC。

### 实际命令与构建结果

所有命令在项目根目录，使用 `source .local/env.sh` 复用已有工具。只通过正常 Gradle 构建解析新增项目依赖，没有安装工具/SDK、接受新协议或改全局配置。证据与测试媒体仅存于忽略目录 `.local/phase2/`。

| 验证 | 实际命令 / 结果 |
| --- | --- |
| 初始检查 | 已阅读 AGENTS/PROJECT/DECISIONS/STATUS、构建/Manifest 与全部现有 Kotlin 源码；`git status --short` 为空，基于既有 Phase 1 提交 3e087ed 工作，无覆盖用户改动 |
| 工具 | `./gradlew --version` 成功；Gradle 9.7.1 / JDK 17，gradle-version.log |
| 首次离线构建 | `./gradlew --offline --no-daemon :app:assembleDebug :app:lintDebug` 失败：CameraX 1.6.2 和部分 Activity 传递依赖没有缓存；不是源码/SDK 错误，build-offline-initial.log |
| 正常依赖解析 | `./gradlew --no-daemon :app:assembleDebug :app:lintDebug`：BUILD SUCCESSFUL，3m55s，46 任务（28 执行 / 18 up-to-date），build-online.log；构建使用已有 API 36/Build Tools 36.0.0，未触发 SDK 安装 |
| 移除传递权限后 | 同一离线命令成功，10s，build-offline-final.log |
| 横屏布局调整后 | 同一离线命令成功，14s，build-offline-final2.log |
| 最后简化画质状态后 | 同一离线命令成功，13s，46 任务（13 执行 / 33 up-to-date），build-offline-final3.log |
| Lint | 0 errors / 15 warnings：SDK/Gradle 更新提示 3 个、UseKtx 3 个（含原音频服务）、SetTextI18n 9 个。未添加 suppression/baseline，未降低 targetSdk/关闭检查；本 PoC 暂为中文状态文案 |
| 构建提示 | 既有 AGP/Gradle 弃用提示；CameraX 的两个 JNI 库未 strip，原样打包，未安装 NDK；不影响本次构建通过 |
| APK | `apksigner verify` 成功；`aapt2 dump badging` 与合并 Manifest 确認 minSdk 29 / targetSdk 36，新 CAMERA 和既有四项权限、Core signature 权限；无网络/存储权限。apk-badging-final.txt |
| 最终安装/冷启动 | `adb -d install -r app/build/outputs/apk/debug/app-debug.apk`：Success；`adb -d shell am start -W --user 0 -n dev.musiccam.prototype/.MainActivity`：Status ok / COLD / 308ms（Wait 313ms），install-final3.log / launch-final3.txt；覆盖安装保留原 WAV 与权限，不卸载 |
| APK 标识 | Debug 0.0.1 / versionCode 1；SHA-256 d6d20ed10ea0492fc30f471b233b5624b739998ed9e6e5c2b77d80276b8e702f，apk-sha256.txt |
| 诊断 | `adb -d logcat -d -b main -b system -b crash -v threadtime -s 'MusicCamPoC:V'` 与 `adb -d exec-out run-as dev.musiccam.prototype cat files/phase1-events.log` 均成功，logcat-app.txt / session-events.log。PHASE1 ERROR 探针明确是 intentional_probe_not_failure |
| 代码检查 | `git diff --check` 已通过；原音频服务/WAV/日志文件 diff 为空。本阶段没有 Commit/Push/发布 |
| 独立视频检查 | `adb -d pull /sdcard/Movies/MusicCam .local/phase2/videos`：3 文件，共 63,115,315 字节。逐文件执行 `ffprobe -v error -count_frames -show_streams -show_format -of json <本地文件>` 与 `ffmpeg -v error -xerror -i <本地文件> -map 0:v:0 -f null -`：均通过，零音轨、有效帧数和正时长；报告 video-inspection.json / 各文件 ffprobe.json、decode.log |
| 本版音频文件 | `adb -d exec-out run-as dev.musiccam.prototype cat files/recordings/capture-1791481753827.wav > .local/phase2/recordings/capture-1791481753827.wav`；`python3 tools/inspect_wav.py <本地文件> --expect non-silent`：通过，wav-regression.json |
| 停止后服务 | `adb -d shell dumpsys activity services dev.musiccam.prototype`：nothing，无活动捕获服务，services-after-tests.txt |

### 设备观察与待完成验收

`adb devices -l`：唯一 USB 已授权 device（序列号仅在本机）；本轮 `getprop` 重新核实 V2527A / Android 16 / API 36 / PD2527C_A_16.0.19.3.W10。MusicCam minSdk 29 / targetSdk 36。

本轮重新用 `adb -d shell dumpsys package com.netease.cloudmusic` 读取到已安装网易云音乐 9.6.05 / versionCode 9006005 / targetSdk 33，与 Phase 1 一致；用户这次没有单独重述音乐 App 或内容，不能仅凭安装包信息确定本轮所有蓝牙音乐的具体音源。用户确认的本次蓝牙听感和测试音 AudioTrack 的 type=8（A2DP）分别记录，不记录耳机地址/序列号/私人曲名，不推广兼容性结论。

| 验收项 | 本轮实际结果 |
| --- | --- |
| 摄像头授权/预览 | PHASE2_CAMERA_PERMISSION granted=true；用户手动授权，没有使用 ADB grant。后置真实画面已在截图中可见，CameraActivity 前台，绑定 FHD、目标 [30,30]；初版状态显示过长调试信息已修正为友好的 1080p/帧率说明 |
| 后置录像 | 本版 FHD 录像开始、用户停止、Finalize error=0，10.543433 秒文件通过独立轨道与解码检查；用户确认后置录像正常 |
| 前置预览/录像 | 本版切前置绑定 FHD / 目标 30fps，录像开始、用户停止、Finalize error=0；10.431200 秒文件通过独立轨道与解码检查，用户确认前置录像正常 |
| MP4 播放/时长/方向 | 三段文件有有效时长与旋转矩阵，全帧解码通过；用户明确确认前后两段均已在手机播放，画面方向、约 10 秒时长正常 |
| 无麦克风音轨 | 三段 MP4 均由手机 MediaExtractor 与主机 ffprobe 独立确认零 audio 轨；并非静音音轨，而是没有音轨 |
| Home/锁屏/返回/重建 | 第三段记录 STOP_REQUEST reason=离开录像页面 → Finalize error=0，保存 1.299622 秒 MP4；其后恢复预览但无自动新录像事件。用户确认录像中按 Home 会停止并正常保存；锁屏/旋转/任务划除未逐项验收 |
| Phase 1 回归 | 新授权 → mediaProjection FGS → 48000Hz/PCM16/2 声道 → 非静音 WAV → MediaPlayer STARTED/COMPLETED → 无活动服务；用户反馈音频实验正常。允许测试音播放/蓝牙 A2DP 路由日志存在；音源可能同时包含音乐，不宣称纯测试音频率基线 |
| 蓝牙音乐连续性 | 针对蓝牙听歌时预览及前后录像的提问，用户确认「音乐不会被打断，摄像并不会影响音乐」。记录为本次蓝牙音乐未受摄像影响，不扩展到全部 App/路由；视频播放操作的音频焦点行为另计 |

### 独立文件结果（仅本地媒体，不提交）

| 文件 / 来源 | 时长 / 大小 | 视频 / 帧数 / 平均帧率 | 音轨与解码 |
| --- | --- | --- | --- |
| MusicCam-1791481650604-c2df5f7c.mp4 / 后置、用户停止 | 10.543433 秒 / 27,057,085 字节 | H.264，1920×1080，316 帧，约 29.97fps；旋转矩阵 -90°（ffprobe 约定） | 1 video / 0 audio，完整解码无报错 |
| MusicCam-1791481667273-acea66b4.mp4 / 前置、用户停止 | 10.431200 秒 / 31,538,232 字节 | H.264，1920×1080，312 帧，约 29.91fps；旋转矩阵 +90° | 1 video / 0 audio，完整解码无报错 |
| MusicCam-1791481686705-3c24bd7c.mp4 / 后置、离开页面 | 1.299622 秒 / 4,519,998 字节 | H.264，1920×1080，39 帧，约 30.01fps；旋转矩阵 -90° | 1 video / 0 audio，完整解码无报错 |

手机容器检查分别报告 10.543 / 10.431 / 1.300 秒、video/avc、音轨 0。平均帧率接近目标 30，仍未分析帧间抖动、长时录制或降级机型；观看方向正常来自用户实际播放确认，旋转矩阵仅是补充文件证据。

本版 capture-1791481753827.wav：20.309333 秒 / 3,899,436 字节（PCM 3,899,392），48000 Hz / PCM16 / 2 声道，974,848 帧；左右非零样本 807,865 / 809,817，峰值 30,911 / 29,449，RMS 3616.25 / 3425.42。RIFF/data 与实际数据一致，非静音预期通过。事件包含新的投影授权、允许测试音 ALL、type=8（蓝牙 A2DP）、用户停止、完整 WAV 回放 20,309ms；用户反馈音频实验正常。具体混合音源未单独确认。原 Phase 1 允许/拒绝基线结果保留，但本版拒绝策略对照未重新测试。

### 已知问题与下一步

1. 八项核心验收完成：真实预览、后置录像、前置录像、手机 MP4 播放、画面/时长/方向、零音轨、Phase 1 回归、蓝牙音乐未受摄像影响；Home 自动停止并正常保存也通过。异常路径、长时录制和其他音源/路由仍需独立验证。
2. 默认码率/codec 与实际帧率由 CameraX/设备决定；30fps 请求、AE 范围与元数据均不能保证每帧均匀间隔。降级路径、相机占用/隐私开关、永久拒绝/撤权、磁盘满和强杀仍需单独设备测试。
3. 录像仅限可见 Activity；离开即停，没有后台相机服务。进程强杀不能保证 Finalize 或定稿，未确认会话显示警告；不自动恢复或当作成功。异常可解析的部分 MP4 保留并显示错误码。
4. 下一阶段建议先补关键权限/异常与长时录像，再验证视频编码输入、播放 PCM 编码及公共时基；先验证同步可行性，再确定 MediaCodec/MediaMuxer 或其他公开 API 组合。本阶段不假设 Recorder 可直接接收外部 PCM，不复用现有 WAV 来模拟实时同步。
5. 真机录像/截图可能包含私人场景，均只保存在 `.local/phase2/`，不进入 Git。未自行提交、推送或发布。

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
