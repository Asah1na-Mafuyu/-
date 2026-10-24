# 极简翻译

Windows 与 Android 极简翻译工具。翻译使用 DeepSeek，用户自己在设置中填写密钥；译文朗读使用无需额外 Key 的 Edge 在线声音。

Windows 支持悬浮小窗、托盘、全局热键、区域截图与图片翻译；Android 支持可拖动悬浮球、翻译小窗、系统截屏授权、区域框选与图片翻译。

Android v0.3.0 新增“拍照翻译”和“语音翻译”，主界面与悬浮小窗均有入口。Windows 保持 v0.2.0，本次没有修改或重新打包 Windows 程序。

当前暂停新增功能，保留 Windows v0.2.0 与 Android v0.3.0 双端源码。2026-10-24 已清理本地 SDK、模拟器、构建及测试缓存；源码、构建脚本、第三方许可、界面预览与验证记录均保留。

- [项目规划](项目规划.md)
- [极简使用说明](使用说明.md)
- [安卓使用说明](安卓使用说明.md)
- [主界面预览](docs/images/main.png)、[悬浮小窗](docs/images/compact.png)、[设置](docs/images/settings.png)
- [安卓主界面](docs/images/android-main.png)、[悬浮小窗](docs/images/android-floating.png)、[截图选区预览](docs/images/android-capture-preview.png)、[深色界面](docs/images/android-dark.png)
- [验证记录](验证记录.md)
- [安卓验证记录](安卓验证记录.md)

## 直接运行

- Android：dist/极简翻译-Android-v0.3.0.apk，约 101 KB，原签名覆盖升级。Android 10+，无需额外运行环境。
- 轻量版：dist/极简翻译-轻量版-v0.2.0.exe，475,347 字节（约 464 KB），下载后双击。需要 .NET 10 桌面运行时 x64（10.0.11 或更高的 10.0 修订版）；本机已具备。
- 完整版：dist/极简翻译-Windows-x64-v0.2.0/极简翻译.exe，约 72 MB，双击即可，无需另外安装 .NET。

