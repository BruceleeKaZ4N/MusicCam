# 架构决策

记录日期：2026-10-08。构建可行性与设备可行性分开验收；尚未实测的方案保持待验证。

## D001：先做音频可行性实验

采用单模块 `app`，Phase 0 只提供最小启动页和构建入口。下一阶段先用可控音源验证播放捕获，再测试第三方 App，最后引入相机。

理由：最主要风险是播放方捕获策略与目标手机行为；提前完成相机/UI 无法解决这些风险。Phase 1 优先可回放 PCM/WAV 实验结果，无需先加入编码封装。

依据：[AudioPlaybackCapture 官方说明](https://developer.android.com/media/platform/av-capture)。

## D002：API 29 起步，目标 API 36

`minSdk = 29`，`compileSdk = targetSdk = 36`。最低版本对应播放捕获 API 的引入；目标版本对应用户的 Android 16 真机，不通过降低目标版本规避系统行为。

较低版本设备支持、OriginOS 行为和 Android 16 权限流程必须另做设备验证，构建声明本身不是兼容性结论。

依据：[播放捕获引入版本](https://developer.android.com/media/platform/av-capture)、[API 与构建工具兼容性](https://developer.android.com/build/releases/about-agp#api-level-support)。

## D003：复用现有工具链，固定构建版本

- Gradle Wrapper：9.7.1，复用本机已经下载的发行包，固定官方 SHA-256。
- Android Gradle Plugin：9.2.1，复用已有缓存。官方要求 Gradle 至少 9.4.1、JDK 17、Build Tools 36.0.0；本机已满足。
- Kotlin：2.4.20。采用 AGP 9 的内置 Kotlin；按照官方方式在根 `buildscript` 固定较高 KGP 版本，以复用本机缓存并适配现有 Gradle。模块不再同时应用 `org.jetbrains.kotlin.android`。
- Java/Kotlin JVM target：17；Android SDK Build Tools：36.0.0；不引入 NDK。
- 使用 Google Maven / Maven Central / Gradle Plugin Portal，禁止动态依赖版本。

选择现有可用工具减少环境安装；版本是否能共同构建以实际构建结果验证。Gradle 自身显示的内嵌 Kotlin 版本与本项目的 Kotlin 编译器版本不是同一概念。

依据：[AGP 9.2 兼容性及 9.2.1 修复](https://developer.android.com/build/releases/agp-9-2-0-release-notes)、[内置 Kotlin](https://developer.android.com/build/migrate-to-built-in-kotlin)、[提高 KGP 版本的官方方法](https://developer.android.com/build/releases/agp-9-0-0-release-notes#upgrade-to-a-higher-kgp-version)、[Kotlin 构建兼容性](https://kotlinlang.org/docs/gradle-configure-project.html)、[Gradle 官方校验值](https://gradle.org/release-checksums/)。

实际验证：Debug APK 和 Lint 离线构建成功。Gradle 9.7.1 报告 AGP 内部测试组件配置使用已弃用的 Project 依赖写法，当前可运行；升级 Gradle 10 前需要检查插件修复情况。没有修改构建检查来隐藏提示。

## D004：最小平台 Activity 与 Views

启动页使用 Kotlin + Android 平台 `Activity` / `TextView`，只显示当前阶段说明，并处理系统栏 inset。暂不引入 Compose、AppCompat、导航、依赖注入、CameraX 或相机 UI。

理由：Phase 0 无复杂交互，平台 UI 足以验证安装启动并保持依赖最少。此决定不锁定未来产品 UI 框架。

## D005：权限随功能引入，先记录正确授权流程

Phase 0 Manifest 不声明音频、相机、前台服务或存储权限。Phase 1 再同时加入 RECORD_AUDIO、用户授权入口、mediaProjection 前台服务及通知、停止回调与清理路径。

预期顺序：用户主动开始 → 运行时权限 → MediaProjection 系统同意 → 从可见页面启动前台服务 → `startForeground()` → `getMediaProjection()` → 注册 callback → 配置 AudioRecord。拒绝、撤权与停止都结束当前会话；不缓存旧 token，不伪造播放方策略。

`RECORD_AUDIO` 是播放捕获的 API 要求，并不是要接入麦克风；本应用自己的 `allowAudioPlaybackCapture` 也不影响音乐 App 是否允许捕获。

依据：[捕获权限和策略](https://developer.android.com/media/platform/av-capture)、[mediaProjection 服务先决条件](https://developer.android.com/about/versions/14/changes/fgs-types-required#media-projection)。

## D006：相机、编码、复用器暂定，组合方式待实验

候选栈为 CameraX、AudioPlaybackCapture/AudioRecord、MediaProjection、MediaCodec、MediaMuxer。CameraX 的角色首先是相机预览与采集；不假设高层 Recorder 支持直接注入任意外部 PCM。

具体相机帧输入、音视频编码、公共时间基准、音轨起点、背压、帧丢弃与最终封装方式，要在 Phase 1 证明音频可行后依据官方 API 和实验确定。本次不创建空接口或未验证的完整流水线。

待查阅：[CameraX 视频捕获](https://developer.android.com/media/camera/camerax/video-capture)、[MediaCodec](https://developer.android.com/reference/android/media/MediaCodec)、[MediaMuxer](https://developer.android.com/reference/android/media/MediaMuxer)。

## D007：开源资料与本地配置分离

临时 namespace/applicationId 为 `dev.musiccam.prototype`，发布前由维护者确定正式标识。SDK 路径使用被 Git 忽略的 `local.properties`，本机环境入口放 `.local/env.sh`，不修改 shell 或系统配置。

初始化本地 Git，不设置远程、不提交或推送。许可证与正式发布签名留待维护者选择；测试录音、密钥和机器缓存不进入仓库。

应用显式关闭云备份并为 Android 12+ 配置排除规则，排除应用私有数据的云备份与设备迁移。Phase 0 不产生媒体；后续实验文件沿用本地保存原则，并独立验证。依据：[Android 备份配置](https://developer.android.com/identity/data/autobackup#control-backup-on-android-12-or-higher)。

## D008：纯音频投影会话使用 mediaProjection 前台服务

查阅日期：2026-10-09；适用 `minSdk 29 / targetSdk 36`，前台服务专用权限和授权顺序重点适用于 API 34+。

用户点击开始后申请 `RECORD_AUDIO`；成功后打开系统 `createScreenCaptureIntent()` 授权界面。仅在本次授权成功后从页面启动服务；服务先以 `FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION` 进入前台，再调用 `getMediaProjection()`、注册 `onStop()`、构建播放捕获 `AudioRecord`。不调用 `createVirtualDisplay()`，不声明相机、屏幕编码或 microphone 服务类型。每次开始都重新请求投影授权；服务使用 `START_NOT_STICKY`，不保存授权 Intent，不自动重启会话。

`POST_NOTIFICATIONS` 仅用于显示前台通知和停止入口；API 33+ 首次申请音频权限时一并询问，拒绝通知不阻止录音。官方说明拒绝后前台服务仍能启动，但通知抽屉可能不展示通知；用户仍可在应用页面停止。音频权限系统文案可能称为麦克风，本应用只通过 `setAudioPlaybackCaptureConfig()` 指定输入，不设置麦克风音源。

依据：[播放捕获](https://developer.android.com/media/platform/av-capture)、[MediaProjectionManager 顺序](https://developer.android.com/reference/android/media/projection/MediaProjectionManager)、[API 34+ mediaProjection 服务要求](https://developer.android.com/about/versions/14/changes/fgs-types-required#media-projection)、[通知权限和前台服务](https://developer.android.com/develop/ui/views/notifications/notification-permission)。构建与设备结果见 STATUS.md；文档要求不等于设备已验证。

## D009：固定 PCM16 立体声，通过实际 API 检查格式并写入 WAV

查阅日期：2026-10-09；适用播放捕获 API 29+，当前 `targetSdk 36`。

Phase 1 固定为 48000 Hz / 16-bit / 2 声道。检查 `getMinBufferSize()` 的错误值、`AudioRecord.state`、实际 sampleRate/channelCount/audioFormat 和 recordingState；不支持时明确报错，不静默改用另一格式。匹配 MEDIA、GAME、UNKNOWN usage，沿用系统与播放器捕获策略，不排除自身 UID，以便捕获自有基线音源。

独立线程用 `short[] / READ_NON_BLOCKING` 读取，显式序列化为 little-endian；避免主线程等待和 `read/stop/release` 并发竞争。停止请求只设置标志，工作线程在非阻塞读取间隔内退出，依次停止/释放 AudioRecord、补齐并同步 WAV 文件、释放 projection 和前台服务。5 秒没有 PCM 返回报错；有返回但全为零时写出 WAV 并明确提示全静音，不能据此宣称成功或判断 DRM。按实际已提交的完整帧写入 RIFF/data 长度，检查 RIFF 32 位长度上限。

文件写入应用私有 `files/recordings/`，先使用 `.wav.part`，正常定稿后才改名为 `.wav`。读取失败时可以保存已成功写入的部分 WAV，并保留错误信息；强制停止/进程终止不能保证执行生命周期回调，因此残留 `.part` 保持未完成标记，启动页面提示，不自动声称它是成功录音。明确退出、任务移除和系统 `onStop()` 请求停止；Home 切换保留前台录音，以便使用其他音源。

依据：[AudioRecord 格式、缓冲区与 read 错误](https://developer.android.com/reference/android/media/AudioRecord)、[播放捕获配置](https://developer.android.com/reference/android/media/AudioPlaybackCaptureConfiguration)、[投影撤回回调](https://developer.android.com/media/platform/av-capture#how_to_handle_a_mediaprojection_token)。纯音频会话的系统撤权/锁屏行为需要在 vivo 上单独观察。

2026-10-09 设备观察（vivo V2527A / API 36 / targetSdk 36）：用户锁屏、解锁后，未创建 virtual display 的纯音频会话继续录制；未收到 onStop，用户手动停止后得到有效 WAV 并正常回放。不能将锁屏等同于这次投影授权已被撤回；[投影文档的锁屏停止说明](https://developer.android.com/media/grow/media-projection#resource-recovery)与本次纯音频观察分别记录，不强行推断原因或保证其他设备一致。实际系统主动撤权的回调仍待用户实测。

## D010：自有合成音建立基线，独立验证捕获与播放

查阅日期：2026-10-09；适用 API 29+，当前 `targetSdk 36`。

应用内提供显式启动的 AudioTrack 测试播放器：48000 Hz PCM16，左声道 440 Hz、右声道 880 Hz，usage=MEDIA。Manifest 明确允许本应用播放被捕获，AudioManager 对本应用设为 ALLOW_CAPTURE_BY_ALL；允许音使用播放器 ALL 策略，拒绝对照使用播放器 NONE 策略。两种音都能正常播放，只有允许音应被本捕获会话录入；不修改其他应用策略。这是对公开播放捕获路径的基线验证，不等于第三方音乐 App 兼容性验证。

输出先用独立 Python 标准库解析 RIFF/WAVE、实际帧数和逐声道非零/峰值/RMS，再由手机 MediaPlayer 回放并由用户确认听感。WAV 文件可解析、非静音、播放器能播放、用户听到正确内容分别记录。测试音与回放在 Activity 销毁时释放，录音归服务管理。

关键事件同时写入 `MusicCamPoC` Logcat 和本应用私有 `phase1-events.log`，不记录授权 Intent/token；私有记录有大小上限。INFO/ERROR 启动探针用于核对包名、PID 和过滤。ERROR 探针明确写明 `intentional_probe_not_failure`，不能误报为应用错误。私有事件记录是补充证据，不替代 Logcat 成功的验收。

依据：[AudioTrack.Builder](https://developer.android.com/reference/android/media/AudioTrack.Builder)、[策略取最严格值](https://developer.android.com/media/platform/av-capture#constraining_capture_by_other_apps)、[MediaPlayer](https://developer.android.com/reference/android/media/MediaPlayer)。实际验证、路由和限制见 STATUS.md。

静态 AudioTrack 在首次写入数据前的有效状态为 `STATE_NO_STATIC_DATA`，应先排除 `STATE_UNINITIALIZED`，完整写入后再确认 `STATE_INITIALIZED`。首轮设备测试暴露了写入前要求 INITIALIZED 的错误，已按官方状态定义修正。[AudioTrack 状态说明](https://developer.android.com/reference/android/media/AudioTrack#STATE_NO_STATIC_DATA)（查阅 2026-10-09；适用本项目 API 29+）。

2026-10-09 设备观察：修正后的两个测试播放器均路由到 `AudioDeviceInfo.TYPE_BLUETOOTH_A2DP`（type=8）。允许音捕获文件中检测到左 440 Hz / 右 880 Hz；拒绝音文件全静音，用户分别确认有声/无声。网易云 9.6.05 日推内容在用户蓝牙耳机测试中也可捕获，但不推广到其他内容或播放器。[路由类型定义](https://developer.android.com/reference/android/media/AudioDeviceInfo#TYPE_BLUETOOTH_A2DP)（查阅 2026-10-09；API 23+，本项目 API 29+）。
