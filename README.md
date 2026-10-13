# MusicCam

Android 播放音频与真实相机视频同步录制的实验项目。当前 Phase 4A-2 提供一次有效授权的音乐拍摄模式、连续独立短片及串行自动合成；保留单次启动摄像头与系统播放捕获的入口；保留 Phase 1 独立 WAV 和 Phase 2 无音轨录像入口。不采集麦克风。只支持系统与播放方允许捕获的音频，实际验证结果见 STATUS.md。

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

`CameraActivity` 独立管理 CameraX 预览、摄像头切换、无音轨录像和生命周期；`Mp4Inspection` 在定稿后检查视频样本、时长和音轨数量。相机页采用 ComponentActivity 与平台 Views，独立模式保留 Phase 1 流程；合成模式使用统一会话控制器协调既有服务。CameraX 1.6.2、Activity 1.13.0 固定为正式稳定版本，AndroidX 开启；传递依赖由 Gradle 正常解析。

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
2. 每次新会话手动确认 MediaProjection 系统授权；API 34+ 显式请求整个默认显示屏，API 29–33 保留原请求。如果厂商仍提供范围选择，基线测试手动选择整个屏幕，并记录实际行为。应用不创建虚拟显示、不保存屏幕视频。
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

## Phase 2 手机测试

1. 从原音频页点击「独立摄像头录像（无音轨）」，再点击「授权摄像头 / 重试预览」，手动允许 CAMERA。首次默认后置，重建保留当前镜头；拒绝不会开始录像，永久拒绝需手动前往应用设置授权。
2. 确认实时预览，在非私人场景录制约 10 秒，点击停止，等待文件检查完成。开始/停止分开；录像与定稿期间不能切镜头或再次开始。
3. 切换前置，再录制约 10 秒。输出位于相册/文件管理器可访问的 `Movies/MusicCam`，不申请存储权限；「打开最近的 MP4」显式调用系统播放器，播放操作可能改变音乐播放，需与摄像头录制阶段区分。
4. 检查前后画面、方向、时长及播放；应用容器检查应显示「视频轨 1 · 音轨 0」。帧率请求优先 30fps；分辨率依次 FHD/HD/SD，绑定失败时尝试设备默认帧率和低分辨率。请求值与文件元数据不等于实测每帧均为 30fps。
5. 录像中 Home/锁屏/返回/旋转页面应停止并定稿；回到页面只恢复预览，需手动再开始。后台不采集相机、不启动相机前台服务。强杀无法保证定稿，未完成标记不能当作成功。
6. 返回原音频页，重新授权播放捕获，用允许测试音录制 WAV 并回放，再用拒绝对照音建立对照，检查 Phase 1 回归。
7. 在蓝牙耳机播放音乐时分别打开预览、后置录像和前置录像，确认有没有暂停、中断或音量变化。应用录像不启用 CameraX audio、不请求音频焦点、不控制其他播放器；厂商/音乐 App 行为必须实测。

真机媒体与截图只存于忽略目录 `.local/phase2/`。应用事件仍共用私有 `phase1-events.log`，相机事件加 `PHASE2_` 前缀。若已有 ffprobe/ffmpeg，可独立检查拉取的 MP4（无需新工具）：

```sh
ffprobe -v error -show_streams -show_format -of json .local/phase2/videos/<文件名>.mp4
ffmpeg -v error -i .local/phase2/videos/<文件名>.mp4 -map 0:v:0 -f null -
```

应为单一 video 轨、零 audio 轨；解码成功不能代替用户确认取景内容和蓝牙听感。

## Phase 3 手机测试与同步边界