两种 EXE 均无需安装软件，功能相同，设置和密钥共用。完整版较大是因为包含 .NET 运行时。没有运行环境的其他电脑，可选完整版或先安装 [微软 .NET 10 Desktop Runtime](https://dotnet.microsoft.com/zh-cn/download/dotnet/10.0)。

v0.2.0 为全部 9 种目标语言增加“朗读／停止”、默认关闭的自动朗读和三档语速。朗读只发送译文至微软，DeepSeek Key 不会交给微软，不消耗翻译额度。两端直接用系统网络和音频能力，不附加 Python、浏览器或语音模型。Edge 接口接入参考 [edge-tts](https://github.com/rany2/edge-tts)，属于非官方客户端，在线服务可用性以实际网络与服务状态为准。

拍照直接调用系统相机，以临时私有 content URI 接收完整照片；拍完确认选区即可翻译，无需先从图库选图。相机临时授权和文件在结束／取消时清理。选区仍发送到 DeepSeek 联网识别，未加入本地 OCR 模型。

语音采用 Android SpeechRecognizer，支持本机识别时优先本机，否则使用系统服务；没有额外的语音密钥。首次使用按需申请麦克风权限，仅在可见语音页主动开启；退出、取消、超时和失去窗口焦点均释放识别器。识别文字可编辑，确认后才按文字翻译发送至 DeepSeek，不上传录音给 DeepSeek。部分手机的系统识别器需要联网，语言和可用性取决于系统服务。

## 构建

Windows x64，.NET 10 SDK。此版使用 WPF；不依赖第三方 NuGet 包。

```powershell
dotnet build src/JiJianTranslate/JiJianTranslate.csproj -c Release
# 首选：轻量单文件 EXE
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/build-light.ps1
# 自带运行时的完整版、ZIP 和可选安装入口
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/build.ps1
```

输出在 dist，附 SHA-256 校验值。两个发布脚本分别生成轻量版和自包含完整版，默认执行相应离线诊断。清理后的源码仓库不包含构建产物，脚本会重新创建所需输出目录；首次构建需要联网恢复 .NET 所需文件。

安卓采用 Java 与系统原生界面，不捆绑 Flutter、Kotlin、AndroidX 或原生库。构建使用 JDK（本次验证为 JDK 24）、Android SDK 36 和 Build Tools 36；先安装 JDK 并将 java、javac、keytool 加入 PATH。脚本可下载并校验所需 SDK，使用 ASCII 路径映射兼容中文项目目录。手机不需要 JDK 或 SDK。

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/build-android.ps1
```

所需 SDK 缺失时，build-android.ps1 会调用 scripts/setup-android.ps1 联网下载并校验；不必保留本次清理掉的 .tools 目录。

首次构建会在 .signing 创建专用发布密钥与本地密码文件，目录仅当前 Windows 用户与 SYSTEM 可读，不进入源码或 APK。本工作区已保留原有 .signing，后续构建会复用原密钥；请另外私密备份整个文件夹，不要随安装包分发或推送 GitHub。在其他电脑覆盖升级现有 APK 时，先恢复这份签名材料，再构建；新生成的密钥不能覆盖安装原签名的应用。签名及对齐检查结果在 .qa/android/signature.txt。

## 验证

Windows 构建脚本默认执行离线诊断：API 模拟响应、错误与取消、预算账本、DPAPI、缓存和界面渲染。结果生成在 .qa 下相应目录，界面预览也写入 .qa。默认诊断用隔离数据目录，不读取个人密钥、不调用真实服务；联网朗读测试需用下方显式命令执行。已完成版本的精简文本报告保留在 [docs/verification](docs/verification)，界面预览保留在 [docs/images](docs/images)，清理缓存不会丢失这些记录。

真实 DeepSeek 端到端翻译等待用户在设置填写自己的密钥后验证。没有将模拟翻译结果作为真实接口成功结果。

安卓的接口、加密、账本与界面集成诊断使用单独测试 APK；系统交互检查运行在本项目隔离模拟器 emulator-5580，不操作真实手机：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/test-android.ps1
# 可选：独立测试服务验证语音识别系统回调，不识别真人语音
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/test-android.ps1 -InputChecks
python scripts/check-android-system.py
# 新增拍照和麦克风系统界面检查（模拟器需配置后置模拟相机）
python scripts/check-android-input.py
```

check-android-system.py 会重置隔离模拟器内的测试应用数据，以检查首次权限流程。模拟器和系统镜像已随清理移除；上述测试不会自动下载或创建模拟器，复验时需另外安装 Android Emulator 与 API 35 镜像，并配置隔离设备 emulator-5580（1080×1920、密度 420、英文系统、后置模拟相机），先构建安卓再启动该设备。构建正式 APK 不需要模拟器。默认检查结果在 .qa/android/checks.txt，语音集成结果在 checks-input.txt，系统回归在 system-checks.txt，新输入界面检查在 .qa/android-v0.3/input-system-checks.txt；测试 APK 只放在 .qa，不作为正式安装包分发。

Edge 联网检查需显式执行，使用固定示例短句，不访问 DeepSeek 或个人 Key：

```powershell
& "src/JiJianTranslate/bin/Release/net10.0-windows/JiJianTranslate.exe" --speech-check (Join-Path (Get-Location) ".qa/speech-windows")
.tools/android/platform-tools/platform-tools/adb.exe -s emulator-5580 shell am instrument -w -e edgeLive true com.jijian.translate.checks/com.jijian.translate.AndroidChecks
```

Windows 结果在 .qa/speech-windows/speech-report.txt；安卓命令在控制台输出结果。音频样例只用于测试；正式程序的安卓音频仅保留内存，Windows 临时音频播放结束或停止后清理。

## Edge 组件与对应源码

GEC v2 与语音协议适配参考 edge-tts，相关移植组件为 LGPLv3；原许可、GPLv3 正文和上游版本记录在 third_party/edge-tts，程序设置中也可查看。对应应用源码和构建脚本随 dist/极简翻译-源码-v0.3.0.zip 提供（Windows 源码仍为 v0.2.0），允许修改组件并重建／重新链接，不限制为调试这些修改而进行的逆向工程。源码 ZIP 不包含用户 Key、私钥、签名密码、SDK 或构建缓存。重建安卓会使用自己的签名；要覆盖现有安装，需在本机保留原有 .signing 材料。使用 scripts/build-source.ps1 -Version 0.3.0 可生成本次源码包。

## 结构

- src/JiJianTranslate：WPF 界面、独立 DeepSeek 服务、系统窗口与截图、设置与用量。
- src/android/app/src/main：安卓界面、悬浮服务、MediaProjection、Key 加密、DeepSeek 与本机用量。
- src/android/tests：独立安卓离线诊断，不包含在正式 APK 中。
- scripts/build.ps1：构建、离线诊断、发布与打包。
- scripts/build-light.ps1：生成轻量单文件 EXE，并对最终文件执行离线诊断；构建缓存与自包含版隔离。
- scripts/build-android.ps1：安卓构建、Release 签名、签名与对齐验证、校验值。
- 项目规划.md：功能范围、决策和后续需求。

Windows 使用原生 WPF，安卓使用原生 Java；两端沿用相同 DeepSeek 请求与结果格式，各自适配系统窗口和权限，当前不共享界面代码。

## 源码与 GitHub

提交源码时保留 src、scripts、docs、third_party、根目录说明文档、global.json 和 .gitignore。已配置忽略 dist、.tools、.qa、bin、obj、.signing、常见私钥与本机配置文件。最新安装包和源码 ZIP 继续留在本机 dist，未纳入 Git；之后需要分发时可上传到 GitHub Releases。

本地源码使用 Git 管理，默认分支为 main，远程仓库由使用者自行配置并推送。提交前应检查 git status，勿使用强制添加把被忽略的签名材料或安装包放入仓库。应用内填写的 DeepSeek Key 保存在系统的应用数据目录，不在本项目源码中。

只打包双端源码（不需要 SDK 或模拟器）：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/build-source.ps1 -Version 0.3.0
```

可重复清理的目录为 .tools、.qa、src 下的 bin／obj／build，以及 dist 中的构建中间目录。清理前先退出模拟器和正在使用这些路径的工具；保留 .signing，避免丢失后续 APK 覆盖升级的能力。
