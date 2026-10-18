# 拾页 · LocalDocScanner

Android 离线扫描、文档库、PP-OCRv6 medium、PDF工具及同包Office编辑器。当前版本 **5.2.0 / versionCode 21**，ARM64，Android8.0及以上。OCR仅内置medium，其它模型可选下载或离线导入。

支持多页拍摄与手动四角裁边、OpenCV 透视和增强、连续扫描换页判断、可调书籍双页拆分、OCR 与可搜索 PDF、PDF 批注/手写/表单、工作副本预览、另存和分享。文档保存在手机本地。

同一APK内含Collabora Office引擎，可新建、打开和编辑Word/Excel/PPT工作副本。支持任务检查点、PDF批注/表单/手写与证书签名、表格和票据复核，以及可选本地模型。V5.2修复收据抽取、XLSX维度、续跑竞态、搜索退页和五列表格，改善大画布/底部工具、原图联动校订与结果保存分享。详见 [V5.2说明](docs/V5.2.0_PROGRESS.md) 和 [复用矩阵](docs/SOURCE_REUSE.md)。

新版的模拟器、静态与文件回读验证不代替手机验收。真实拍摄、曲面/反光处理效果、手机Office编辑往返、外部接收与打印仍需真机专项验证。不提供文档问答摘要、PDF转完整可编辑Word或元素可编辑PPT，不依赖云端会员服务。

## 构建

JDK17、Android SDK35、build-tools35.0.0、NDK29.0.14206865、CMake3.22.1；Gradle8.10.2 wrapper。Windows请使用纯英文项目路径。不需要上传私人文档或配置云端OCR。

```text
python LocalDocScanner/tools/restore_binary_assets.py
cd LocalDocScanner
python tools/restore_office_runtime.py
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

Windows 使用 `gradlew.bat`。`local.properties` 可配置本机 SDK，勿提交。二进制模型/字体/测试素材/Gradle wrapper 放在经过 SHA256 校验的 `asset-bundle.zip.NNN` 资产分卷中，只含构建所需的固定资产；恢复脚本只提取清单中的资产，不覆盖当前源码。所有 Kotlin/Java、配置、矢量图标和测试源文件均可在仓库直接阅读。

## 验证状态

本轮JVM回归、Lint和单APK构建通过；Office完整保留文件哈希、JNI入口、ELF依赖及对齐检查通过。Android图像测试仅编译，未进行小米真机拍摄、OCR效果、分享、数据库升级、长文档压力及Office保存回写验收。具体结果随候选Release记录；静态检查不能替代屏幕效果验收。

## 许可

本项目自有代码采用 [MIT](../LICENSE)。上游文件、模型、字体和依赖保留各自许可，见 [THIRD_PARTY_NOTICES](THIRD_PARTY_NOTICES.md)。Leptonica为BSD-2-Clause；Collabora Java/资源和修改文件保留MPL许可，详见 [内置Office来源与修改](office-engine/README.md)。没有复制GPL/AGPL参考仓库实现；独立许可未确认的拼写词典不分发。

贡献请提供可复现样本与修改前后结果；请移除姓名、电话、账号、地址等私人信息后提交公开样本。
