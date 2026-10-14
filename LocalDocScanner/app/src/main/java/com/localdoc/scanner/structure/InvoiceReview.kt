package com.localdoc.scanner.structure

import java.math.BigDecimal

/** Offline consistency checks only. Never equate a plausible total with authenticity. */
object InvoiceReview {
    fun type(raw: String): String = when {
        raw.contains("火车票") || raw.contains("铁路电子客票") -> "铁路客票"
        raw.contains("航空运输") || raw.contains("行程单") -> "航空行程单"
        raw.contains("通行费") -> "通行费发票"
        raw.contains("机动车销售") -> "机动车销售发票"
        raw.contains("电子发票") -> "电子发票"
        raw.contains("专用发票") -> "增值税专用发票"
        raw.contains("发票") -> "普通/电子发票"
        else -> "未确定票种"
    }
    fun fields(raw: String): Map<String, String> = linkedMapOf("票种" to type(raw)).apply {
        putAll(StructureExtractor.extract(StructureExtractor.Kind.INVOICE, raw).fields.associate { it.label to it.value })
        val total = Regex("(?:价税合计|小写)[^0-9\\n]{0,16}(-?[0-9]+\\.[0-9]{2})").findAll(raw).lastOrNull()?.groupValues?.get(1).orEmpty()
        put("价税合计", total)
    }
    fun itemRows(rows: List<List<String>>): List<List<String>> {
        val start = rows.indexOfFirst { row -> row.any { it.contains("名称") || it.contains("项目") } && row.any { it.contains("金额") || it.contains("数量") } }
        if (start < 0) return rows
        val after = rows.drop(start)
        val end = after.drop(1).indexOfFirst { row -> row.any { it.contains("价税合计") || it.trim() in listOf("合计", "总计") } }
        return if (end < 0) after else after.take(end + 1)
    }
    fun revalidated(fields: Map<String,String>, rows: List<List<String>>): Map<String,String> {
        val problems=issues(fields,rows)
        val complete=fields["金额(不含税)"]?.toBigDecimalOrNull()!=null && fields["税额"]?.toBigDecimalOrNull()!=null && fields["价税合计"]?.toBigDecimalOrNull()!=null
        return fields + ("勾稽校验" to when { problems.isNotEmpty() -> problems.joinToString("；"); complete -> "本地金额关系一致；不代表真伪核验"; else -> "信息不足，金额关系未完整校验" })
    }
    fun issues(fields: Map<String, String>, rows: List<List<String>>): List<String> = buildList {
        fun amount(vararg hints: String): BigDecimal? = fields.entries.firstOrNull { e -> hints.any { it == e.key } }?.value?.replace("￥", "")?.replace("¥", "")?.replace(",", "")?.trim()?.toBigDecimalOrNull()
        val net = amount("金额", "不含税金额", "金额(不含税)", "合计金额"); val tax = amount("税额", "合计税额"); val total = amount("价税合计")
        if (net != null && tax != null && total != null && (net + tax - total).abs() > BigDecimal("0.02")) add("金额 + 税额与价税合计不一致，请核对原图")
        val rate = fields["税率(%)"]?.trim()?.removeSuffix("%")?.removeSuffix("％")?.toBigDecimalOrNull()
        if (net != null && tax != null && rate != null && (net*rate/BigDecimal(100)-tax).abs()>BigDecimal("0.02")) add("金额 × 税率与税额不一致，请核对")
        val header = rows.firstOrNull().orEmpty()
        fun index(vararg names: String) = header.indexOfFirst { h -> names.any { h.trim() == it } }
        val quantity = index("数量"); val price = index("单价"); val value = index("金额", "不含税金额")
        if (quantity >= 0 && price >= 0 && value >= 0) rows.drop(1).forEachIndexed { i, row ->
            val q = row.getOrNull(quantity)?.toBigDecimalOrNull(); val p = row.getOrNull(price)?.toBigDecimalOrNull(); val a = row.getOrNull(value)?.toBigDecimalOrNull()
            if (q != null && p != null && a != null && (q * p - a).abs() > BigDecimal("0.02")) add("商品第${i + 1}行：数量 × 单价与金额不一致")
        }
        if (fields.filterKeys { it != "票种" && !it.contains("校验") }.values.count { it.isNotBlank() } < 2) add("有效字段不足，请补录号码、日期与金额后复核")
    }
}
