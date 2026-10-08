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
| Phase 0（本次） | 环境检查、最小 Kotlin Android 项目、构建入口和工程文档 | 构建/Lint 的实际结果；单独记录是否真机启动 |
| Phase 1 | 单独验证 AudioPlaybackCapture，最小开始/停止与状态展示 | 授权、PCM 数据与可回放文件、允许/拒绝捕获音源对照、撤权释放、耳机路由实测 |
| Phase 2 | 接入真实相机画面，研究独立音轨与视频时间戳 | 实际画面、音频来源、输出轨道和长时间同步误差 |
| Phase 3 | 编码封装、恢复路径、产品 UI 和开源准备 | 真机回归、兼容性记录、许可证与发布资料 |

Phase 0 只有启动说明页。当前 Phase 1 已实现 MediaProjection 授权、AudioPlaybackCapture / AudioRecord、mediaProjection 前台服务、WAV 保存与回放，以及自有测试音源；实际验收结果见 STATUS.md。只加入播放捕获所需的 RECORD_AUDIO、前台服务权限和通知权限，不采集麦克风，不引入互联网、相机、存储权限、CameraX、MediaCodec 或 MediaMuxer。

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
