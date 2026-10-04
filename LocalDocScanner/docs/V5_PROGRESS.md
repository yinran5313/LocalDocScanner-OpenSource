# 拾页 V5.0.0 实现与验收

2026-10-05。Android 8+ / ARM64，versionCode 14，沿用既有 Debug 签名与 applicationId。V5是本轮指定范围的开发交付，不代表全部历史缺口都已解决。

## 本轮改动

| 原编号 | 实际接入 | 核心源码 | 边界 |
|---|---|---|---|
| M02 | 自由、A4横竖、方形、证件、4:3裁切；编辑参数持久化；身份证正反面/银行卡取景提示 | edit/CropAspect.kt、EditScreen.kt、capture/CaptureScreen.kt、PageEntity.cropRatio | 固定比例使用当前选区内矩形；原自由四角透视仍保留。正反面复核、重拍和排序复用现有页编辑与文档库；不是独立自动证件向导。 |
| M04 | 按修改/创建时间、名称、大小排序，网格/列表，收藏与筛选；收藏备份恢复 | ui/home/HomeScreen.kt、DocList.kt、data/db/DocEntity.kt | 排序与布局保留，收藏写入Room7；当前收藏筛选状态是页面状态。 |
| M05 | 命名模板、纸张、导出质量、默认SAF目录与主题；导出入口实际使用 | data/AppPreferences.kt、ui/settings/DefaultSettings.kt、output/DefaultDestination.kt | 默认目录失效会提示；显式保存、同名追加序号；仍可另选位置。特定工具单次选择优先于全局值。 |
| P01 | 多项待提交PDF操作生成真实组合PDF预览，移除/调整后重算 | pdf/PdfEditPipeline.kt、ui/tools/CombinedPdfPreview.kt、jobs/ToolTaskProcessor.kt | 与正式处理共用操作实现；临时结果不写入正式导出记录。原件预览与组合预览分别打开对照。 |
| P03 | 手改证件字段重新校验身份证/银行卡/信用代码/票据等，并刷新关联字段与导出 | structure/StructureExtractor.revalidate、RecognitionTools.CardFlow | 无足够原始MRZ数据时护照校验标为待复核，避免沿用旧的通过状态。 |
| P07 | 自动、简中、繁中、英文、日文、拉丁文字选项贯穿工具与文档任务 | ocr/OcrLanguage.kt、jobs/OcrJobs.kt、DocumentOcrWorker.kt | 使用现有多语言PP-OCRv6模型，不下载独立词典；日文强制medium。高精度仍是medium。语言选项不等于真机精度已经达标。 |
| P08 | 人工指定/拆分合并格、列类型、跨页合并和重复表头去重；真实XLSX合并与类型 | structure/TableLayout.kt、SpreadsheetExport.kt、ui/tools/TableReviewOptions.kt | 自动复杂表格推断仍需人工复核，不恢复原Excel公式；长号码始终文本。跨页列数/类型不一致会拒绝合并。 |
| P09 | 增量索引、生成文件自动排队、外部导入、旧DOC/XLS/PPT、密码PDF显式解锁索引 | data/FullTextIndex.kt、IndexFileWorker.kt、ui/tools/IndexImportActions.kt、office/LegacyOfficeText.kt | 120MB/200万字符上限；无文字层先OCR。密码不保存，解锁后的文字进入本地普通索引。中文检索仍是关键词，未测海量性能。 |

新增品牌“拾页”，图标强化四角取景框。PDF阅读改为连续拖动，可停两页之间；阅读菜单可选整页吸附，支持双指1–4倍缩放、双击放大/复位，放大后可横向平移、纵向继续阅读；页码点击跳转、搜索收起为图标。Office字体限制警告中文化：只读打开/移除嵌入字体并编辑保持原响应语义，不自动绕过字体限制。

## 已有证据

- 103项JVM测试通过，0失败；包含字段再校验、比例数学、XLSX真实合并/数值/长号码、二进制Office提取。
- API35 x86模拟器：5项Android测试通过，覆盖两页之间停留/跳页、双击/双指与继续滚动、整页吸附的零偏移，DOC/XLS/PPT提取，以及未触碰默认值提交/修改后重开持久化。运行实际应用的同一PDF阅读代码。
- app:assembleDebug、lintDebug及Android测试源码编译通过；Lint 0错误、38警告（依赖升级提示及已有代码建议等）。未为消除提示擅自更新依赖。
- Office中文JS测试通过：动态字体名单、简繁体、按钮语义、回调及可编辑内容保持。
- Android实际图标、首页与真实6页PDF截图见交付目录；预览工程业务入口为演示，不作为业务端到端证据。
- APK的版本/中文名称、签名、Office5330项固定资源、JNI与16KB对齐检查另附交付清单。

## 尚待真机与专项验收

小米手机相机、反光阴影黑白效果、各语言OCR精度/延迟、完整Office ARM64中文编辑与保存往返、微信只读URI分享、打印、默认目录授权失效、Room6→7覆盖安装、备份恢复和大文档压力没有本轮真机证据。上游Office核心仍使用固定匹配运行资源，完整Linux从源码重构建证明尚未完成。当前为Debug开发包。

49号报告未选入本轮的M01/M03/M06–M10、P02/P04–P06/P10–P12仍保留在后续清单；不能因版本升到V5就宣布完成。

## 兼容与维护

Room6→7增加favorite与cropRatio默认列；OCR旧自由比例recipe保持原串格式；旧图片PDF任务参数继续按旧原尺寸/质量解释，V5纸张/质量使用新键。CSV规范引用；XLSX原子替换避免Windows同名导出失败。PDF按需渲染可见页，复用一次原生阅读会话；预览与正式编辑共享处理链。一次性补丁脚本放入tools/history，正常构建不用它们。

旧APK和修改前备份保留；不卸载/清除用户数据。源码和所需资源在本机；完整Office匹配上游源码保留在references/opensource-reuse下，许可与可恢复来源在office-engine内记录。

默认控制值首次进入草稿时写入参数队列，确保未点选控件也按界面默认值提交；ToolRequest拆为独立数据文件，预览测试直接使用生产草稿服务。
