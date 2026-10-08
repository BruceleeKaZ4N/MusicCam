# MusicCam

Android 播放音频与真实相机视频同步录制的实验项目。当前为 Phase 1：最小系统播放音频捕获 PoC，可开始/停止、保存 PCM16 WAV 并回放；不采集麦克风或相机。只支持系统与播放方允许捕获的音频，实际验证结果见 STATUS.md。

产品边界见 [PROJECT.md](PROJECT.md)，架构理由见 [DECISIONS.md](DECISIONS.md)，实际验证进度见 [STATUS.md](STATUS.md)，工程协作规则见 [AGENTS.md](AGENTS.md)。项目尚未确定开源许可证。

## 结构

```text
MusicCam/
├── AGENTS.md / PROJECT.md / DECISIONS.md / STATUS.md
├── README.md
├── settings.gradle.kts / build.gradle.kts / gradle.properties
├── gradlew / gradlew.bat / gradle/wrapper/
├── app/
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/dev/musiccam/prototype/MainActivity.kt
│       └── res/
├── local.properties       # 本机 SDK 路径，不提交
└── .local/env.sh          # 本机临时环境入口，不提交
```

`MainActivity` 提供授权入口、测试音和 WAV 回放；`PlaybackCaptureService` 管理捕获会话，`WavFile` 写入 WAV，`PocLog` 输出诊断事件。`tools/inspect_wav.py` 使用 Python 标准库独立检查文件，无新增 Android 依赖。

## 构建

需要现有 JDK 17、Android SDK Platform 36、Build Tools 36.0.0 和 Platform Tools。Gradle Wrapper 固定为 9.7.1，AGP 9.2.1 内置 Kotlin 配合 KGP 2.4.20。

本机已生成 `.local/env.sh`；它只设置当前终端环境，并复用已有工具缓存，不修改 shell 启动文件：

```sh
source .local/env.sh
./gradlew --version
./gradlew --offline --no-daemon :app:assembleDebug :app:lintDebug
```

新机器需先经维护者同意安装工具，并设置自己的 JAVA_HOME 与 SDK 路径（`local.properties` 的 `sdk.dir` 或 ANDROID_HOME）。首次没有依赖缓存时去掉 `--offline`，允许 Gradle 解析项目构建依赖。项目关闭 SDK 自动下载；SDK 缺失时先报告再征求安装同意。

Debug APK：`app/build/outputs/apk/debug/app-debug.apk`。
Lint 报告：`app/build/reports/lint-results-debug.html`。

## 真机启动验证（需连接和授权设备）

```sh
source .local/env.sh
adb devices -l
adb shell getprop ro.product.model
adb shell getprop ro.build.version.release
adb shell getprop ro.build.version.sdk
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n dev.musiccam.prototype/.MainActivity
```

若有多个设备，为命令加 `adb -s <设备序列号>`；不要把序列号写入可提交的日志。看到启动说明页只证明安装/启动成功；音频捕获、音乐 App 兼容性、耳机路由和音视频同步需要后续独立实测。

## Phase 1 手机测试

1. 点击「开始录音」，手动允许音频权限。系统可能称为“麦克风”权限，但应用仅配置播放捕获；通知权限拒绝不阻止录音。
2. 每次新会话手动确认 MediaProjection 系统授权；若系统提供范围选择，基线测试选择整个屏幕。应用不创建虚拟显示、不保存屏幕视频。
3. 显示正在录音后点击「播放允许捕获的测试音」，手动调整媒体音量，等待约 10 秒。此音源明确配置 usage=MEDIA / ALLOW_CAPTURE_BY_ALL，左 440 Hz / 右 880 Hz。
4. 点击「停止录音」，等待 WAV 保存完成，然后「停止测试音 / 回放」。结果展示实际数据时长、大小、非零样本和峰值；全静音不能判定成功。
5. 点击「回放最近的 WAV」，确认能听到刚才的音源。再单独开一次新会话，只播放「拒绝捕获的对照音」验证 NONE 策略；排除其他正在播放的可捕获音源。
6. 单独测试取消投影授权、拒绝音频权限、重复点击、通知栏停止、系统撤回投影、锁屏、明确退出和任务划除。Home 切换保留前台捕获；「停止录音并退出」结束会话。系统强杀无法保证保存，残留 `.part` 不当作完整 WAV。

文件位于应用私有目录 `files/recordings/`，不申请存储权限；卸载会移除数据。Debug 版本可通过 ADB 读取。授权必须由用户完成，不通过 ADB 授权命令代替。

```sh
source .local/env.sh
adb -d shell pidof dev.musiccam.prototype
# 用上一步实际 PID，显式检查所有日志级别；若日志为空再核对 PID/包名/过滤条件。
adb -d logcat -d -b main -b system -b crash --pid=<PID> -v threadtime '*:V'
# 补充会话事件（包含启动探针与权限/录制状态，不含授权 token）。
mkdir -p .local/phase1/recordings
adb -d exec-out run-as dev.musiccam.prototype cat files/phase1-events.log > .local/phase1/session-events.log
adb -d shell run-as dev.musiccam.prototype ls -l files/recordings
# 替换为上一条命令列出的已定稿文件名；仅保存到被忽略的本地目录。
adb -d exec-out run-as dev.musiccam.prototype cat files/recordings/<文件名>.wav > .local/phase1/recordings/<文件名>.wav
python3 tools/inspect_wav.py .local/phase1/recordings/<文件名>.wav --expect non-silent
```

`-d` 只适用于唯一已连接 USB 设备。拒绝捕获对照检查用 `--expect silent`；存在其他可捕获音源时不能期待全静音。检测结果只说明文件参数与数据，不自动证明音源正确或播放听感正常。录音、截图和本机证据保留在 `.local/`，不得提交私人媒体。
