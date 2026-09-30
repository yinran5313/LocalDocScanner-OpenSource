package com.localdoc.scanner.office

enum class OfficeFamily(val label: String) {
    WORD("文字文档"),
    SHEET("电子表格"),
    SLIDES("演示文稿")
}

data class OfficeFormat(
    val extension: String,
    val mime: String,
    val family: OfficeFamily,
    val label: String,
    val quickEditSupported: Boolean = false
)

/** Collabora/LibreOffice可编辑格式在主App中的统一判定与MIME修正。 */
object OfficeFormats {
    private val formats = listOf(
        OfficeFormat("doc", "application/msword", OfficeFamily.WORD, "Word 97–2003"),
        OfficeFormat("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", OfficeFamily.WORD, "Word", true),
        OfficeFormat("docm", "application/vnd.ms-word.document.macroenabled.12", OfficeFamily.WORD, "Word宏文档"),
        OfficeFormat("dot", "application/msword", OfficeFamily.WORD, "Word模板"),
        OfficeFormat("dotx", "application/vnd.openxmlformats-officedocument.wordprocessingml.template", OfficeFamily.WORD, "Word模板"),
        OfficeFormat("dotm", "application/vnd.ms-word.template.macroenabled.12", OfficeFamily.WORD, "Word宏模板"),
        OfficeFormat("odt", "application/vnd.oasis.opendocument.text", OfficeFamily.WORD, "OpenDocument文字"),
        OfficeFormat("ott", "application/vnd.oasis.opendocument.text-template", OfficeFamily.WORD, "OpenDocument文字模板"),
        OfficeFormat("rtf", "application/rtf", OfficeFamily.WORD, "RTF文字文档"),

        OfficeFormat("xls", "application/vnd.ms-excel", OfficeFamily.SHEET, "Excel 97–2003"),
        OfficeFormat("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", OfficeFamily.SHEET, "Excel", true),
        OfficeFormat("xlsm", "application/vnd.ms-excel.sheet.macroenabled.12", OfficeFamily.SHEET, "Excel宏工作簿"),
        OfficeFormat("xlsb", "application/vnd.ms-excel.sheet.binary.macroenabled.12", OfficeFamily.SHEET, "Excel二进制工作簿"),
        OfficeFormat("xlt", "application/vnd.ms-excel", OfficeFamily.SHEET, "Excel模板"),
        OfficeFormat("xltx", "application/vnd.openxmlformats-officedocument.spreadsheetml.template", OfficeFamily.SHEET, "Excel模板"),
        OfficeFormat("xltm", "application/vnd.ms-excel.template.macroenabled.12", OfficeFamily.SHEET, "Excel宏模板"),
        OfficeFormat("ods", "application/vnd.oasis.opendocument.spreadsheet", OfficeFamily.SHEET, "OpenDocument表格"),
        OfficeFormat("ots", "application/vnd.oasis.opendocument.spreadsheet-template", OfficeFamily.SHEET, "OpenDocument表格模板"),

        OfficeFormat("ppt", "application/vnd.ms-powerpoint", OfficeFamily.SLIDES, "PowerPoint 97–2003"),
        OfficeFormat("pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation", OfficeFamily.SLIDES, "PowerPoint", true),
        OfficeFormat("pptm", "application/vnd.ms-powerpoint.presentation.macroenabled.12", OfficeFamily.SLIDES, "PowerPoint宏演示"),
        OfficeFormat("pps", "application/vnd.ms-powerpoint", OfficeFamily.SLIDES, "PowerPoint放映"),
        OfficeFormat("ppsx", "application/vnd.openxmlformats-officedocument.presentationml.slideshow", OfficeFamily.SLIDES, "PowerPoint放映"),
        OfficeFormat("ppsm", "application/vnd.ms-powerpoint.slideshow.macroenabled.12", OfficeFamily.SLIDES, "PowerPoint宏放映"),
        OfficeFormat("pot", "application/vnd.ms-powerpoint", OfficeFamily.SLIDES, "PowerPoint模板"),
        OfficeFormat("potx", "application/vnd.openxmlformats-officedocument.presentationml.template", OfficeFamily.SLIDES, "PowerPoint模板"),
        OfficeFormat("potm", "application/vnd.ms-powerpoint.template.macroenabled.12", OfficeFamily.SLIDES, "PowerPoint宏模板"),
        OfficeFormat("odp", "application/vnd.oasis.opendocument.presentation", OfficeFamily.SLIDES, "OpenDocument演示"),
        OfficeFormat("otp", "application/vnd.oasis.opendocument.presentation-template", OfficeFamily.SLIDES, "OpenDocument演示模板")
    )
    private val byExtension = formats.associateBy { it.extension }
    private val byMime = formats.groupBy { it.mime.lowercase() }

    fun detect(fileName: String, declaredMime: String = ""): OfficeFormat? {
        val extension = fileName.substringAfterLast('.', "").lowercase()
        byExtension[extension]?.let { return it }
        return byMime[declaredMime.substringBefore(';').trim().lowercase()]?.firstOrNull()
    }

    fun mimeFor(fileName: String, declaredMime: String = ""): String =
        detect(fileName, declaredMime)?.mime ?: declaredMime.ifBlank { "application/octet-stream" }

    fun ensureExtension(fileName: String, format: OfficeFormat?): String {
        if (format == null) return fileName
        val extension = fileName.substringAfterLast('.', "").lowercase()
        if (byExtension.containsKey(extension)) return fileName
        return "$fileName.${format.extension}"
    }

    fun supportedExtensions(): Set<String> = byExtension.keys
}
