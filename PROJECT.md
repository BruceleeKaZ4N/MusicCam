# MusicCam

## 产品定位

MusicCam 是一个计划在 GitHub 开源的 Android 相机应用。目标是让用户戴耳机听音乐时，拍摄真实摄像头画面，并把系统允许捕获的播放音频同步写入视频，尽量免去后期剪辑。

产品能力限定为“捕获符合系统与播放方策略的音频”，不承诺任意音乐 App、会员曲目或 DRM 内容兼容。当前没有发布、没有确定开源许可证。

## 验证环境

- 开发机：用户提供 macOS / Apple Silicon M4；本机检查为 arm64 / macOS 27.0.1。
- 目标真机：用户提供 vivo S50 Pro mini；ADB 已核实 vivo V2527A，Android 16 / API 36。OriginOS 6 为用户描述，系统属性见 STATUS.md。
- 最低 Android 版本：Android 10 / API 29，因为 AudioPlaybackCapture 从此版本提供。
- 初始 compileSdk / targetSdk：36，验证 Android 16 的现行系统行为。

## 阶段范围

| 阶段 | 目标 | 验收依据 |
| --- | --- | --- |
| Phase 0 | 环境检查、最小 Kotlin Android 项目、构建入口和工程文档 | 构建/Lint 的实际结果；单独记录是否真机启动 |
| Phase 1 | 单独验证 AudioPlaybackCapture，最小开始/停止与状态展示 | 授权、PCM 数据与可回放文件、允许/拒绝捕获音源对照、撤权释放、耳机路由实测 |
| Phase 2 | CameraX 独立摄像头预览、前后切换与无音轨 MP4 录像 | 真机画面、文件播放、时长、零音轨、音频模块回归、蓝牙音乐连续性 |
| Phase 3 | 单次启动两路采集，录后原生 AAC 编码与 H.264/AAC MP4 合成 | 最小原型完成，回归通过；局部同步估计及测量歧义见 STATUS.md |
| Phase 4A（当前） | API 34+ 默认显示屏授权请求优化；一次授权连续拍摄的架构分析 | 已完成：构建/Lint、vivo 弹窗/取消授权、后置短片合成与独立音频回归；连续拍摄等待 Phase 4A-2 决定 |
| 后续 | 同步改进、兼容性与产品 UI、开源准备 | 长时与多路由验证、许可证与发布资料 |

Phase 0 只有启动说明页。Phase 1 已实现 MediaProjection 授权、AudioPlaybackCapture / AudioRecord、mediaProjection 前台服务、WAV 保存与回放，以及自有测试音源；核心真机验收通过，实际结果与未完成异常测试见 STATUS.md。

Phase 2 已完成独立 CameraX 无音轨录像。Phase 3 已在同一相机页增加独立的合成入口：统一控制器启动 CameraX Recorder 与既有 AudioPlaybackCapture，停止并等两路定稿后，将本轮 PCM/WAV 编码为 AAC，再无损复用 H.264 视频到 Movies/MusicCam。原独立音频/视频入口保留。

当前 Phase 4A 仅调整两个播放捕获授权入口：API 34+ 使用 MediaProjectionConfig.createConfigForDefaultDisplay() 与 createScreenCaptureIntent(config)，API 29–33 保留无参数请求。系统弹窗仍由用户确认；不创建 VirtualDisplay，CameraX 仍是视频来源，音频捕获、合成和时间戳逻辑不变。vivo 的实际弹窗及回归结果分别记录在 STATUS.md，不把 API 预期当作设备结论。一次授权连续拍摄仅完成可行性与最小方案分析（DECISIONS.md D018），未实施，等待维护者决定是否进入 Phase 4A-2。本阶段不重复声光测试、不处理既有约 100ms 局部偏差及测量歧义，不做 UI 美化、相册管理、实时封装或多机型适配。

使用 BOOTTIME 单调时钟记录会话事件，AudioRecord 时间戳与 CameraX 编码事件估计两路起点；CameraX 正式公开 API 无绝对首帧采集时间戳，因此属于可测量的近似对齐，不宣称精准同步。音频按测得的起点裁剪/补静音，保留开头有效静音，尾部以视频时间线为准；AAC priming 和长时漂移仍须实测。MediaProjection 仅授权播放捕获，不是视频来源。每轮重新由用户授权，不复用历史 WAV。保持 minSdk 29 / compileSdk、targetSdk 36，无新增依赖、网络或存储权限。

## 已确认的技术限制

以下为 Android 官方 API 约束，查阅日期 2026-10-08；设备兼容性仍待测试。

- 播放捕获需要 `RECORD_AUDIO`、用户批准 MediaProjection，且播放方与捕获方在同一用户配置文件。使用 `AudioPlaybackCaptureConfiguration` 配置 `AudioRecord` 的输入。[官方捕获说明](https://developer.android.com/media/platform/av-capture)
- 可捕获的播放器 usage 限于 MEDIA、GAME、UNKNOWN，且有效策略必须允许普通应用捕获。Manifest、AudioManager 与播放器策略取最严格结果；目标版本的默认允许行为不代表一定可捕获。[官方捕获策略](https://developer.android.com/media/platform/av-capture#constraining_capture_by_other_apps)
- Android 14+ 的 MediaProjection 流程需要相应前台服务声明；未来从可见页面取得用户同意后启动服务并进入前台，再获取 projection。会话授权不可作为可重复使用的持久凭证。[前台服务要求](https://developer.android.com/about/versions/14/changes/fgs-types-required#media-projection)
- MediaProjection 的 `onStop()` 必须驱动停止和释放。官方屏幕投影文档说明较新系统会在锁屏等情况下停止投影；纯音频会话在目标 OriginOS 上的实际回调与路由行为必须验证。[会话资源释放](https://developer.android.com/media/grow/media-projection#resource-recovery)
- MediaProjection 在此用于播放音频捕获授权。未来视频画面来自真实相机；Phase 1 不为音频实验创建屏幕截图或屏幕视频。
- 耳机连接、蓝牙延迟、厂商系统行为、多个播放器混音、音源静音及捕获策略可能影响实验结果；不能仅凭无声推断具体原因。
- 未来音视频同步与输出格式是待验证设计；系统授权通过、PCM 读取成功、编码成功、文件可回放和同步正确是不同验收项。

## 非目标

不绕过 DRM、Root、应用/系统音频捕获限制；不获取音乐 App 的下载文件；不默认混入麦克风；不在后台隐蔽采集；不在 Phase 0 承诺完整相机或音乐 App 兼容性。
