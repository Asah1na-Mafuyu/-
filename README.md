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
