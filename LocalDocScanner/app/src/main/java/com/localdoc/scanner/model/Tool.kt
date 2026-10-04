package com.localdoc.scanner.model

/** 首页工具箱入口可调起的文件类型 */
enum class FileKind { IMAGE, PDF, ANY }

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
    ToolEntry("card", "证件识别", FileKind.IMAGE),
    ToolEntry("barcode", "条码识别", FileKind.IMAGE),
    ToolEntry("long_image", "长图拼接", FileKind.IMAGE, allowMultiple = true),
    ToolEntry("image_edit", "图片编辑", FileKind.IMAGE, allowMultiple = true),
    ToolEntry("pdf_compare", "PDF对比", FileKind.PDF, allowMultiple = true)
)
