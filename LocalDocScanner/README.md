# LocalDocScanner 本地文档扫描

Android 离线扫描、文档库、PP-OCRv6 tiny/medium 和 PDF 工具。开发候选版 **4.3.1-rc1**，ARM64，Android8.0及以上。

支持多页拍摄与手动四角裁边、OpenCV 透视和增强、连续扫描换页判断、可调书籍双页拆分、OCR 与可搜索 PDF、PDF 批注/手写/表单、工作副本预览、另存和分享。文档保存在手机本地。

**当前完整 Office 编辑仍使用外部 Collabora 联动。单 APK 内置完整 Office 引擎、曲面展平、任意 PDF 转可编辑 Office、证书数字签名尚未完成。** 现有简单 OpenXML 编辑不能替代完整排版引擎。详细源码审计和逐项复用入口见 [源码复用矩阵](../docs/SOURCE_REUSE.md)。

## 构建

JDK17、Android SDK35、build-tools35.0.0；Gradle8.10.2 wrapper。不需要上传私人文档或配置云端 OCR。

```text
python LocalDocScanner/tools/restore_binary_assets.py
cd LocalDocScanner
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

Windows 使用 `gradlew.bat`。`local.properties` 可配置本机 SDK，勿提交。二进制模型/字体/测试素材/Gradle wrapper 放在经过 SHA256 校验的 `asset-bundle.zip.NNN` 资产分卷中，只含构建所需的固定资产；恢复脚本只提取清单中的资产，不覆盖当前源码。所有 Kotlin/Java、配置、矢量图标和测试源文件均可在仓库直接阅读。

## 验证状态

本轮 JVM 回归、Lint 和 ARM64 APK 构建通过；Android 图像测试包已编译。未进行小米真机拍摄、OCR 效果、分享、数据库升级、长文档压力及 Office 保存回写验收。JVM Android stub 检查不代表屏幕效果已经验收。

## 许可

本项目自有代码采用 [MIT](../LICENSE)。上游文件、模型、字体和依赖保留各自许可，见 [THIRD_PARTY_NOTICES](THIRD_PARTY_NOTICES.md)。没有复制 GPL/AGPL 参考仓库代码。未来 Collabora 整合须另外满足所用 MPL 文件及三方依赖的来源、修改与源码要求。

贡献请提供可复现样本与修改前后结果；请移除姓名、电话、账号、地址等私人信息后提交公开样本。
