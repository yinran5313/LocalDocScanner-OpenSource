# Linux 构建与 Office 内置工作

V4.3.2的扫描、内置Office及Leptonica模块已在本机构建为同一APK。本目录保留Linux构建入口；构建与静态检查通过不代表手机编辑保存往返已经验收。

## 扫描工程

在仓库根目录运行 `python LocalDocScanner/tools/restore_binary_assets.py`，再进入 `LocalDocScanner/`。

1. 运行 `bash cloud/probe.sh --network`，记录发行版、CPU 配额、内存和磁盘。
2. 安装JDK17、Android SDK35、build-tools35.0.0、NDK29.0.14206865、CMake3.22.1；Gradle wrapper为8.10.2。运行`python tools/restore_office_runtime.py`恢复SHA锁定的官方Office运行时。
3. 运行 `bash cloud/build-scanner.sh`，保留单测、Lint、构建日志、APK 与 SHA256。

`ppocr-sdk` 明确使用 JDK 17。所有模型与字体必须按清单恢复，不能用空文件占位。公开测试素材不包含手机文档库或私人照片。

## 完整 Office 引擎

当前内置模块的唯一匹配版本是`office-engine/runtime-manifest.json`中的20a46c332c380925803a1fe538a545c6f9b8fce7。Java/资源从该源码编译，完整native和数据从SHA锁定的26.04.3.1官方运行时恢复；本机没有从零编译全部LibreOffice内核。`collabora-upstream.lock`及`build-collabora-arm64.sh`保留为独立的全源码构建实验，固定的是较新eeb7ea3提交，不得将它的Java/native混入当前模块。

Android编辑器类、JNI、Web编辑器、字体、注册表与对应许可证均已进入主App。`bash cloud/verify-office-contract.sh`核验运行时清单及宿主入口；传入APK路径可追加产物检查。不得以空组件通过验收。

验证依次区分：扫描构建通过、原版引擎构建通过、单 APK 构建通过、设备上编辑与保存回写通过。源码入口及许可边界见仓库 `docs/SOURCE_REUSE.md`。

本项目自有代码采用MIT；已引入的MPL文件保留来源和修改声明，并提供完整运行时许可和源码入口。GPL/AGPL参考实现没有复制进主工程。

## 覆盖安装

包名保持 `com.localdoc.scanner`。云端不持有开发者本机旧签名密钥，云端 APK 只作构建证据；可覆盖旧版的交付包须回到本机以原签名生成。不要提交私钥、账号配置或私人文档。
