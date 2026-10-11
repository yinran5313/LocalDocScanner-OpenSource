# V5源码复用定位

| 当前缺口 | 最佳现有实现/仓库 | 文件 / 类 / 函数 | 依赖与License | 能否直接复制修改 / 如何接入 |
|---|---|---|---|---|
| 旧Office全文检索 | apache/poi，REL_5_4_1 | poi-scratchpad/src/main/java/org/apache/poi/hwpf/extractor/WordExtractor.java，WordExtractor.getText；hslf/usermodel/HSLFSlideShowImpl.java，getRecords；hslf/record/PPDrawing.java，getTextboxWrappers；TextCharsAtom/TextBytesAtom.getText；poi/src/main/java/org/apache/poi/hssf/usermodel/HSSFWorkbook.java | poi与poi-scratchpad5.4.1、commons-math3、collections4、SparseBitSet；Apache-2.0 | 可直接复用库及适配。LegacyOfficeText读取文字记录避免AWT渲染，再送FullTextIndex；DOC/XLS/PPT已通过Android测试。 |
| 真正合并格/数值XLSX | dhatim/fastexcel，0.18.4 | fastexcel-writer/src/main/java/org/dhatim/fastexcel/Worksheet.java，value/range；Range.merge | fastexcel0.18.4；Apache-2.0 | 直接调用已有依赖，SpreadsheetExport增加类型、合并、原子写入；不抄一份Excel引擎。 |
| PDF连续拖动/吸附/缩放 | androidx，Compose Foundation | compose/foundation/foundation/src/commonMain/kotlin/androidx/compose/foundation/lazy/LazyList.kt，LazyColumn；gestures/snapping/LazyListSnapLayoutInfoProvider.kt，rememberSnapFlingBehavior；gestures/TransformGestureDetector.kt，calculateZoom/calculatePan | 现有Compose BOM2024.12.01；Apache-2.0 | 直接调用已安装库，PdfContinuousPages复用PdfReadSession；慢拖停止后额外对齐，保留自由连续模式。 |
| 多语言OCR | PaddlePaddle/PaddleOCR | configs/rec/PP-OCRv6/PP-OCRv6_medium_rec.yml；docs/version3.x/module_usage/text_recognition.md，模型/语言表 | 已内置PP-OCRv6 medium/tiny、ONNX Runtime；Apache-2.0模型/代码，ORT MIT | 复用同一现有多语言模型。medium50语言，tiny49且不含日文；语言入口贯穿任务，不将UI选项冒充独立模型。 |
| 比例裁切与透视 | 当前OpenCV管线；Yalantis/uCrop作方案核对 | uCrop/ucrop/src/main/java/com/yalantis/ucrop/view/CropImageView.java，setTargetAspectRatio；当前edit/CropAspect.fit、cv/DocumentProcessor | OpenCV现有版本；Apache-2.0。uCrop Apache-2.0 | uCrop可复制，但引入第二套裁切引擎不能直接保留文档四角透视/编辑配方，因此未复制；当前小型比例适配复用已验证的透视处理。 |
| Office字体警告中文 | CollaboraOnline/online匹配上游中文PO | browser/po/core-zh_CN.po及core-zh_TW.po；core字体警告key；office-engine/OfficeUiLanguage.injectTranslationOverlay | 保留MPL-2.0；具体commit/PO哈希见OFFICE_ZH_SOURCES.json | 可按MPL复用翻译并保留声明；WebView注入独立覆盖脚本，不修改5330项固定核心资源。 |

仓库：[Apache POI](https://github.com/apache/poi/tree/REL_5_4_1)、[fastexcel](https://github.com/dhatim/fastexcel/tree/0.18.4)、[AndroidX](https://github.com/androidx/androidx)、[PaddleOCR](https://github.com/PaddlePaddle/PaddleOCR)、[uCrop](https://github.com/Yalantis/uCrop)。其余扫描检测/展平/PDF引擎来源沿用SOURCE_REUSE.md与既有许可台账，未引入GPL/AGPL源码。

图标为本轮imagegen生成的透明纸张标记，自有资源；提示要求白色折页、薄荷色背页、深绿文档线、四个粗薄荷L形取景角、适合小尺寸Android图标，无文字/外部商标。生成后仅直接复制，Android drawable负责边距与背景；资源SHA256见交付源码清单。内部功能图标继续使用Google Material原矢量，见UI_ICON_SOURCES.json。

DOC测试素材来自apache/poi REL_5_4_1/test-data/document/simple.doc，Apache-2.0；XLS/PPT样例为测试代码用POI生成，只含虚构“拾页索引”文本，无用户文档。
