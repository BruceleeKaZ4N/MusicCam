# MusicCam

Android 播放音频与真实相机视频同步录制的实验项目。当前为 Phase 0：最小 Kotlin 应用和工程基础，启动页仅显示阶段说明，不进行录制。只计划支持系统与播放方允许捕获的音频。

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

仅当 Phase 1 开始实现功能时新增音频实验和会话服务代码。

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
