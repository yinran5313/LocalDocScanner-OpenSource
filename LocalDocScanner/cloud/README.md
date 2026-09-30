# Linux 构建与 Office 内置工作

扫描候选版可独立构建。完整 Office 当前仍使用外部 Collabora 联动；本目录保留内置引擎的可复现构建入口，不代表单 APK Office 已完成。

## 扫描工程

在仓库根目录运行 `python LocalDocScanner/tools/restore_binary_assets.py`，再进入 `LocalDocScanner/`。

1. 运行 `bash cloud/probe.sh --network`，记录发行版、CPU 配额、内存和磁盘。
2. 安装 JDK 17、Android SDK 35 与 build-tools 35.0.0；Gradle wrapper 为 8.10.2。
3. 运行 `bash cloud/build-scanner.sh`，保留单测、Lint、构建日志、APK 与 SHA256。

`ppocr-sdk` 明确使用 JDK 17。所有模型与字体必须按清单恢复，不能用空文件占位。公开测试素材不包含手机文档库或私人照片。

## 完整 Office 引擎

`collabora-upstream.lock` 固定 CollaboraOnline/online.mirror 源码提交及配套 SDK/NDK。运行 `bash cloud/build-collabora-arm64.sh`，先构建该版本完整原版引擎；不得混用扫描工程的 SDK 35 与 Office 的 SDK 37/NDK 29。

整合时需要 Android 编辑器类、JNI、Web 编辑器资源、字体与对应许可证一起进入主 App，再执行 `bash cloud/verify-office-contract.sh`。当前该检查应明确报告缺失组件，不能以空入口通过验收。

验证依次区分：扫描构建通过、原版引擎构建通过、单 APK 构建通过、设备上编辑与保存回写通过。源码入口及许可边界见仓库 `docs/SOURCE_REUSE.md`。

本项目自有代码采用 MIT；未来采用的 MPL 文件仍需保留来源和修改声明，并完整审查三方许可。GPL/AGPL 参考代码不得复制进主工程。

## 覆盖安装

包名保持 `com.localdoc.scanner`。云端不持有开发者本机旧签名密钥，云端 APK 只作构建证据；可覆盖旧版的交付包须回到本机以原签名生成。不要提交私钥、账号配置或私人文档。
