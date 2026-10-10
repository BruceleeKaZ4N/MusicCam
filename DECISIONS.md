# 架构决策

记录日期：2026-10-08。构建可行性与设备可行性分开验收；尚未实测的方案保持待验证。

## D017：API 34+ 显式请求默认显示屏，保留每次系统同意

查阅日期：2026-10-09～10（Asia/Shanghai）；适用 minSdk 29 / compileSdk、targetSdk 36。

源码核实有两处请求：CameraActivity.continueCombinedStart()（合成录像）和 MainActivity.requestProjection()（独立音频）。两处均在 SDK_INT >= 34 时调用 MediaProjectionConfig.createConfigForDefaultDisplay()，再调用 MediaProjectionManager.createScreenCaptureIntent(config)；API 29–33 保留无参数版本。就地加版本分支，不引入新授权模块或依赖；日志只增加请求范围 default_display/legacy，不记录授权内容，也不声称该日志证明厂商弹窗实际范围。

官方预期为仅请求默认显示屏、免去单应用范围选择；API 与配置均从 34 提供，无参数版本等价于用户选择配置。系统仍提示用户同意，厂商可覆盖退出单应用共享的配置，实际 UI 必须另行观察。不使用自动点击授权、隐藏 API 或特殊权限，不创建 VirtualDisplay，不改变 CameraX、AudioRecord、合成、BOOTTIME 和释放路径。每次录制仍是一轮新授权；这项改动不实现连续拍摄。

