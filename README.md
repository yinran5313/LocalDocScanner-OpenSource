# 拾页 V5.2.0

手机本地扫描、PDF处理、medium OCR、Office新建/编辑和可选模型工具。修复收据抽取、XLSX范围、续跑竞态、PDF搜索退页及五列表格恢复，改善大画布、底部工具、原图联动校订、批量范围和文件结果入口。

版本5.2.0 / code21，Android8.0+、ARM64。开发及非实拍验证完成，实拍未验；手机已覆盖升级，完整真机回归仍待执行。范围与源码入口见 [V5.2.0说明](LocalDocScanner/docs/V5.2.0_PROGRESS.md)，[检查结果](LocalDocScanner/docs/V5.2.0_TEST_RESULTS.json)，[公开资料检查](LocalDocScanner/docs/V5.2.0_PRIVACY.md)。

## 构建

JDK17、SDK35、NDK29.0.14206865、CMake3.22.1。保留仓库固定资源分卷；进入LocalDocScanner后运行：

```text
python tools/restore_binary_assets.py
python tools/download_ocr_models.py
python tools/restore_office_runtime.py
python tools/restore_sentencepiece.py
python tools/restore_v51_assets.py
gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

Linux使用./gradlew。只内置medium OCR，其它模型按需下载或离线导入；恢复脚本核对来源及SHA，保留已存在且不同的本地文件。手机安装包与源码编译所用签名独立，签名密钥不进公开仓库。

## 许可

自有代码MIT，第三方保留原许可，见 [THIRD_PARTY_NOTICES](LocalDocScanner/THIRD_PARTY_NOTICES.md) 与 [Office来源](LocalDocScanner/office-engine/README.md)。新增图标取自Google Material Design Icons（Apache-2.0）；不复制商业App专有素材。
