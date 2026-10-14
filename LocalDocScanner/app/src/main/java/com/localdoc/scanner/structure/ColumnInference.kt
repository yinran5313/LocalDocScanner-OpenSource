package com.localdoc.scanner.structure

/** Conservative suggestions: account numbers and leading zeroes must stay text. */
object ColumnInference {
    fun suggest(rows: List<List<String>>): Map<Int, String> = (0 until (rows.maxOfOrNull { it.size } ?: 0)).associateWith { column ->
        val header = rows.firstOrNull()?.getOrNull(column).orEmpty()
        val values = rows.drop(1).mapNotNull { it.getOrNull(column)?.trim()?.takeIf(String::isNotEmpty) }
        when {
            values.isEmpty() || Regex("号码|编号|代码|账号|学号|电话|身份证|卡号|邮编").containsMatchIn(header) -> "TEXT"
            values.all { Regex("[+-]?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?%").matches(it) } -> "PERCENT"
            values.all { runCatching { java.time.LocalDate.parse(it.replace('/', '-')) }.isSuccess } -> "DATE"
            values.all { Regex("[+-]?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?").matches(it) && it.toBigDecimalOrNull()?.precision()?.let { p -> p <= 15 } == true } -> "NUMBER"
            else -> "TEXT"
        }
    }
}
