package com.localdoc.scanner.structure

object ReceiptExtractor {
    fun fields(raw: String): Map<String, String> {
        val lines = raw.lineSequence().map(String::trim).filter(String::isNotBlank).toList()
        val total = Regex("(?:实付|应付|总计|合计|金额)[：:\\s￥¥]*([0-9]+(?:\\.[0-9]{1,2})?)").findAll(raw).lastOrNull()?.groupValues?.get(1).orEmpty()
        val date = Regex("20\\d{2}[-/年.]\\d{1,2}[-/月.]\\d{1,2}").find(raw)?.value.orEmpty()
        return linkedMapOf("商户" to lines.firstOrNull().orEmpty(), "日期" to date, "金额" to total,
            "票号" to Regex("(?:单号|票号|订单号)[：:\\s]*([A-Za-z0-9-]+)").find(raw)?.groupValues?.get(1).orEmpty())
    }
    fun custom(raw: String, template: String): Map<String, String> = template.lineSequence().filter(String::isNotBlank).associate { line ->
        val name = line.substringBefore('=').trim()
        require('=' in line && name.isNotBlank()) { "模板格式：字段名=正则表达式" }
        val match = Regex(line.substringAfter('=').trim()).find(raw)
        name to (match?.groupValues?.getOrNull(1) ?: match?.value.orEmpty())
    }
}
