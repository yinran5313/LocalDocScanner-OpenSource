# LocalDocScanner 本地文档扫描

Android 离线扫描、文档库、PP-OCRv6 tiny/medium、PDF工具及同包Office编辑器。开发候选版 **4.4.2-rc1**，ARM64，Android8.0及以上。

支持多页拍摄与手动四角裁边、OpenCV 透视和增强、连续扫描换页判断、可调书籍双页拆分、OCR 与可搜索 PDF、PDF 批注/手写/表单、工作副本预览、另存和分享。文档保存在手机本地。

本版将Collabora完整Office引擎接入同一APK，打开Office文件后进入内置真实排版界面；无需另装Office App。书籍双页可启用Leptonica文字行曲率展平，不可靠的模型保留原页。PDF压缩处理图片对象并保留文字/表单/批注，转图片与永久打码按页释放位图。新增连续书籍拍摄、多图批量编辑、自动纠偏、密码PDF处理联动、独立批注管理、有限范围原文字修改、页码/水印参数、medium敏感范围建议、PKCS12数字签名、CSV/Markdown预览，以及票据/表格XLSX复核、全文检索、文件保存分享与恢复；文档OCR已有持久逐页任务，新增系统强生物识别入口和AES-GCM密码加密备份。**当前为开发候选，Office往返、相机和OCR效果尚未通过手机验收；内部文档保险库、语义模型和任意复杂PDF转完整可编辑Office仍未实现。** 新增工具任务后台续跑、逐页检查点、输入/输出校验、人工复核保留、内部空间清理及复查修复。详细范围及源码入口见 [V4.4.2进度](docs/V4.4.2_PROGRESS.md) 与 [原复用矩阵](docs/SOURCE_REUSE.md)。

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

本项目自有代码采用 [MIT](LICENSE)。上游文件、模型、字体和依赖保留各自许可，见 [THIRD_PARTY_NOTICES](THIRD_PARTY_NOTICES.md)。Leptonica为BSD-2-Clause；Collabora Java/资源和修改文件保留MPL许可，详见 [内置Office来源与修改](office-engine/README.md)。没有复制GPL/AGPL参考仓库实现；独立许可未确认的拼写词典不分发。

贡献请提供可复现样本与修改前后结果；请移除姓名、电话、账号、地址等私人信息后提交公开样本。
