package com.localdoc.scanner.model

/** 首页工具箱入口可调起的文件类型 */
enum class FileKind { IMAGE, PDF, ANY, NONE }

/** 首页功能列表的一项。点击后拉起本地文件选择器，再进入对应处理流程。 */
data class ToolEntry(
    val id: String,
    val label: String,
    val accept: FileKind,
    val allowMultiple: Boolean = false
)

val TOOL_ENTRIES: List<ToolEntry> = listOf(
    ToolEntry("images_to_pdf", "图片转PDF", FileKind.IMAGE, allowMultiple = true),
    ToolEntry("pdf_merge", "PDF合并", FileKind.PDF, allowMultiple = true),
    ToolEntry("pdf_split", "PDF拆分", FileKind.PDF),
    ToolEntry("pdf_compress", "PDF压缩", FileKind.PDF),
    ToolEntry("pdf_to_images", "PDF转图片", FileKind.PDF),
    ToolEntry("pdf_text", "PDF提取文字", FileKind.PDF),
    ToolEntry("pdf_encrypt", "PDF加密码", FileKind.PDF),
    ToolEntry("pdf_office", "PDF编辑", FileKind.PDF),
    ToolEntry("pdf_sign", "证书数字签名", FileKind.PDF),
    ToolEntry("ocr", "文字识别", FileKind.ANY, allowMultiple = true),
    ToolEntry("batch_extract", "票据批量汇总", FileKind.ANY, allowMultiple = true),
    ToolEntry("table_xlsx", "表格转XLSX", FileKind.ANY, allowMultiple = true),
    ToolEntry("card", "证件识别", FileKind.IMAGE, allowMultiple = true),
    ToolEntry("barcode", "条码识别", FileKind.IMAGE),
    ToolEntry("long_image", "长图拼接", FileKind.IMAGE, allowMultiple = true),
    ToolEntry("image_edit", "图片编辑", FileKind.IMAGE, allowMultiple = true),
    ToolEntry("pdf_compare", "PDF对比", FileKind.PDF, allowMultiple = true),
    ToolEntry("photo_restore", "照片高清修复", FileKind.IMAGE),
    ToolEntry("region_erase", "区域擦除", FileKind.IMAGE),
    ToolEntry("portrait", "证件照", FileKind.IMAGE),
    ToolEntry("book_unwarp", "书页展平", FileKind.IMAGE, allowMultiple = true),
    ToolEntry("formula", "公式识别", FileKind.IMAGE),
    ToolEntry("translate", "拍照翻译", FileKind.IMAGE),
    ToolEntry("structured_markdown", "转Markdown", FileKind.ANY, allowMultiple = true),
    ToolEntry("pdf_long", "PDF转长图", FileKind.PDF),
    ToolEntry("pdf_enhance", "扫描PDF增强", FileKind.PDF),
    ToolEntry("zip_import", "解压ZIP", FileKind.ANY),
    ToolEntry("qr_create", "生成二维码", FileKind.NONE),
    ToolEntry("semantic_search", "语义检索", FileKind.NONE),
    ToolEntry("barcode_live", "扫码", FileKind.NONE),
    ToolEntry("watermark_camera", "水印相机", FileKind.NONE),
    ToolEntry("scan_ppt", "拍PPT", FileKind.NONE),
    ToolEntry("scan_whiteboard", "拍白板", FileKind.NONE),
    ToolEntry("scan_handwriting", "拍手写", FileKind.NONE),
    ToolEntry("pdf_pages", "页面管理", FileKind.PDF),
    ToolEntry("count_objects", "物体计数", FileKind.IMAGE),
    ToolEntry("sign_library", "手写签名", FileKind.NONE),
    ToolEntry("card_wallet", "证件卡包", FileKind.NONE),
    ToolEntry("image_ppt", "图片转PPT", FileKind.IMAGE, allowMultiple = true)
)
