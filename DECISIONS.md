# 架构决策

记录日期：2026-10-08。构建可行性与设备可行性分开验收；尚未实测的方案保持待验证。

## D013：统一录制会话，保留两路成熟采集实现

查阅日期：2026-10-09；适用 minSdk 29 / compileSdk、targetSdk 36，CameraX 1.6.2。

合成入口复用 CameraActivity 的预览与 Recorder，视频仍禁用 withAudioEnabled。CombinedSessionController 持有应用级 Context，协调 PREPARING → RECORDING → STOPPING → MERGING → DONE/FAILED，不持有 Activity；页面只订阅状态，销毁时解绑。单次开始先取得 CAMERA/RECORD_AUDIO 和本轮显式 MediaProjection 授权，再启动原 PlaybackCaptureService 与 Recorder；两路实际启动确认后才显示 RECORDING。没有 AudioFocus 请求、音量修改、麦克风输入或虚拟显示。原独立入口和 WAV 回放偏好保持；服务只增加可选会话 UUID、时间记录与停止状态，不重构原 PCM 读取循环。

离开相机页、错误、某一路结束均驱动两路停止，等待视频 Finalize 和音频 WAV/时间记录定稿后离线合成。配置重建不续拍；合成线程使用应用 Context，可继续完成。每次捕获重新授权，Intent 只在内存短暂持有、移交服务后移除，不放入 Bundle/JSON/偏好；恢复合成只使用已完成文件，不重新用投影 token。启动/停止超时显示失败并保留数据。进程被强杀不保证定稿，不能宣称后台持久任务或自动恢复未完成采集。

