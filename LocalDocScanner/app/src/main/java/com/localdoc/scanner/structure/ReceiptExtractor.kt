package com.localdoc.scanner.structure

object ReceiptExtractor {
    fun fields(raw: String): Map<String, String> {
        val lines = raw.lineSequence().map(String::trim).filter(String::isNotBlank).toList()
        // Currency labels may contain brackets or spaces. Prefer the paid total,
        // never a later cash/change line or a product's amount column.
        val amount = "[￥¥]?\\s*([0-9]+(?:,[0-9]{3})*(?:\\.[0-9]{1,2})?)(?![0-9.])"
        val separator = "\\s*(?:[（(]\\s*(?:人民币|RMB|CNY|元)\\s*[）)])?\\s*[：:]?\\s*(?:人民币|RMB|CNY|￥|¥)?\\s*"
        val total = listOf("(?:实际支付|实付(?:金额)?|支付金额)", "(?:应付(?:金额)?|价税合计|合计(?:金额)?|总计(?:金额)?)", "(?:金额)")
            .firstNotNullOfOrNull { label ->
                val pattern = Regex("$label$separator$amount", RegexOption.IGNORE_CASE)
                lines.asSequence().filterNot { it.contains("找零") || it.contains("退款") }
                    .mapNotNull { pattern.find(it)?.groupValues?.get(1) }.lastOrNull()
            }.orEmpty().replace(",", "")
        val date = Regex("20\\d{2}[-/年.]\\d{1,2}[-/月.]\\d{1,2}").find(raw)?.value.orEmpty()
        val merchant = lines.firstNotNullOfOrNull {
            Regex("^(?:商户(?:名称)?|商家(?:名称)?|店名|门店名称)[：:\\s]+(.+)$").find(it)?.groupValues?.get(1)?.trim()
        } ?: lines.firstOrNull { line ->
            !Regex("(?i)^(?:TEST(?:[_ :—-].*)?|测试(?:样本|小票|收据|票据).*|.*(?:收据|小票|购物凭证)|欢迎光临|谢谢惠顾)$").matches(line) &&
                !Regex("^(?:日期|时间|单号|票号|订单号|合计|总计|实付|应付|金额|电话|地址)[：:\\s（(]").containsMatchIn(line) &&
                !line.matches(Regex("[0-9\\s\\p{Punct}￥¥]+"))
        }.orEmpty()
        return linkedMapOf("商户" to merchant, "日期" to date, "金额" to total,
            "票号" to Regex("(?:单号|票号|订单号)[：:\\s]*([A-Za-z0-9-]+)").find(raw)?.groupValues?.get(1).orEmpty())
    }
    fun custom(raw: String, template: String): Map<String, String> = template.lineSequence().filter(String::isNotBlank).associate { line ->
        val name = line.substringBefore('=').trim()
        require('=' in line && name.isNotBlank()) { "模板格式：字段名=正则表达式" }
        val match = Regex(line.substringAfter('=').trim()).find(raw)
        name to (match?.groupValues?.getOrNull(1) ?: match?.value.orEmpty())
    }
}
