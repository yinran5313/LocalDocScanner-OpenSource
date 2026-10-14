# 拾页 V5.1.0

手机本地扫描、PDF处理、OCR、Office编辑与可选模型工具。

新增专用去模糊/降噪、确认式反光/边缘手指候选修补和图文版面Markdown导出。

说明、适用范围与具体开源复用位置见 [V5.1.0](LocalDocScanner/docs/V5.1.0-final.md)。本版本真实样本精度和真机完整流程按用户要求留待后续验收。

## 构建

进入LocalDocScanner，沿用tools/restore_binary_assets.py和tools/restore_office_runtime.py恢复固定资源，再运行：

```
python tools/download_ocr_models.py
python tools/restore_sentencepiece.py
python tools/restore_v51_assets.py
gradlew.bat :app:assembleDebug
```

只内置medium OCR，其余模型在模型管理按需下载或离线导入。新模型完整许可证随App源码提供。Office为固定预编译运行库，匹配上游来源见项目许可说明。
