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