来源：[MediaProjectionConfig](https://developer.android.com/reference/android/media/projection/MediaProjectionConfig#createConfigForDefaultDisplay())、[MediaProjectionManager 授权请求](https://developer.android.com/reference/android/media/projection/MediaProjectionManager#createScreenCaptureIntent(android.media.projection.MediaProjectionConfig))、[单应用共享退出与厂商覆盖](https://developer.android.com/media/grow/media-projection#opt_out)。构建和本次 vivo 实际行为见 STATUS.md Phase 4A。

本次设备观察：vivo V2527A / Android16 / API36 / PD2527C_A_16.0.19.3.W10，合成入口弹窗范围显示「共享整个屏幕」、范围项灰色，展开提示「MusicCam 已停用此选项」，仍有系统「开始」按钮。此设备遵循请求配置，无需手动切换范围；不能由一台设备推广到其他厂商或系统版本。

## D018：一次有效授权支持多段相机视频的可行性（仅分析，未实施）

查阅日期：2026-10-09～10；评估 API 29–36，Android 14–16 / targetSdk 36 的生命周期约束。以下是基于源码和官方 API 的设计推论，连续拍摄尚无设备验证，实施暂停至维护者决定 Phase 4A-2。

### 当前调用链与耦合

合成入口：CameraActivity.beginCombined() → 权限结果 → continueCombinedStart() → 系统投影结果；新 Intent 只在内存临时存放，消费前清空 consent → CombinedSessionController.start()/begin()。控制器创建单段 UUID，启动 PlaybackCaptureService.ACTION_START，再启动禁用 CameraX audio 的 Recorder。服务先 startForeground(mediaProjection)，再 getMediaProjection() 一次、注册 onStop()，工作线程构建 AudioPlaybackCaptureConfiguration 与单个 AudioRecord，写这段目录的 audio.wav.part 并记录 BOOTTIME。控制器以 100ms 轮询音频状态并接收 CameraX 事件，两路实际启动才进入 RECORDING。

正常停止/离开相机页/配置变化/相机错误 → CombinedSessionController.stop()/abort() → PlaybackCaptureService.stop(reason, id) 与 Recording.stop()。音频工作线程退出后 stop/release AudioRecord → WAV.finish() → AudioCaptureTiming.finish() → complete() → unregisterCallback、MediaProjection.stop()、移除前台通知、stopSelf()。CameraX Finalize 收到后关闭 Recording；控制器等待两路定稿，再编码/发布。成功只清理该 UUID 中间媒体，失败保留并尝试导出无音轨视频；retry() 只读取已完成文件、不再授权。

系统撤权：PlaybackCaptureService.callback.onStop() → requestStop() → 音频线程定稿与 complete()；控制器轮询到音频不再 active，停止相机并汇合。现有实现不是 onStop 同步直达控制器，相机停止要经过音频结束和轮询。服务 onDestroy/onTaskRemoved、通知 ACTION_STOP 也请求停止。onDestroy 先释放 projection，工作线程以停止标志退出；进程强杀不保证执行。纯音频独立入口 MainActivity.onActivityResult() 直接启动同一服务，sessionId=null，写 recordings/ 并更新独立回放偏好；其 Home 行为与相机页离开即停不同。

无法直接连续拍摄的原因：服务的 started/stopping 是单次不可恢复状态，第二次 ACTION_START 被忽略；sessionId、WavFile、AudioCaptureTiming 和 status 都属于一段录像。stop() 会关闭投影和服务；控制器 active 直到合成完成才清空，并以 !audio.active 作为音频定稿信号；CameraActivity 离开就停止整个会话。删掉 MediaProjection.stop() 一行会留下失去 AudioRecord/状态管理的会话，不能解决这些耦合。

### 推荐的最小方案

可行方向是在用户明确进入音乐拍摄模式时取得一次新授权，由前台服务持有一个仍有效的 MediaProjection 和一个持续运行的 AudioRecord；A/B/C 是该音频会话内的独立文件片段，CameraX 继续每段 prepare/start/stop/Finalize。仅首次调用 getMediaProjection()，不重复消费结果 Intent、不持久化 token、不创建 VirtualDisplay。服务技术上能持续运行（现有独立音频模式已经可在 Home 后继续），但目前代码需调整所有权；文档没有保证纯音频会话永久有效，系统可随时结束它。

将“结束片段”与“结束音乐模式”分成两个命令：结束片段只定稿其 WAV/视频，结束模式才停止 AudioRecord、projection 和前台服务。为控制改动，第一版仍串行完成 A 的定稿/合成后才允许 B 开始，期间保留有效音频会话，避免新增并行合成队列。若产品要求 A 合成同时拍 B，需另行扩大范围；不必为免重复授权引入此复杂度。

音频线程持续及时读取，只在片段打开期间写文件，片段间读到的 PCM 直接丢弃；不写一份贯穿整个模式的长 WAV。所有片段打开/关闭由同一音频线程在完整立体声帧边界串行执行，并带模式 generation 和片段 UUID 回执，拒绝陈旧命令。开始片段先确认 WAV 已打开，再请求 CameraX 开始；停止先请求 CameraX.stop()，待其 Finalize 后关闭片段 WAV（有定稿超时），保留覆盖视频实际尾部的 PCM，再由已有算法裁剪。这样无需最初就实现共享文件索引、环形缓存或改编码器；Finalization 等待期间仍采集音频，应明确展示并设上限。

每段保存独立 video.mp4、audio.wav/.part、audio-timing.json、session.json。音频模式建立全局已读帧计数，空档丢弃的数据也计数；片段记录 [startFrameInclusive,endFrameExclusive)，局部帧 k 对应全局 startFrame+k。写入帧数必须等于 end-start，非零计数/首非零位置局部化，真实静音保持有效，不拿非零样本作时间原点。

保持 elapsedRealtimeNanos/TIMEBASE_BOOTTIME：若连续音频锚点为 (F,T)，片段从全局帧 S 开始，则其帧零候选为 T-(F-S)×10^9/48000，等价于全局起点+S×10^9/48000。只选片段附近且连续有效的锚点，冻结每段报告后不再随后续数据改写；保留原始全局锚点、S/E 和估计离散度以便审计。局部首次 read 回推仍只能是带标记的近似 fallback。AudioRecord 时间戳计数与已读帧序列的一致性需实际验证，不能遇到溢出/丢帧/ERROR_DEAD_OBJECT 后假定连续；异常结束当前模式并保留受影响片段，下一次重新授权。不能对每段 AudioRecord.stop/start 后仍使用旧 framePosition 原点。

CameraX 的每段 Status 起点算法保持；给既有 AudioVideoComposer/AudioAlignment 提供片段自己的 originEstimateNs 和 WAV，继续按视频实际时间范围裁剪/补静音，AAC 编码器与 muxer 不变。同步仍是 Phase 3 的近似，不把连续捕获当作修复约 100ms 局部偏差；不加固定补偿。

onStop() 或读错时立即将模式标为不可用，阻止新片段、废弃所有待启动命令，并通知活动控制器停止相机；音频线程完成有限定稿/释放。已有完成片段可继续离线合成，当前片段记录撤权/错误并保留，不能用撤权后的静音伪装成功。Notification 停止、退出模式、离开页面/锁屏、权限撤回同样结束模式，清除内存引用；若返回页面再次进入必须新授权。不依赖纯音频在这台手机上曾锁屏不断的历史观察作为保留后台授权的保证。

每段等待“该 WAV 与时间报告已定稿”的回执，不再等待整个服务 inactive。每段仍独立使用 SessionStorage.publish() 的 pending/检查/发布与 cleanupMedia()，失败只影响该 UUID，模式停止不删除已完成媒体；合成失败后其他片段及独立 WAV 不受影响。最低方案不增加共享长文件，所以成功清理不会删掉其他片段正使用的 PCM；recoverableId 目前只能表示一个失败片段，多失败管理暂不扩大、需明确这一限制。

### 文件、风险和验收

| 预计修改文件 | 最小必要调整 |
| --- | --- |
| CameraActivity.kt | 模式授权/进入/退出、片段开始停止、离开结束模式、按模式与片段分别显示状态 |
| PlaybackCaptureService.kt | 模式持有 projection/AudioRecord；片段写入命令与定稿回执；模式 generation、全局帧计数、撤权即时通知、空档丢弃 |
| CombinedSessionController.kt | 不再每段接收授权/启动服务；按片段回执汇合，分离片段停止和模式停止，保留每段状态/失败/串行合成 |
| AudioCaptureTiming.kt | 连续锚点采样与片段起点/计数换算、逐段冻结报告、连续性检查；保留 BOOTTIME |
| SessionStorage.kt | 记录模式关联及片段帧范围；保持每段私有文件/原子写入/独立清理，避免共享可变报告 |
| MainActivity.kt / strings.xml | 独立音频与音乐模式互斥、明确持续捕获与结束入口、通知/页面状态文案 |
| PROJECT.md / DECISIONS.md / STATUS.md / README.md | 更新生命周期、操作与实际验收范围 |

预计无需修改 AudioVideoComposer.kt、AudioAlignment.kt、WavFile.kt 或 Gradle/Manifest 权限；若逐段输出契约未能兼容，应先明确差异。调整横跨服务、控制器、UI 和时序，属于实质生命周期重构，不能附带在本次弹窗优化中实施。

主要风险是读线程/命令/撤权竞态、全局帧与时间戳失配、长时漂移、定稿等待尾部、电量和热量、写入/合成争用、单失败偏好覆盖，以及旧 UI 无法区分“已停片段但还在捕获”。最小方案只在页面可见的音乐模式保持捕获，持续通知和页面指示，提供随时结束入口；空档不持久化 PCM，设有限空闲超时和单段/模式时长上限，退出/后台/锁屏主动停止，不静默自动重新授权。不用 AudioRecord 停启节电来换取未经验证的旧 token 重建；空闲超时结束后下次明确授权。具体超时数值在 Phase 4A-2 选定并实测功耗，不宣称丢弃 PCM 等于停止采集。

Phase 4A-2 验证计划：先复跑 Phase 3 单段前后置/独立 WAV/无音轨视频/蓝牙音乐/取消授权/失败保留；再同一有效模式 A/B/C 检查仅一次授权和一次 getMediaProjection、单个连续 AudioRecord、每段 UUID/帧范围/BOOTTIME 报告独立冻结、双轨完整解码与正确音源、空档内容不进入片段。用可控且各段不同的音源验证切片顺序与边界，主机独立核对帧映射，沿用原生合成文件对照。分别在录制/空闲/定稿时撤权或退出、验证禁止 B 启动及全部资源释放，重新进入确有新授权；验证超时、快速点击、磁盘失败不污染其他片段。增加有界长时/空闲功耗观察；无需重复本阶段高成本声光测试，原同步局限保持。

来源：[播放音频授权与撤权](https://developer.android.com/media/platform/av-capture#how_to_handle_a_mediaprojection_token)、[Android 14+ 单次授权使用与会话释放](https://developer.android.com/media/grow/media-projection#user_consent)、[MediaProjectionManager 前台服务顺序](https://developer.android.com/reference/android/media/projection/MediaProjectionManager)、[AudioRecord 时间戳与读取](https://developer.android.com/reference/android/media/AudioRecord#getTimestamp(android.media.AudioTimestamp,%20int))。屏幕投影文档的单次 VirtualDisplay 规则不等于每个 CameraX 片段必须创建新投影；本方案只在一个仍有效的播放捕获会话内划分文件，纯音频的持续行为仍须设备验证。

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

Phase 4A 的 API34+ 请求配置已由 D017 更新；此处授权、前台服务与释放顺序保持不变。

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