依据：[CameraX 视频捕获](https://developer.android.com/media/camera/camerax/video-capture)、[AudioPlaybackCapture](https://developer.android.com/media/platform/av-capture)、[MediaProjection 会话与释放](https://developer.android.com/media/grow/media-projection)、[前台服务类型与授权顺序](https://developer.android.com/about/versions/14/changes/fgs-types-required#media-projection)。

## D014：BOOTTIME 时间记录与可验证的近似对齐

查阅日期：2026-10-09；适用 API 29–36，目标 API 36。

分别记录按钮点击、权限/投影结果、服务请求、Recorder 请求、CameraX Start 收到、第一份编码媒体 Status 收到、AudioRecord.startRecording 调用前后、首次 PCM read 返回、首个非零样本位置、AudioTimestamp 锚点、停止与定稿。时间基准为 SystemClock.elapsedRealtimeNanos 与 AudioTimestamp.TIMEBASE_BOOTTIME；真实静音也是有效 PCM，不能把“第一次非零”当作音频时间零点。

音频起点采用多个 AudioRecord.getTimestamp 的 framePosition/nanoTime 回推帧零，取中位数，并记录所有原始锚点、起点离散度与观测采样率；没有有效时间戳时明确退化为“首次 read 返回减本块时长”的近似。该 API 是系统最佳估计，不保证播放捕获的绝对呈现精度或不存在溢出。暂不重采样修正漂移。

CameraX 正式公开 Recorder API 没有绝对首个视频采集帧时间戳。已读取 Google Maven 正式 1.6.2 sources JAR 的 Recorder.java：Start 在编码器启动后、首个编码数据之前发出；recordedDurationNanos 来自当前视频编码 PTS 减首个编码 PTS，写入编码视频时更新 Status。因此用独立事件执行器收到 Status 的 BOOTTIME 减 recordedDuration 回推候选视频起点，取最小值降低可变交付延迟，记录首份观察、候选离散度和每秒原始观察。这仍包含编码/调度的未知延迟，离散度不是绝对误差上界；不能声称首帧曝光时间已测得。没有使用实验 Camera2Interop，也没有未经测量的固定同步偏移量。

以视频轨时间线为最终范围：目标 PCM 帧 j 对应原 WAV 帧 j + round((videoOrigin-audioOrigin)×48000)。超出左边补零、提前的音频裁剪、右边不足补零、超长裁剪；保留 WAV 内原有开头静音。AAC 的实际 PTS 直接使用编码器输出，不因总时长相等而判断同步；未报告的 priming/蓝牙/显示差异通过物理镜面声光测试评估，不写死补偿。AudioTimestamp 原始锚点、videoCandidates、裁剪/填充数量和编码 PTS 写入本轮 JSON 供复核。

依据：[AudioRecord.getTimestamp](https://developer.android.com/reference/android/media/AudioRecord#getTimestamp(android.media.AudioTimestamp,%20int))、[AudioTimestamp](https://developer.android.com/reference/android/media/AudioTimestamp)、[SystemClock](https://developer.android.com/reference/android/os/SystemClock)、[RecordingStats](https://developer.android.com/reference/androidx/camera/video/RecordingStats)、[CameraX 1.6.2 正式源码包](https://dl.google.com/dl/android/maven2/androidx/camera/camera-video/1.6.2/camera-video-1.6.2-sources.jar)。源码副本仅在忽略目录，不提交库源码。

## D015：原生录后合成，发布成功后才清理

查阅日期：2026-10-09；适用 API 29–36，目标 API 36。

每个 UUID 会话私有保存 video.mp4、audio.wav/.part、audio-timing.json 和原子写入的 session.json。MediaCodec 将本轮对齐后的 PCM16/48kHz/立体声编码为 AAC-LC/192kbps，中间封装 AAC MP4 以保留编码格式与 PTS；MediaExtractor 读取 AVC 和 AAC，再由 MediaMuxer 封装。视频不重新编码，保留 csd、帧次序、相邻 PTS 差值与旋转元数据。读取器 SAMPLE_FLAG 与 Codec BUFFER_FLAG 不能直接混用，只映射关键帧标志。视频 PTS 以首帧归零；AAC 不随意平移，负 PTS 或非递增序列明确失败。当前只接受单一无音轨 H.264 来源和递增视频 PTS，不伪装支持 HEVC、B 帧重排或加密输入。

目标时长采用视频轨 duration；缺失时由末帧 PTS 加观测帧间隔估计，不按按钮时间硬拉伸。空 EOS 样本标记视频与 AAC 最后一帧的期望结束时间，超出目标的编码填充包丢弃。同步偏移和编码延迟仍独立验收；容器等时长不是精准同步证据。

最终先写私有 merged.mp4.part 并定稿，再向 MediaStore Movies/MusicCam 插入 IS_PENDING 项、保存 URI 到会话日志、复制、解析轨道、解除 pending。失败删除本轮未发布媒体项，保留私有源文件；可解析的原始无音轨视频尝试另存公开备份。最终发布及再次检查成功后仅删除本轮指定中间媒体，保留 JSON；发布完成但流程中断可据已记录 URI 重新确认，无需重新采集。失败重试不会删除历史独立 WAV。捕获进程强杀、磁盘满或 provider 故障无法保证恢复，必须报告实测范围。

应用未引入 FFmpeg、额外依赖、权限或 SDK 组件。主机已有 ffprobe/ffmpeg 只独立验证容器与解码，Debug 框架 Instrumentation 只对合成测试文件运行原生编码/封装，并验证源文件哈希未改变；它不证明实际摄像头/播放捕获同步通过。

依据：[MediaCodec](https://developer.android.com/reference/android/media/MediaCodec)、[BufferInfo](https://developer.android.com/reference/android/media/MediaCodec.BufferInfo)、[MediaExtractor](https://developer.android.com/reference/android/media/MediaExtractor)、[MediaMuxer 与 EOS 时长](https://developer.android.com/reference/android/media/MediaMuxer)、[MediaStore 本应用媒体与 pending](https://developer.android.com/training/data-storage/shared/media)、[AtomicFile](https://developer.android.com/reference/android/util/AtomicFile)。

## D016：物理镜面声光同步测试，不把 UI 色块算作视频数据

查阅日期：2026-10-09；目标设备 API 36。

最小 SyncProbe 播放允许捕获的 MEDIA AudioTrack，48kHz PCM16 立体声，每秒 200ms/1kHz 脉冲；按实际 playbackHeadPosition 在 Choreographer 回调切换绿色块并记录提交显示时刻、播放头和路由类型。用户用镜子将手机屏幕真实拍入前置摄像头，不截屏、不把 View 渲染当相机采集。不会改变音量或请求焦点；为排除其他内容干扰，用户手动暂停音乐。

主机工具提取视频实际 PTS 与绿色区域脉冲起点、解码 AAC 后每 10ms 检测 1kHz 脉冲，用完整首脉冲固定配对再报告中位/范围/漂移。数量不等、绿色对比不足、稳定对数不足会明确失败。测得的是播放头、显示刷新、蓝牙呈现、播放捕获、相机及 AAC 的全路径事件差，分辨率约一视频帧加 10ms；不能分解为某一 API 的精准延迟。只以实际文件结果更新 STATUS，不由看起来播放正常推断同步。

2026-10-09 第一轮镜面实测：前置 MP4 及双轨解码正常，声光事件记录有 14 次脉冲/蓝牙 A2DP，但镜中屏幕较小且过曝，整体和屏幕 ROI 均无法可靠检测绿色起点；测试音前后仍存在持续声音。该轮同步测量明确未通过，不改变阈值或使用总时长/日志起点差代替测量。重测须由用户暂停背景音乐、降低屏幕亮度、增大镜中屏幕，并确认绿/暗变化可见；自动分析可选择实际色块 ROI，仍保留原判定条件和测量失败记录。

同日第二轮镜面实测：片段开头/末尾屏幕离开画面，手机距离变化也影响色块面积；整段分析仍失败。工具增加显式 --interval，只选已确认连续可见的原始 PTS 区间，过滤两路观测而不平移或重新编码。9–17s 内 8 对脉冲可检测，4 对稳定样本局部音频滞后中位约 101ms，量化约 33.433+10ms；阈值公式未修改。每秒重复脉冲没有唯一周期标识，缺失开头不能排除整周期错配，因此该结果只算局部估计，不能证明绝对同步或据此加入固定补偿。后续测试优先采用带唯一启动标记/非周期编码的声光事件。本轮用户要求停止追加测试，保留局限并收尾。

依据：[AudioTrack 播放头](https://developer.android.com/reference/android/media/AudioTrack#getPlaybackHeadPosition())、[Choreographer](https://developer.android.com/reference/android/view/Choreographer)、[捕获策略](https://developer.android.com/media/platform/av-capture#constraining_capture_by_other_apps)。设备结果与局限见 STATUS.md。

## D011：Phase 2 独立 CameraX 录像页，音频模块保持原样

查阅日期：2026-10-09；适用 minSdk 29 / compileSdk、targetSdk 36。

采用 CameraX 正式稳定版 1.6.2 的 PreviewView、Preview、VideoCapture<Recorder>、ProcessCameraProvider；另用稳定 Activity 1.13.0 的 ComponentActivity 提供 LifecycleOwner 和摄像头权限结果处理。只添加 camera-core、camera-camera2、camera-lifecycle、camera-video、camera-view 和 activity 直接依赖，不增加 Compose/AppCompat/播放器框架。CameraX 1.6 的 CameraPipe/Media3 为库自身传递实现；项目不另建编码/合成流水线。

实际合并 Manifest 检查发现 Media3 common 1.9.0 传递声明 ACCESS_NETWORK_STATE；本阶段只使用本地封装，不需要网络监测，以 tools:node=remove 移除此无关权限。保留 AndroidX Core 为非导出动态接收器添加的本应用 signature 权限（DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION），它不是用户运行时授权或网络访问能力。没有 INTERNET 或存储权限。

新增独立 CameraActivity；MainActivity 只添加页面入口，PlaybackCaptureService、WavFile、PocLog 不改。默认后置，停止且定稿完成后才能切前后镜头。绑定 Preview + VideoCapture 到 Activity 生命周期，onPause 显式请求停止，onDestroy 解绑自己的 use cases；Home、锁屏、返回、配置重建不会持续后台录像，返回页面不自动开始新录像。前台录像保持屏幕亮，退出即清除。相机错误、权限拒绝/撤回、启动/定稿异常明确展示，不把失败文件标为成功；进程终止无法保证最终回调，保留未确认会话提示。

依据：[CameraX 稳定版本](https://developer.android.com/jetpack/androidx/releases/camera)、[Activity 稳定版本](https://developer.android.com/jetpack/androidx/releases/activity)、[生命周期绑定](https://developer.android.com/media/camera/camerax/architecture)、[摄像头权限](https://developer.android.com/training/permissions/requesting)。原 D004 的最少依赖选择仅适用于早期音频页面，本阶段单独引入相机所需的生命周期能力。

## D012：优先 FHD/30fps、SDR，输出无音轨 MediaStore MP4

查阅日期：2026-10-09；适用 minSdk 29 / targetSdk 36，CameraX 1.6.2。

每个镜头独立查询 Recorder 视频能力，依次尝试 FHD（1080p）/HD（720p）/SD，必要时采用唯一可用质量。查询 CameraInfo AE 帧率范围，优先请求 [30,30]；不支持时选接近 30 的设备范围。用例组合绑定失败先尝试默认帧率，再降画质。AE 范围对特定用例组合并非保证，帧率是目标而非实际每帧承诺；分辨率、旋转、轨道、时长与帧率元数据通过定稿后 MediaExtractor/MediaMetadataRetriever 检查，另外用主机 ffprobe/ffmpeg 和用户回放验收。采用 SDR 与 Recorder 默认编码器，具体 codec 由实际文件报告，不预设设备必定返回 H.264。

通过 MediaStoreOutputOptions 写入公共视频集合，MIME video/mp4，RELATIVE_PATH=Movies/MusicCam；API 29+ 写入本应用拥有的媒体无需存储权限。Recorder 负责创建和定稿容器；仅收到 Finalize 后才检查并提供播放入口。发生 CameraX 错误但部分文件可解析时明确显示部分视频与错误码；无法解析时保留输出诊断，不宣称成功。强杀/磁盘满等边界另作设备测试。

摄像头页只申请 CAMERA，从不调用 withAudioEnabled，不创建 AudioRecord/MediaProjection、不请求音频焦点或修改音量/音乐播放器。现有 RECORD_AUDIO 仅继续供 Phase 1 使用，即使已获此权限，录像仍不启用音轨；容器检查要求 audio 轨为零。打开播放器是用户显式操作，其音频焦点影响与录像本身分开测试。蓝牙音乐连续性只有设备听感可确认。

本阶段 Recorder 管理自身视频时间戳；没有合成、同步、外部 PCM 注入、读取 WAV 或创建空同步接口。后续需独立验证相机编码输入、播放 PCM 编码与公共时基，不能由这个 Recorder 原型直接推断合成可行。

依据：[视频捕获架构与音频选择](https://developer.android.com/media/camera/camerax/video-capture)、[VideoCapture.Builder](https://developer.android.com/reference/androidx/camera/video/VideoCapture.Builder)、[帧率范围和组合限制](https://developer.android.com/reference/androidx/camera/core/CameraInfo#getSupportedFrameRateRanges())、[Finalize 错误与输出](https://developer.android.com/reference/androidx/camera/video/VideoRecordEvent.Finalize)、[本应用媒体权限](https://developer.android.com/training/data-storage/shared/media#access-own-files)、[MediaExtractor](https://developer.android.com/reference/android/media/MediaExtractor)。文档预期与本次构建/设备结果分别见 STATUS.md。

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