1. 从主页面打开「摄像头 + 系统音频自动合成」，点击开始；缺少 CAMERA / RECORD_AUDIO 时先由用户授权，再完成本次新的系统投影授权。Phase 4A 在 API 34+ 请求整个默认显示屏；仍由用户确认，取消授权不创建录制会话。这个原单段入口每段仍重新授权；连续拍摄请使用新的音乐模式入口。不要用 ADB 代替授权。
2. 在蓝牙耳机播放允许捕获的内容时录制非私人场景，点击「停止并合成」，等待编码与发布。Home、离开页面、摄像头错误、投影撤回会停止两路；正常停止后继续在应用工作线程合成，不继续后台采集相机。
3. 输出名 MusicCam-AV-<会话 UUID>.mp4，位于 Movies/MusicCam。应有一条 H.264 和一条 AAC-LC 48kHz 立体声音轨。全静音会明确警告，不能认定捕获成功。请确认画面、方向、时长和音乐回放。
4. 同步采用 AudioRecord BOOTTIME 时间戳与 CameraX Status 事件的近似视频起点。它不是按钮点击时刻，也不是 CameraX Start；不会按总时长相等推定同步。视频绝对采集时间无法通过当前正式 Recorder API 精确取得；事件延迟、AudioTimestamp 最佳估计、AAC priming、蓝牙呈现及漂移都需要实测。
5. 失败保留 files/sessions/<UUID> 内已取得的原始视频、WAV/.part 和 JSON 时间记录；可解析的视频另尝试保存 MusicCam-source-<UUID>.mp4。只在最终媒体检查并发布成功后删除本轮中间媒体，JSON 审计保留。「重试未完成的合成」不重新捕获或复用授权；中间数据不足时明确失败，不伪造音轨。进程强杀后的未定稿数据不能保证恢复，卸载会丢失私有数据。
6. 重新测试独立 WAV、独立无音轨视频、权限取消与快速停止。完整测试状态以 STATUS.md 为准，不把合成成功当作精准同步通过。

声光测试：暂停其他音乐，保留要测试的音频路由；切前置，用镜子让摄像头真实拍到手机屏幕的色块。开始并授权，正在录制后按「开始 / 停止声光同步测试」，保持稳定约 15 秒再停止合成。音源为允许捕获的 MEDIA AudioTrack，每秒播放 200ms 的 1kHz 脉冲；屏幕根据实际播放头显示绿/暗色块，不修改音量或请求音频焦点。必须拍到物理反射，预览上的 UI 本身不会写入相机视频。

主机分析（需已有 ffmpeg/ffprobe，Android 应用没有此依赖）：

    python3 tools/measure_sync.py .local/phase3/videos/<测试MP4> --output .local/phase3/sync-report.json
    # 必要时指定旋转后视频内色块的归一化区域 x,y,width,height。
    python3 tools/measure_sync.py .local/phase3/videos/<测试MP4> --roi 0.1,0.1,0.8,0.2
    # 只分析已人工确认连续可见的原始 PTS 区间；两轨不平移，不重新编码。
    python3 tools/measure_sync.py .local/phase3/videos/<测试MP4> --roi 0.1,0.1,0.8,0.2 --interval 9,17

报告正值表示声音晚于可见闪光。测量包含播放头/显示刷新/蓝牙/采集/编码全路径，不是孤立摄像头的精准采集延迟；量化约一帧加 10ms。重复脉冲以分析区间内首个可见脉冲配对，需确保从测试开始就完整拍到色块；脉冲缺失、计数不等或对比不足会明确失败。若开头没拍到，可见区间只能给出局部相位估计：没有独特周期标记，不能排除整秒错配。报告中的 passed 只表示所选区间检测/统计条件满足，absolute_cycle_identity_verified=false 不能当作绝对同步通过。

Debug 另带无第三方测试库的框架 Instrumentation，以合成文件测试真实 MediaCodec/Extractor/Muxer 的提前音频裁剪、滞后补静音、尾部时长、方向、AAC 可解码与失败保留。只用合成测试媒体，不请求采集权限。**运行会重启本应用，必须先结束实际录制**：

    source .local/env.sh
    # 安装当前 Debug APK（手机手动确认）；保持本应用无活动录制。
    python3 tools/check_native_composer.py

测试数据与报告仅保留在 .local/phase3/native-checks 和手机 files/native-checks。Shell app_process 路径曾被目标设备终止，改用公开框架 Instrumentation；测试代码仅位于 src/debug，Release 不包含测试入口。设备编码测试与实际两路捕获/主观回放分别验收。

## Phase 4A-2 音乐拍摄模式

1. 先在音乐 App 播放允许捕获的音乐，然后进入“音乐拍摄模式（一次授权连续拍摄）”，点击“进入音乐模式”并手动确认系统授权。
2. 页面明确显示持续捕获。点击“开始视频 + 系统音频”，短录 A 后“停止并合成”；等待保存完成再录 B、C。当前有效模式内不再弹授权，每段各一个 MusicCam-AV-<UUID>.mp4。
3. 片段间音频仍读取但直接丢弃，没有跨整模式的长 WAV。合成串行，期间开始按钮禁用；有明确前台通知时可以切到音乐 App 选歌，录像和系统内录继续；从音乐模式入口或通知返回当前片段。回放可能改变播放音源/音频焦点，验收请录完再回放。
4. 点击“结束音乐模式”，或显式返回退出、锁屏结束捕获并释放授权。Home/切换应用有通知时保留模式；通知关闭则结束后台捕获。空闲 3 分钟自动结束；单段 10 分钟自动停止并合成，模式 30 分钟自动结束。再次进入必须重新授权；取消不启动任何采集，不持久化 token。
5. 系统撤权/读取或相机异常停止当前录制，尽可能保留本段原始文件；成功保存的其他段不受影响。既有失败重试只用文件；界面仅记最近一个未完成 UUID，旧失败数据仍保留在私有 sessions 中。强杀不保证定稿。
6. 在 vivo 验收三段视频各含视频/AAC、音乐属于各自录制期间、间隔没有串段；用户确认听感/蓝牙是否中断，主机核对 JSON 全局区间与局部帧数。时间对齐沿用 Phase 3，不能把本轮成功当作精准同步通过。

无需新测试库的 Debug PCM 验证使用实际片段写入器，输入不同 A/B/C 与间隔标记，检查不串段、BOOTTIME 换算、报告冻结、错误保留及拒绝覆盖。先结束实际录制（Instrumentation 会重启应用）：

```sh
source .local/env.sh
adb -d shell am instrument -w -e suite music_pcm dev.musiccam.prototype/.DeviceMediaInstrumentation
adb -d exec-out run-as dev.musiccam.prototype cat files/music-pcm-check.json
python3 tools/check_native_composer.py --output .local/phase4a2/native-checks
```

这些合成/写入器测试不使用真实投影，不能替代一次授权 A/B/C 与生命周期真机验证；最新实际结果见 STATUS.md。

后台录像按维护者测试反馈增加（D020）：在相机页显式开始后由带 camera/mediaProjection 类型的前台服务持有相机生命周期，通知明确显示摄像头/系统音频状态并提供“停止本段并合成”与“结束音乐模式”。通知权限或频道关闭时不保留后台捕获。不需要忽略电池优化/悬浮窗权限，系统仍可撤权或终止进程；不支持锁屏继续拍摄、自动后台启动或永久常驻。原单段合成/独立摄像头入口仍离开页面停止。

返回页面停顿的小修正见 D021：同一音乐页面暂时切后台保留预览连接，真正销毁才断开。页面重建或厂商驱动行为仍可能造成停顿；用户已确认本机返回停顿解决；结果与覆盖边界见 STATUS.md。

音乐模式开始黑屏的优化见 D022：预览阶段采用可交接的相机生命周期，开始时接管现有绑定，空闲切后台仍释放相机。该优化已构建，首轮手机拒绝安装，2026-10-13用户允许后安装成功，并确认问题解决、本阶段完成；独立摄像头切出仍按原设计停止并保存。
